package org.example.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
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
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedisService {
    private final RedissonClient redissonClient;

    public <T> void set(String key, T value) {
        redissonClient.<T>getBucket(key).set(value);
    }

    public <T> void set(String key, T value, Duration ttl) {
        redissonClient.<T>getBucket(key).set(value, ttl);
    }

    public <T> T get(String key) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    public boolean delete(String key) {
        return redissonClient.getBucket(key).delete();
    }

    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    public boolean expire(String key, Duration ttl) {
        return redissonClient.getBucket(key).expire(ttl);
    }

    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit timeUnit) {
        try {
            return redissonClient.getLock(lockKey).tryLock(waitTime, leaseTime, timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Try lock interrupted: lockKey={}", lockKey, e);
            return false;
        }
    }

    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    public RStream<String, Object> getStream(String streamKey) {
        return redissonClient.getStream(streamKey);
    }

    public StreamMessageId addStreamMessage(String streamKey, Map<String, Object> message) {
        if (streamKey == null || streamKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Stream key 不能为空");
        }
        if (message == null || message.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Stream 消息不能为空");
        }
        StreamAddArgs<String, Object> args = StreamAddArgs.entries(message);
        if (AsyncTaskStreamConstants.STREAM_MAX_LEN > 0) {
            args = args.trim().maxLen(AsyncTaskStreamConstants.STREAM_MAX_LEN);
        }
        return getStream(streamKey).add(args);
    }

    public void createStreamGroup(String streamKey, String group) {
        getStream(streamKey).createGroup(StreamCreateGroupArgs.name(group).makeStream());
    }

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

    public long ackStreamMessage(String streamKey, String group, StreamMessageId... messageIds) {
        return getStream(streamKey).ack(group, messageIds);
    }

    public long deleteStreamMessage(String streamKey, StreamMessageId... messageIds) {
        return getStream(streamKey).remove(messageIds);
    }
}
