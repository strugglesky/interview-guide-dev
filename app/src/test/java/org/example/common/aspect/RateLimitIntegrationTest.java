package org.example.common.aspect;

import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

//@Disabled("需要真实 Redis 环境，按需手动启用")
@DisplayName("限流脚本测试")
public class RateLimitIntegrationTest {
    private static final String SCRIPT_PATH = "scripts/rate_limit_single.lua";

    private RedissonClient redissonClient;
    private RScript rScript;
    private String scriptSha;
    private String testKeyPrefix;

    @BeforeEach
    void setUp() throws IOException {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        redissonClient = Redisson.create(config);
        rScript = redissonClient.getScript(StringCodec.INSTANCE);
        scriptSha = rScript.scriptLoad(loadScript());
        testKeyPrefix = "test:rate_limit:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (redissonClient == null) {
            return;
        }
        RKeys keys = redissonClient.getKeys();
        keys.deleteByPattern(testKeyPrefix + "*");
        redissonClient.shutdown();
    }

    @Nested
    @DisplayName("基础限流场景")
    class BasicRateLimitTests {

        /**
         * 验证在未达到阈值时允许请求通过。
         */
        @Test
        @DisplayName("未达到阈值时应允许通过")
        void shouldAllowWhenRequestCountIsBelowLimit() {
            String key = buildKey("allow");

            Boolean first = executeScript(key, 2, 1_000);
            Boolean second = executeScript(key, 2, 1_000);

            assertThat(first).isTrue();
            assertThat(second).isTrue();
        }

        /**
         * 验证达到阈值后会拒绝新的请求。
         */
        @Test
        @DisplayName("达到阈值后应拒绝请求")
        void shouldBlockWhenRequestCountReachesLimit() {
            String key = buildKey("block");

            Boolean first = executeScript(key, 2, 1_000);
            Boolean second = executeScript(key, 2, 1_000);
            Boolean third = executeScript(key, 2, 1_000);

            assertThat(first).isTrue();
            assertThat(second).isTrue();
            assertThat(third).isFalse();
        }
    }

    @Nested
    @DisplayName("窗口恢复场景")
    class WindowRecoveryTests {

        /**
         * 验证时间窗口过后可以重新获取访问资格。
         */
        @Test
        @DisplayName("窗口过期后应恢复访问")
        void shouldAllowAgainAfterWindowExpires() throws InterruptedException {
            String key = buildKey("recover");

            Boolean first = executeScript(key, 1, 200);
            Boolean blocked = executeScript(key, 1, 200);

            Thread.sleep(250);

            Boolean recovered = executeScript(key, 1, 200);

            assertThat(first).isTrue();
            assertThat(blocked).isFalse();
            assertThat(recovered).isTrue();
        }
    }

    @Nested
    @DisplayName("参数边界场景")
    class ParameterEdgeCaseTests {

        /**
         * 验证非法 limit 会直接拒绝请求。
         */
        @Test
        @DisplayName("limit 小于等于 0 时应拒绝请求")
        void shouldRejectWhenLimitIsInvalid() {
            Boolean allowed = executeScript(buildKey("invalid-limit"), 0, 1_000);

            assertThat(allowed).isFalse();
        }

        /**
         * 验证非法窗口时脚本按当前设计直接放行。
         */
        @Test
        @DisplayName("窗口小于等于 0 时应直接放行")
        void shouldAllowWhenWindowIsInvalid() {
            Boolean allowed = executeScript(buildKey("invalid-window"), 1, 0);

            assertThat(allowed).isTrue();
        }
    }

    /**
     * 执行一次限流脚本。
     */
    private Boolean executeScript(String key, int limit, long windowMillis) {
        return rScript.evalSha(
                RScript.Mode.READ_WRITE,
                scriptSha,
                RScript.ReturnType.BOOLEAN,
                List.of((Object) key),
                limit,
                windowMillis
        );
    }

    /**
     * 生成当前测试专用的 Redis Key，避免不同测试之间互相污染。
     */
    private String buildKey(String suffix) {
        return testKeyPrefix + ":" + suffix;
    }

    /**
     * 读取限流脚本文本。
     */
    private String loadScript() throws IOException {
        ClassPathResource resource = new ClassPathResource(SCRIPT_PATH);
        return new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
