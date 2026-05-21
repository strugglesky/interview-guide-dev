package org.example.modules.knowledgebase.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.async.AbstractStreamProducer;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向量化任务生产者。
 * 负责发送知识库向量化任务到 Redis Stream。
 */
@Slf4j
@Component
public class VectorizeStreamProducer
        extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private static final String STREAM_KEY = "stream:knowledgebase:vectorize";
    private static final String TASK_TYPE = "KNOWLEDGE_BASE_VECTORIZATION";
    private static final String PRODUCER_NAME = "vectorizeStreamProducer";

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public record VectorizeTaskPayload(Long kbId, String content) {
    }

    /**
     * @param redisService            Redis 服务
     * @param knowledgeBaseRepository 知识库仓储
     * @return 无返回值
     */
    public VectorizeStreamProducer(
            RedisService redisService,
            KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 根据知识库 ID 发送向量化任务。
     *
     * @param kbId 知识库 ID
     * @return Redis Stream 消息 ID
     */
    public StreamMessageId sendVectorizeTask(Long kbId) {
        return send(new VectorizeTaskPayload(kbId, null));
    }

    /**
     * 根据知识库 ID 和解析内容发送向量化任务。
     *
     * @param kbId    知识库 ID
     * @param content 解析后的文本内容
     * @return Redis Stream 消息 ID
     */
    public StreamMessageId sendVectorizeTask(Long kbId, String content) {
        return send(new VectorizeTaskPayload(kbId, content));
    }

    /**
     * 发送指定载荷的向量化任务。
     *
     * @param payload 向量化任务载荷
     * @return Redis Stream 消息 ID
     */
    public StreamMessageId sendVectorizeTask(VectorizeTaskPayload payload) {
        return send(payload);
    }

    /**
     * @param message 原始消息
     * @return 预处理后的消息
     */
    @Override
    protected VectorizeTaskPayload preProcessMessage(VectorizeTaskPayload message) {
        if (message == null) {
            return null;
        }
        String normalizedContent = StringUtils.hasText(message.content())
                ? message.content().trim()
                : null;
        return new VectorizeTaskPayload(message.kbId(), normalizedContent);
    }

    /**
     * @param message 向量化任务消息
     * @return 无返回值
     */
    @Override
    protected void validateMessage(VectorizeTaskPayload message) {
        super.validateMessage(message);
        if (message.kbId() == null || message.kbId() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库 ID 非法");
        }
        if (!knowledgeBaseRepository.existsById(message.kbId())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
        }
    }

    /**
     * @return 生产者名称
     */
    @Override
    protected String getProducerName() {
        return PRODUCER_NAME;
    }

    /**
     * @param message 向量化任务消息
     * @return Stream Key
     */
    @Override
    protected String getStreamKey(VectorizeTaskPayload message) {
        return STREAM_KEY;
    }

    /**
     * @param message 向量化任务消息
     * @return 任务类型
     */
    @Override
    protected String getTaskType(VectorizeTaskPayload message) {
        return TASK_TYPE;
    }

    /**
     * @param message 向量化任务消息
     * @return 业务标识
     */
    @Override
    protected String getBusinessKey(VectorizeTaskPayload message) {
        return "knowledgeBase:" + message.kbId();
    }

    /**
     * @return 发送失败错误码
     */
    @Override
    protected ErrorCode getSendFailedErrorCode() {
        return ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED;
    }

    /**
     * @param message 向量化任务消息
     * @return 发送失败消息
     */
    @Override
    protected String getSendFailedMessage(VectorizeTaskPayload message) {
        return "知识库向量化任务发送失败";
    }

    /**
     * @param message 向量化任务消息
     * @return 附加元数据
     */
    @Override
    protected Map<String, Object> buildMetadata(VectorizeTaskPayload message) {
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(message.kbId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "知识库不存在"
                ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("knowledgeBaseId", knowledgeBase.getId());
        metadata.put("knowledgeBaseName", knowledgeBase.getName());
        metadata.put("originalFilename", knowledgeBase.getOriginalFilename());
        metadata.put("storageKey", knowledgeBase.getStorageKey());
        metadata.put("contentType", knowledgeBase.getContentType());
        metadata.put("vectorStatus", resolveVectorStatus(knowledgeBase));
        return metadata;
    }

    /**
     * @param message 向量化任务消息
     * @return Stream 消息体
     */
    @Override
    protected Map<String, Object> buildMessage(VectorizeTaskPayload message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kbId", message.kbId());
        if (StringUtils.hasText(message.content())) {
            body.put("content", message.content());
        }
        return body;
    }

    /**
     * @param message   向量化任务消息
     * @param streamKey Stream Key
     * @param payload   已发送载荷
     * @param messageId 消息 ID
     * @return 无返回值
     */
    @Override
    protected void afterSend(
            VectorizeTaskPayload message,
            String streamKey,
            Map<String, Object> payload,
            StreamMessageId messageId
    ) {
        log.info(
                "Vectorize task queued: kbId={}, streamKey={}, messageId={}",
                message.kbId(),
                streamKey,
                messageId
        );
    }

    /**
     * @param knowledgeBase 知识库实体
     * @return 向量化状态名称
     */
    private String resolveVectorStatus(KnowledgeBaseEntity knowledgeBase) {
        VectorStatus vectorStatus = knowledgeBase.getVectorStatus();
        return vectorStatus == null ? VectorStatus.PENDING.name() : vectorStatus.name();
    }
}
