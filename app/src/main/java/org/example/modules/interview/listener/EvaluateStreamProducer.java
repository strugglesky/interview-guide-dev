package org.example.modules.interview.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.async.AbstractStreamProducer;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.model.AsyncTaskStatus;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<String> {
    private final InterviewSessionRepository interviewSessionRepository;

    protected EvaluateStreamProducer(RedisService redisService, InterviewSessionRepository interviewSessionRepository) {
        super(redisService);
        this.interviewSessionRepository = interviewSessionRepository;
    }

    /**
     * 发送评估任务到 Redis Stream
     *
     * @param sessionId 面试会话ID
     */
    public void sendEvaluateTask(String sessionId) {
        sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "sessionId" + sessionId;
    }


    @Override
    protected void onSendFailed(String sessionId, String error) {
        updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, truncateError(error));
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        interviewSessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            interviewSessionRepository.save(session);
        });
    }
}
