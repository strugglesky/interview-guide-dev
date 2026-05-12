package org.example.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 服务封装。
 * 提供通用的 Redis 操作，包括缓存、分布式锁、Stream 消息队列等。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedisService {
    private final RedissonClient redissonClient;

    /**
     * 写入缓存。
     */
    public <T> void set(String key, T value) {
        redissonClient.<T>getBucket(key).set(value);
    }

    /**
     * 写入带过期时间的缓存。
     */
    public <T> void set(String key, T value, Duration ttl) {
        redissonClient.<T>getBucket(key).set(value, ttl);
    }

    /**
     * 读取缓存。
     */
    public <T> T get(String key) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    /**
     * 删除缓存。
     */
    public boolean delete(String key) {
        return redissonClient.getBucket(key).delete();
    }

    /**
     * 判断 key 是否存在。
     */
    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    /**
     * 设置 key 的过期时间。
     */
    public boolean expire(String key, Duration ttl) {
        return redissonClient.getBucket(key).expire(ttl);
    }

    /**
     * 获取分布式锁对象。
     */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    /**
     * 尝试获取分布式锁。
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit timeUnit) {
        try {
            return redissonClient.getLock(lockKey).tryLock(waitTime, leaseTime, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Try lock interrupted: lockKey={}", lockKey, e);
            return false;
        }
    }

    /**
     * 释放当前线程持有的分布式锁。
     */
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * 获取 Redis Stream 对象。
     */
    public RStream<String, Object> getStream(String streamKey) {
        return redissonClient.getStream(streamKey);
    }

    /**
     * 向 Stream 写入消息。
     */
    public StreamMessageId addStreamMessage(String streamKey, Map<String, Object> message) {
        return getStream(streamKey).add(StreamAddArgs.entries(message));
    }

    /**
     * 创建 Stream 消费组。
     */
    public void createStreamGroup(String streamKey, String group) {
        getStream(streamKey).createGroup(StreamCreateGroupArgs.name(group).makeStream());
    }

    /**
     * 读取消费组中的新消息。
     */
    public Map<StreamMessageId, Map<String, Object>> readGroup(
            String streamKey,
            String group,
            String consumer,
            int count,
            Duration timeout
    ) {
        StreamReadGroupArgs args = StreamReadGroupArgs.neverDelivered()
                .count(count)
                .timeout(timeout);
        return getStream(streamKey).readGroup(group, consumer, args);
    }

    /**
     * 确认 Stream 消息已消费。
     */
    public long ackStreamMessage(String streamKey, String group, StreamMessageId... messageIds) {
        return getStream(streamKey).ack(group, messageIds);
    }

    /**
     * 删除 Stream 消息。
     */
    public long deleteStreamMessage(String streamKey, StreamMessageId... messageIds) {
        return getStream(streamKey).remove(messageIds);
    }
}
