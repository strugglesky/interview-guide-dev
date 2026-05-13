package org.example.common.aspect;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.example.common.annotation.RateLimit;
import org.example.common.exception.RateLimitExceededException;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 限流 AOP 切面。
 * 支持同一方法上声明多个 {@link RateLimit}，并逐条执行独立限流规则。
 * 任意一条规则不通过时，直接拒绝当前请求。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /**
     * Redisson 客户端，用于执行 Lua 限流脚本。
     */
    private final RedissonClient redissonClient;

    /**
     * 限流 Lua 脚本文本。
     */
    private static final String LUA_SCRIPT;

    /**
     * Lua 脚本加载到 Redis 后返回的 SHA1 标识。
     */
    private String luaScriptSha;

    /**
     * Redisson 脚本执行器。
     */
    private RScript rScript;

    /**
     * 类加载时读取限流 Lua 脚本内容。
     */
    static {
        try {
            ClassPathResource resource = new ClassPathResource("scripts/rate_limit_single.lua");
            LUA_SCRIPT = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载限流 Lua 脚本失败", e);
        }
    }

    /**
     * 初始化脚本执行器并将 Lua 脚本预加载到 Redis。
     */
    @PostConstruct
    public void init() {
        rScript = redissonClient.getScript(StringCodec.INSTANCE);
        loadScript();
    }

    /**
     * 将脚本加载到 Redis，后续优先通过 SHA1 执行。
     */
    private void loadScript() {
        this.luaScriptSha = rScript.scriptLoad(LUA_SCRIPT);
        log.info("限流 Lua 脚本加载完成, SHA1: {}", luaScriptSha);
    }

    /**
     * 环绕通知：拦截带 {@link RateLimit} 或容器注解的方法。
     * 逐条执行限流规则，全部通过后才继续执行目标方法。
     */
    @Around("@annotation(org.example.common.annotation.RateLimit) || "
            + "@annotation(org.example.common.annotation.RateLimit.Container)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 先解析出目标方法和其上声明的所有限流规则。
        Method method = getTargetMethod(joinPoint);
        List<RateLimit> rateLimits = getRateLimits(method);
        if (rateLimits.isEmpty()) {
            return joinPoint.proceed();
        }

        // 逐条执行限流规则，任意一条失败都直接拦截。
        for (RateLimit rateLimit : rateLimits) {
            if (!tryAcquire(rateLimit, method)) {
                return handleBlockedRequest(joinPoint, method, rateLimit);
            }
        }
        return joinPoint.proceed();
    }

    /**
     * 获取 AOP 当前拦截到的目标方法。
     */
    private Method getTargetMethod(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getMethod();
    }

    /**
     * 读取方法上声明的所有 {@link RateLimit} 注解。
     */
    private List<RateLimit> getRateLimits(Method method) {
        return Arrays.asList(method.getAnnotationsByType(RateLimit.class));
    }

    /**
     * 执行单条限流规则校验。
     */
    private boolean tryAcquire(RateLimit rateLimit, Method method) {
        // 将注解配置转换成 Redis 限流脚本所需的 key 和时间窗口。
        String key = buildRateLimitKey(rateLimit, method);
        long windowMillis = rateLimit.timeUnit().toMillis(rateLimit.interval());
        Boolean allowed = executeLimitScript(key, rateLimit.count(), windowMillis);
        log.debug("限流校验: key={}, dimension={}, allowed={}",
                key, rateLimit.dimension(), allowed);
        return Boolean.TRUE.equals(allowed);
    }

    /**
     * 调用 Redis Lua 脚本完成原子限流判断。
     */
    private Boolean executeLimitScript(String key, int count, long windowMillis) {
        return rScript.evalSha(
                RScript.Mode.READ_WRITE,
                luaScriptSha,
                RScript.ReturnType.BOOLEAN,
                List.of((Object) key),
                count,
                windowMillis
        );
    }

    /**
     * 构造限流 Redis Key。
     * Key 中包含类名、方法名、限流维度和维度取值，便于不同规则相互隔离。
     */
    private String buildRateLimitKey(RateLimit rateLimit, Method method) {
        String methodKey = method.getDeclaringClass().getSimpleName() + ":" + method.getName();
        String dimensionValue = resolveDimensionValue(rateLimit.dimension());
        return "ratelimit:{" + methodKey + "}:" + rateLimit.dimension().name().toLowerCase()
                + ":" + dimensionValue;
    }

    /**
     * 根据限流维度解析对应的限流标识值。
     */
    private String resolveDimensionValue(RateLimit.Dimension dimension) {
        return switch (dimension) {
            case GLOBAL -> "global";
            case IP -> resolveClientIp();
            case USER -> resolveUserId();
        };
    }

    /**
     * 获取当前请求的客户端 IP。
     * 优先读取代理头，取不到时回退到原始远端地址。
     */
    private String resolveClientIp() {
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return "unknown";
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 获取当前请求的用户标识。
     * 当前实现优先读取请求头中的 X-User-Id，其次读取容器中的远端用户。
     */
    private String resolveUserId() {
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return "anonymous";
        }
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return userId.trim();
        }
        return request.getRemoteUser() != null ? request.getRemoteUser() : "anonymous";
    }

    /**
     * 获取当前线程绑定的 HTTP 请求对象。
     */
    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }

    /**
     * 处理限流命中后的返回逻辑。
     * 配置了降级方法时执行降级，否则直接抛出限流异常。
     */
    private Object handleBlockedRequest(ProceedingJoinPoint joinPoint, Method method, RateLimit rateLimit)
            throws Throwable {
        if (rateLimit.fallback() != null && !rateLimit.fallback().isBlank()) {
            return invokeFallback(joinPoint, method, rateLimit.fallback());
        }
        throw new RateLimitExceededException();
    }

    /**
     * 调用限流降级方法。
     * 支持无参降级方法，也支持与原方法参数列表一致的降级方法。
     */
    private Object invokeFallback(ProceedingJoinPoint joinPoint, Method sourceMethod, String fallbackName)
            throws Throwable {
        Object target = joinPoint.getTarget();
        Method fallbackMethod = findFallbackMethod(target.getClass(), sourceMethod, fallbackName);
        if (fallbackMethod == null) {
            throw new RateLimitExceededException("限流降级方法不存在: " + fallbackName);
        }
        // 允许调用私有降级方法，简化业务侧使用方式。
        fallbackMethod.setAccessible(true);
        if (fallbackMethod.getParameterCount() == 0) {
            return fallbackMethod.invoke(target);
        }
        return fallbackMethod.invoke(target, joinPoint.getArgs());
    }

    /**
     * 查找降级方法。
     * 优先查找与原方法参数列表一致的方法，找不到时再尝试无参方法。
     */
    private Method findFallbackMethod(Class<?> targetClass, Method sourceMethod, String fallbackName) {
        try {
            return targetClass.getDeclaredMethod(fallbackName, sourceMethod.getParameterTypes());
        } catch (NoSuchMethodException ignored) {
            return findNoArgFallbackMethod(targetClass, fallbackName);
        }
    }

    /**
     * 查找无参降级方法。
     */
    private Method findNoArgFallbackMethod(Class<?> targetClass, String fallbackName) {
        try {
            return targetClass.getDeclaredMethod(fallbackName);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
