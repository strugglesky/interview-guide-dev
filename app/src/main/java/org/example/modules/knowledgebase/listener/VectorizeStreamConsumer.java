package org.example.modules.knowledgebase.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.async.AbstractStreamConsumer;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.example.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库向量化 Stream 消费者。
 */
@Slf4j
@Component
public abstract class VectorizeStreamConsumer
        extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final RedisService redisService;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    protected VectorizeStreamConsumer(
            RedisService redisService,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        super(redisService);
        this.redisService = redisService;
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    record VectorizePayload(Long kbId, String content) {}

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    @Override
    protected VectorizePayload parsePayload(
            StreamMessageId messageId,
            Map<String, String> data
    ) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (kbIdStr == null || content == null) {
            log.warn("Invalid vectorize message, skip: messageId={}", messageId);
            return null;
        }
        return new VectorizePayload(Long.parseLong(kbIdStr), content);
    }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void markProcessing(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(VectorizePayload payload) {
        vectorService.vectorizeAndStore(payload.kbId(), payload.content());
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        try {
            StreamMessageId messageId = redisService.addStreamMessage(
                    streamKey(),
                    buildRetryMessage(payload, retryCount)
            );
            updateVectorStatus(payload.kbId(), VectorStatus.PENDING, null);
            log.info(
                    "Vectorize task retried: kbId={}, retryCount={}, messageId={}",
                    payload.kbId(),
                    retryCount,
                    messageId
            );
        } catch (Exception e) {
            log.error(
                    "Retry vectorize task failed: kbId={}, retryCount={}",
                    payload.kbId(),
                    retryCount,
                    e
            );
            markFailed(payload, resolveRetryError(e));
        }
    }

    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);
                kb.setVectorError(error);
                knowledgeBaseRepository.save(kb);
                log.debug("Vector status updated: kbId={}, status={}", kbId, status);
            });
        } catch (Exception e) {
            log.error("Update vector status failed: kbId={}, status={}", kbId, status, e);
        }
    }

    private Map<String, Object> buildRetryMessage(VectorizePayload payload, int retryCount) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("taskType", taskDisplayName());
        message.put("sentAt", Instant.now().toString());
        message.put(AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString());
        message.put(AsyncTaskStreamConstants.FIELD_CONTENT, payload.content());
        message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
        return message;
    }

    private String resolveRetryError(Exception exception) {
        String error = exception.getMessage();
        if (error == null || error.isBlank()) {
            return "向量化任务重试发送失败";
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
