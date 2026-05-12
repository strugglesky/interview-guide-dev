package org.example.infrastructure.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisServiceTest {

  @Mock
  private RedissonClient redissonClient;

  @Mock
  private RBucket<String> bucket;

  @Mock
  private RLock lock;

  @Mock
  private RStream<String, Object> stream;

  private RedisService redisService;

  @BeforeEach
  void setUp() {
    redisService = new RedisService(redissonClient);
  }

  /**
   * Test cache set, get, expire and delete.
   */
  @Test
  void testCacheOperations() {
    String key = "cache:test";
    Duration ttl = Duration.ofMinutes(5);
    when(redissonClient.<String>getBucket(key)).thenReturn(bucket);
    when(bucket.get()).thenReturn("value");
    when(bucket.expire(ttl)).thenReturn(true);
    when(bucket.delete()).thenReturn(true);

    redisService.set(key, "value");
    redisService.set(key, "value", ttl);
    String value = redisService.get(key);
    boolean expired = redisService.expire(key, ttl);
    boolean deleted = redisService.delete(key);

    assertThat(value).isEqualTo("value");
    assertThat(expired).isTrue();
    assertThat(deleted).isTrue();
    verify(bucket).set("value");
    verify(bucket).set("value", ttl);
  }

  /**
   * Test distributed lock acquire and release.
   */
  @Test
  void testLockOperations() throws InterruptedException {
    String lockKey = "lock:test";
    when(redissonClient.getLock(lockKey)).thenReturn(lock);
    when(lock.tryLock(1, 10, TimeUnit.SECONDS)).thenReturn(true);
    when(lock.isHeldByCurrentThread()).thenReturn(true);

    boolean locked = redisService.tryLock(lockKey, 1, 10, TimeUnit.SECONDS);
    redisService.unlock(lockKey);

    assertThat(redisService.getLock(lockKey)).isSameAs(lock);
    assertThat(locked).isTrue();
    verify(lock).unlock();
  }

  /**
   * Test Stream add, read, ack and delete.
   */
  @Test
  void testStreamOperations() {
    String streamKey = "stream:test";
    String group = "test-group";
    String consumer = "test-consumer";
    StreamMessageId messageId = new StreamMessageId(1, 0);
    Map<String, Object> message = Map.of("type", "test");
    Map<StreamMessageId, Map<String, Object>> messages = Map.of(messageId, message);

    when(redissonClient.<String, Object>getStream(streamKey)).thenReturn(stream);
    when(stream.add(any(StreamAddArgs.class))).thenReturn(messageId);
    when(stream.readGroup(eq(group), eq(consumer), any(StreamReadGroupArgs.class)))
        .thenReturn(messages);
    when(stream.ack(group, messageId)).thenReturn(1L);
    when(stream.remove(messageId)).thenReturn(1L);

    StreamMessageId addedId = redisService.addStreamMessage(streamKey, message);
    redisService.createStreamGroup(streamKey, group);
    Map<StreamMessageId, Map<String, Object>> readMessages = redisService.readGroup(
        streamKey,
        group,
        consumer,
        10,
        Duration.ofSeconds(1)
    );
    long ackCount = redisService.ackStreamMessage(streamKey, group, messageId);
    long deleteCount = redisService.deleteStreamMessage(streamKey, messageId);

    assertThat(addedId).isEqualTo(messageId);
    assertThat(readMessages).isEqualTo(messages);
    assertThat(ackCount).isEqualTo(1L);
    assertThat(deleteCount).isEqualTo(1L);
    verify(stream).createGroup(any(StreamCreateGroupArgs.class));
  }
}
