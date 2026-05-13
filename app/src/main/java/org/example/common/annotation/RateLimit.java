package org.example.common.annotation;

import org.example.common.aspect.RateLimitAspect;

import java.lang.annotation.*;

/**
 * 限流注解
 * 用于方法级别的限流控制，支持可重复注解实现多维度独立限流
 * <p>
 * 每个注解实例代表一条独立的限流规则，拥有独立的 count/interval/timeUnit 配置。
 * 同一方法上可标注多个 @RateLimit，所有规则必须全部通过才允许请求。
 * <p>
 * 示例：
 * <pre>
 * @RateLimit(dimension = Dimension.GLOBAL, count = 100)
 * @RateLimit(dimension = Dimension.IP, count = 5)
 * public Result query() { ... }
 * </pre>
 *
 * @see RateLimitAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimit.Container.class)
public @interface RateLimit {
    /**
     * 限流维度枚举
     */
    enum Dimension {
        /**
         * 全局限流：对所有请求统一限流
         */
        GLOBAL,
        /**
         * IP限流：按客户端IP地址限流
         */
        IP,
        /**
         * 用户限流：按用户ID限流
         */
        USER
    }


    //容器注解
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Container{
        RateLimit[] value();
    }
}
