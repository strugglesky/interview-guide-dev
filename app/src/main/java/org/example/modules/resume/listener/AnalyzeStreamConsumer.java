package org.example.modules.resume.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.async.AbstractStreamConsumer;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeRepository;
import org.example.modules.resume.service.ResumeGradingService;
import org.example.modules.resume.service.ResumePersistenceService;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简历分析 Stream 消费者
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer
        extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

    private final RedisService redisService;
    private final ResumeGradingService gradingService;
    private final ResumePersistenceService persistenceService;
    private final ResumeRepository resumeRepository;

    protected AnalyzeStreamConsumer(
            RedisService redisService,
            ResumeGradingService gradingService,
            ResumePersistenceService persistenceService,
            ResumeRepository resumeRepository
    ) {
        super(redisService);
        this.redisService = redisService;
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
    }

    record AnalyzePayload(Long resumeId, String content) {}

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "resume-analyze-consumer";
    }

    @Override
    protected AnalyzePayload parsePayload(
            StreamMessageId messageId,
            Map<String, String> data
    ) {
        String resumeIdValue = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (!StringUtils.hasText(resumeIdValue) || !StringUtils.hasText(content)) {
            log.warn("Invalid analyze message: messageId={}, data={}", messageId, data);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历分析消息缺少必要字段");
        }
        try {
            return new AnalyzePayload(Long.parseLong(resumeIdValue), content);
        } catch (NumberFormatException e) {
            log.warn("Invalid resumeId in analyze message: messageId={}, resumeId={}", messageId, resumeIdValue);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历分析消息中的 resumeId 非法");
        }
    }

    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void markProcessing(AnalyzePayload payload) {
        // 消费开始时先更新为处理中，便于前端感知异步任务状态。
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(AnalyzePayload payload) {
        ResumeEntity resume = resumeRepository.findById(payload.resumeId()).orElse(null);
        if (resume == null) {
            // 简历已经被删除时直接丢弃消息，避免无意义重试。
            log.warn("Resume not found, skip analyze task: resumeId={}", payload.resumeId());
            return;
        }
        ResumeAnalysisResponse response = gradingService.analyzeResume(payload.content());
        persistenceService.saveAnalysis(resume, response);
        log.info(
                "Resume analyze task processed: resumeId={}, overallScore={}",
                payload.resumeId(),
                response.overallScore()
        );
    }

    @Override
    protected void markCompleted(AnalyzePayload payload) {
        // 分析结果持久化完成后，统一将异步任务状态置为完成。
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(AnalyzePayload payload, int retryCount) {
        try {
            StreamMessageId messageId = redisService.addStreamMessage(
                    streamKey(),
                    buildRetryMessage(payload, retryCount)
            );
            // 重新投递成功后恢复为待处理，等待下一次消费。
            updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PENDING, null);
            log.info(
                    "Resume analyze task retried: resumeId={}, retryCount={}, messageId={}",
                    payload.resumeId(),
                    retryCount,
                    messageId
            );
        } catch (Exception e) {
            log.error(
                    "Retry resume analyze task failed: resumeId={}, retryCount={}",
                    payload.resumeId(),
                    retryCount,
                    e
            );
            markFailed(payload, resolveRetryError(e));
        }
    }

    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        try {
            resumeRepository.findById(resumeId).ifPresentOrElse(resume -> {
                resume.setAnalyzeStatus(status);
                resume.setAnalyzeError(truncateError(error));
                resumeRepository.save(resume);
                log.debug("Resume analyze status updated: resumeId={}, status={}", resumeId, status);
            }, () -> log.warn("Resume not found when updating analyze status: resumeId={}, status={}", resumeId, status));
        } catch (Exception e) {
            log.error("Update resume analyze status failed: resumeId={}, status={}", resumeId, status, e);
        }
    }

    private Map<String, Object> buildRetryMessage(AnalyzePayload payload, int retryCount) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("taskType", taskDisplayName());
        message.put("sentAt", Instant.now().toString());
        message.put(AsyncTaskStreamConstants.FIELD_RESUME_ID, String.valueOf(payload.resumeId()));
        message.put(AsyncTaskStreamConstants.FIELD_CONTENT, payload.content());
        message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
        return message;
    }

    private String resolveRetryError(Exception exception) {
        String error = exception.getMessage();
        if (!StringUtils.hasText(error)) {
            return "简历分析任务重试发送失败";
        }
        return truncateError(error);
    }

    private String truncateError(String error) {
        if (!StringUtils.hasText(error)) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
