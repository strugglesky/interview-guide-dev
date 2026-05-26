package org.example.common.async;

import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.redisson.api.stream.StreamMessageId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * 统一消息发送骨架与失败处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {
    private final RedisService redisService;

    protected AbstractStreamProducer(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * 发送任务。
     *
     * @param payload 任务负载
     */
    protected void sendTask(T payload){
        if (payload == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务负载不能为空");
        }
        if (streamKey() == null || streamKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Stream Key 不能为空");
        }

        try {
            // 先构造消息体，并补充统一的任务元数据
            Map<String, String> message = buildMessage(payload);
            if (message == null || message.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Stream 消息体不能为空");
            }

            Map<String, Object> streamMessage = new LinkedHashMap<>();
            streamMessage.put("taskType", taskDisplayName());
            streamMessage.put("sentAt", Instant.now().toString());
            streamMessage.putAll(message);

            // 将任务写入 Redis Stream，交由对应消费者异步处理
            StreamMessageId messageId = redisService.addStreamMessage(streamKey(), streamMessage);
            log.info(
                    "Stream task sent: task={}, streamKey={}, payload={}, messageId={}",
                    taskDisplayName(),
                    streamKey(),
                    payloadIdentifier(payload),
                    messageId
            );
        } catch (BusinessException e) {
            onSendFailed(payload, truncateError(e.getMessage()));
            throw e;
        } catch (Exception e) {
            String error = truncateError(e.getMessage());
            log.error(
                    "Failed to send stream task: task={}, streamKey={}, payload={}",
                    taskDisplayName(),
                    streamKey(),
                    payloadIdentifier(payload),
                    e
            );
            onSendFailed(payload, error);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, taskDisplayName() + "任务发送失败", e);
        }
    }
    /**
     * 错误截断。
     *
     * @param error 错误信息
     * @return 截断后的错误信息
     */
    protected String truncateError(String error){
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void onSendFailed(T payload, String error);
}
