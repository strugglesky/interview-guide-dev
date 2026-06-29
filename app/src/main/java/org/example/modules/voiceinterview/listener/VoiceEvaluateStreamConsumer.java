package org.example.modules.voiceinterview.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.async.AbstractStreamConsumer;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import org.example.modules.voiceinterview.service.VoiceInterviewService;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音面试评估 Stream 消费者
 */
@Service
@Slf4j
public class VoiceEvaluateStreamConsumer extends AbstractStreamConsumer<VoiceEvaluateStreamConsumer.VoiceEvaluatePayload> {
    private final RedisService redisService;
    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;

    public VoiceEvaluateStreamConsumer(RedisService redisService,
                                       VoiceInterviewService voiceInterviewService,
                                       VoiceInterviewEvaluationService evaluationService) {
        super(redisService);
        this.redisService = redisService;
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
    }

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "voice-evaluate-consumer";
    }

    @Override
    protected VoiceEvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID);
        if (!StringUtils.hasText(sessionId)) {
            log.warn("Invalid voice evaluate message: messageId={}, data={}", messageId, data);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "语音面试评估消息缺少 voiceSessionId");
        }
        try {
            Long.parseLong(sessionId.strip());
            return new VoiceEvaluatePayload(sessionId.strip());
        } catch (NumberFormatException e) {
            log.warn("Invalid voiceSessionId in voice evaluate message: messageId={}, voiceSessionId={}",
                    messageId, sessionId);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "语音面试评估消息中的 voiceSessionId 非法");
        }
    }

    @Override
    protected String payloadIdentifier(VoiceEvaluatePayload payload) {
        return "voiceSessionId=" + payload.sessionId();
    }

    @Override
    protected void markProcessing(VoiceEvaluatePayload payload) {
        try {
            voiceInterviewService.updateEvaluateStatus(
                    Long.parseLong(payload.sessionId()), AsyncTaskStatus.PROCESSING, null);
            log.debug("Voice evaluate status updated: sessionId={}, status={}",
                    payload.sessionId(), AsyncTaskStatus.PROCESSING);
        } catch (Exception e) {
            log.error("Update voice evaluate processing status failed: sessionId={}", payload.sessionId(), e);
        }
    }

    @Override
    protected void processBusiness(VoiceEvaluatePayload payload) {
        Long sessionId = Long.parseLong(payload.sessionId());
        if (voiceInterviewService.getSession(sessionId) == null) {
            log.warn("Voice interview session not found, skip evaluate task: sessionId={}", payload.sessionId());
            return;
        }
        evaluationService.generateEvaluation(sessionId);
        log.info("Voice evaluate task processed: sessionId={}", payload.sessionId());
    }

    @Override
    protected void markCompleted(VoiceEvaluatePayload payload) {
        try {
            voiceInterviewService.updateEvaluateStatus(
                    Long.parseLong(payload.sessionId()), AsyncTaskStatus.COMPLETED, null);
            log.debug("Voice evaluate status updated: sessionId={}, status={}",
                    payload.sessionId(), AsyncTaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Update voice evaluate completed status failed: sessionId={}", payload.sessionId(), e);
        }
    }

    @Override
    protected void markFailed(VoiceEvaluatePayload payload, String error) {
        try {
            String truncatedError = StringUtils.hasText(error) && error.length() > 500
                    ? error.substring(0, 500)
                    : error;
            voiceInterviewService.updateEvaluateStatus(
                    Long.parseLong(payload.sessionId()), AsyncTaskStatus.FAILED, truncatedError);
            log.debug("Voice evaluate status updated: sessionId={}, status={}",
                    payload.sessionId(), AsyncTaskStatus.FAILED);
        } catch (Exception e) {
            log.error("Update voice evaluate failed status failed: sessionId={}", payload.sessionId(), e);
        }
    }

    @Override
    protected void retryMessage(VoiceEvaluatePayload payload, int retryCount) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("taskType", taskDisplayName());
            message.put("sentAt", Instant.now().toString());
            message.put(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, payload.sessionId());
            message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
            StreamMessageId messageId = redisService.addStreamMessage(streamKey(), message);
            voiceInterviewService.updateEvaluateStatus(
                    Long.parseLong(payload.sessionId()), AsyncTaskStatus.PENDING, null);
            log.info("Voice evaluate task retried: sessionId={}, retryCount={}, messageId={}",
                    payload.sessionId(), retryCount, messageId);
        } catch (Exception e) {
            log.error("Retry voice evaluate task failed: sessionId={}, retryCount={}",
                    payload.sessionId(), retryCount, e);
            String error = e.getMessage();
            String truncatedError = !StringUtils.hasText(error)
                    ? "语音面试评估任务重试发送失败"
                    : (error.length() > 500 ? error.substring(0, 500) : error);
            markFailed(payload, truncatedError);
        }
    }

    record VoiceEvaluatePayload(String sessionId) {
    }
}
