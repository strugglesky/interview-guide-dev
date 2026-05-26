package org.example.modules.knowledgebase.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.async.AbstractStreamProducer;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 向量化任务生产者。
 * 负责发送知识库向量化任务到 Redis Stream。
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    record VectorizeTaskPayload(Long kbId, String content) {}

    public VectorizeStreamProducer(RedisService redisService, KnowledgeBaseRepository knowledgeBaseRepository) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }
    /**
     * 发送向量化任务到redis stream
     *
     * @param kbId   知识库ID
     * @param content 文档内容
     */
    public void sendVectorizeTask(Long kbId, String content) {
        sendTask(new VectorizeTaskPayload(kbId, content));
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }
    /**
     * 构建向量化任务消息
     *
     * @param payload 载荷
     * @return 载荷消息
     */
    @Override
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    /**
     * 载荷标识
     *
     * @param payload 载荷
     * @return 载荷标识
     */
    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
         return "kbId=" + payload.kbId();
    }

    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, truncateError(error));
    }

    /**
     * 更新向量化状态
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);
            if (error != null) {
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            knowledgeBaseRepository.save(kb);
        });
    }


}
