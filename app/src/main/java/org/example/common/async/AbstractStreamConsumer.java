package org.example.common.async;

import lombok.extern.slf4j.Slf4j;
import org.example.infrastructure.redis.RedisService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者模板基类。
 * <p>
 * 将消费循环、ACK、重试与生命周期管理收敛到统一模板，子类仅关注业务处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamConsumer<T>{
    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }
}
