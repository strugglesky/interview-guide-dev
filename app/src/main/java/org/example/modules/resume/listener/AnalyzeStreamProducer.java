package org.example.modules.resume.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.async.AbstractStreamProducer;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.model.AsyncTaskStatus;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.resume.repository.ResumeRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 简历分析任务生产者
 * 负责发送分析任务到 Redis Stream
 */
@Slf4j
@Component
public class AnalyzeStreamProducer extends AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload> {
    private final ResumeRepository resumeRepository;

    protected AnalyzeStreamProducer(RedisService redisService, ResumeRepository resumeRepository) {
        super(redisService);
        this.resumeRepository = resumeRepository;
    }

    record AnalyzeTaskPayload(Long resumeId, String content) {}

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(AnalyzeTaskPayload payload) {
        // 构建发送到 Redis Stream 的消息体，消费者将基于这些字段恢复任务上下文。
        return Map.of(
                AsyncTaskStreamConstants.FIELD_RESUME_ID, payload.resumeId().toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(AnalyzeTaskPayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void onSendFailed(AnalyzeTaskPayload payload, String error) {
        // 任务发送失败时直接回写简历分析状态，避免前端长时间停留在待处理状态。
        resumeRepository.findById(payload.resumeId()).ifPresent(resume -> {
            resume.setAnalyzeStatus(AsyncTaskStatus.FAILED);
            resume.setAnalyzeError(truncateError(error));
            resumeRepository.save(resume);
            log.warn("Analyze task send failed, resume status updated: resumeId={}", payload.resumeId());
        });
    }

}
