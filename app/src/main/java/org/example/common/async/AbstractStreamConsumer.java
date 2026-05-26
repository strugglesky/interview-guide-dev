package org.example.common.async;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.redisson.api.stream.StreamMessageId;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者模板基类
 * <p>
 * 将消费循环、ACK、重试与生命周期管理收敛到统一模板，子类仅关注业务处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {
    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    @PostConstruct
    public void init() {
        // 初始化消费者实例名并确保同一消费者不会被重复启动
        if (!running.compareAndSet(false, true)) {
            return;
        }
        consumerName = consumerPrefix() + UUID.randomUUID();
        executorService = createExecutorService();
        try {
            ensureConsumerGroup();
            // 启动单线程消费循环，统一处理拉取、ACK 和重试逻辑
            executorService.submit(this::consumeLoop);
            log.info(
                    "Stream consumer started: task={}, streamKey={}, group={}, consumer={}",
                    taskDisplayName(),
                    streamKey(),
                    groupName(),
                    consumerName
            );
        } catch (Exception e) {
            running.set(false);
            shutdownExecutor();
            log.error("Failed to start stream consumer: task={}", taskDisplayName(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, taskDisplayName() + "消费者启动失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        // 先标记停止，避免继续拉取新消息
        running.set(false);
        shutdownExecutor();
        log.info(
                "Stream consumer stopped: task={}, streamKey={}, group={}, consumer={}",
                taskDisplayName(),
                streamKey(),
                groupName(),
                consumerName
        );
    }



    private ExecutorService createExecutorService() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(threadName());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                threadFactory
        );
    }

    private void ensureConsumerGroup() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
        } catch (Exception e) {
            if (isConsumerGroupExists(e)) {
                log.debug("Stream consumer group already exists: streamKey={}, group={}", streamKey(), groupName());
                return;
            }
            throw e;
        }
    }

    private boolean isConsumerGroupExists(Exception exception) {
        return exception.getMessage() != null && exception.getMessage().contains("BUSYGROUP");
    }

    private void consumeLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Map<StreamMessageId, Map<String, Object>> messages = redisService.readGroup(
                        streamKey(),
                        groupName(),
                        consumerName,
                        AsyncTaskStreamConstants.BATCH_SIZE,
                        Duration.ofMillis(AsyncTaskStreamConstants.POLL_INTERVAL_MS)
                );
                if (messages == null || messages.isEmpty()) {
                    continue;
                }
                messages.forEach(this::handleMessageSafely);
            } catch (Exception e) {
                if (isInterrupted(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.error("Stream consume loop failed: task={}, streamKey={}", taskDisplayName(), streamKey(), e);
                sleepQuietly();
            }
        }
    }

    private void handleMessageSafely(StreamMessageId messageId, Map<String, Object> rawData) {
        try {
            handleMessage(messageId, rawData);
        } catch (Exception e) {
            log.error("Handle stream message failed unexpectedly: task={}, messageId={}", taskDisplayName(), messageId, e);
            safeAckAndDelete(messageId);
        }
    }

    private void handleMessage(StreamMessageId messageId, Map<String, Object> rawData) {
        Map<String, String> data = convertData(rawData);
        T payload = parsePayload(messageId, data);
        String payloadId = payloadIdentifier(payload);
        int retryCount = parseRetryCount(data);
        try {
            markProcessing(payload);
            processBusiness(payload);
            markCompleted(payload);
            safeAckAndDelete(messageId);
            log.info("Stream message processed: task={}, messageId={}, payload={}", taskDisplayName(), messageId, payloadId);
        } catch (BusinessException e) {
            handleProcessingFailure(messageId, payload, payloadId, retryCount, e);
        } catch (Exception e) {
            handleProcessingFailure(messageId, payload, payloadId, retryCount, e);
        }
    }

    private void handleProcessingFailure(
            StreamMessageId messageId,
            T payload,
            String payloadId,
            int retryCount,
            Exception exception
    ) {
        int nextRetryCount = retryCount + 1;
        String error = resolveErrorMessage(exception);
        try {
            if (nextRetryCount > AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                markFailed(payload, error);
                log.error(
                        "Stream message failed after retries: task={}, messageId={}, payload={}, retryCount={}",
                        taskDisplayName(),
                        messageId,
                        payloadId,
                        retryCount,
                        exception
                );
            } else {
                retryMessage(payload, nextRetryCount);
                log.warn(
                        "Stream message retry scheduled: task={}, messageId={}, payload={}, retryCount={}",
                        taskDisplayName(),
                        messageId,
                        payloadId,
                        nextRetryCount,
                        exception
                );
            }
        } finally {
            safeAckAndDelete(messageId);
        }
    }

    private Map<String, String> convertData(Map<String, Object> rawData) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawData == null || rawData.isEmpty()) {
            return result;
        }
        rawData.forEach((key, value) -> result.put(key, value == null ? null : String.valueOf(value)));
        return result;
    }

    private int parseRetryCount(Map<String, String> data) {
        String value = data.get(AsyncTaskStreamConstants.FIELD_RETRY_COUNT);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid retry count in stream message: task={}, value={}", taskDisplayName(), value);
            return 0;
        }
    }

    private String resolveErrorMessage(Exception exception) {
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return "异步任务处理失败";
    }

    private void safeAckAndDelete(StreamMessageId messageId) {
        try {
            redisService.ackStreamMessage(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Ack stream message failed: task={}, messageId={}", taskDisplayName(), messageId, e);
        }
        try {
            redisService.deleteStreamMessage(streamKey(), messageId);
        } catch (Exception e) {
            log.error("Delete stream message failed: task={}, messageId={}", taskDisplayName(), messageId, e);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(AsyncTaskStreamConstants.POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isInterrupted(Exception exception) {
        return exception instanceof InterruptedException || exception.getCause() instanceof InterruptedException;
    }

    private void shutdownExecutor() {
        if (executorService == null) {
            return;
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                List<Runnable> droppedTasks = executorService.shutdownNow();
                log.warn("Force shutdown stream consumer executor: task={}, droppedTasks={}", taskDisplayName(), droppedTasks.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }
    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void markProcessing(T payload);

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount);

}
