package org.example.modules.knowledgebase.listener;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.formula.functions.T;
import org.example.common.async.AbstractStreamProducer;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.example.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * 统一消息校验、消息构建、元数据补充与失败处理逻辑。
 */
@Slf4j
@Component
public abstract class VectorizeStreamConsumer extends AbstractStreamProducer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;


    protected VectorizeStreamConsumer(RedisService redisService, KnowledgeBaseVectorService vectorService, KnowledgeBaseRepository knowledgeBaseRepository) {
        super(redisService);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    record VectorizePayload(Long kbId, String content) {}

//    public StreamMessageId send(T message) {
//        T candidate = preProcessMessage(message);
//        validateMessage(candidate);
//
//        String streamKey = getStreamKey(candidate);
//        validateStreamKey(streamKey);
//
//        Map<String, Object> payload = buildPayload(candidate);
//        if (payload.isEmpty()) {
//            throw new BusinessException(ErrorCode.BAD_REQUEST, "Stream 消息体不能为空");
//        }
//
//        try {
//            StreamMessageId messageId = redisService.addStreamMessage(streamKey, payload);
//            afterSend(candidate, streamKey, payload, messageId);
//            log.info(
//                    "Stream message sent: producer={}, streamKey={}, messageId={}, taskType={}, businessKey={}",
//                    getProducerName(),
//                    streamKey,
//                    messageId,
//                    getTaskType(candidate),
//                    getBusinessKey(candidate)
//            );
//            return messageId;
//        } catch (BusinessException e) {
//            throw e;
//        } catch (Exception e) {
//            log.error("Failed to send stream message: streamKey={}", streamKey, e);
//            throw new BusinessException(getSendFailedErrorCode(), getSendFailedMessage(candidate), e);
//        }
//    }

    protected T preProcessMessage(T message) {
        return message;
    }

    protected void validateMessage(T message) {
        if (message == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Stream 消息不能为空");
        }
    }

    protected void validateStreamKey(String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Stream Key 不能为空");
        }
    }

    protected Map<String, Object> buildPayload(T message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskType", getTaskType(message));
        payload.put("producer", getProducerName());
        payload.put("sentAt", Instant.now().toString());

        String businessKey = getBusinessKey(message);
        if (businessKey != null && !businessKey.isBlank()) {
            payload.put("businessKey", businessKey);
        }

        Map<String, Object> metadata = buildMetadata(message);
        if (metadata != null && !metadata.isEmpty()) {
            payload.putAll(metadata);
        }

        Map<String, Object> body = buildMessage(message);
        if (body != null && !body.isEmpty()) {
            payload.putAll(body);
        }
        return payload;
    }

    protected Map<String, Object> buildMetadata(T message) {
        return Map.of();
    }

    protected void afterSend(
            T message,
            String streamKey,
            Map<String, Object> payload,
            StreamMessageId messageId
    ) {
    }

    protected String getSendFailedMessage(T message) {
        return "Stream 消息发送失败";
    }

    protected abstract String getProducerName();

    protected abstract String getStreamKey(T message);

    protected abstract String getTaskType(T message);

    protected abstract String getBusinessKey(T message);

    protected abstract ErrorCode getSendFailedErrorCode();

    protected abstract Map<String, Object> buildMessage(T message);
}