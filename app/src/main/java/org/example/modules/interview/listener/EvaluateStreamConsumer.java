package org.example.modules.interview.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.common.async.AbstractStreamConsumer;
import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.repository.InterviewSessionRepository;
import org.example.modules.interview.service.AnswerEvaluationService;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 面试评估 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行评估
 */
@Slf4j
@Component
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload> {
    private final RedisService redisService;
    private final InterviewSessionRepository sessionRepository;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;


    protected EvaluateStreamConsumer(RedisService redisService,
                                     InterviewSessionRepository interviewSessionRepository,
                                     AnswerEvaluationService evaluationService,
                                     InterviewPersistenceService persistenceService,
                                     ObjectMapper objectMapper,
                                     LlmProviderRegistry llmProviderRegistry
    ) {
        super(redisService);
        this.redisService = redisService;
        this.sessionRepository = interviewSessionRepository;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
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
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "interview-evaluate-consumer";
    }

    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        if (!StringUtils.hasText(sessionId)) {
            log.warn("Invalid evaluate message: messageId={}, data={}", messageId, data);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试评估消息缺少 sessionId");
        }
        return new EvaluatePayload(sessionId.strip());
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(EvaluatePayload payload) {
        InterviewSessionEntity session = loadSession(payload.sessionId());
        if (session == null) {
            log.warn("Interview session not found, skip evaluate task: sessionId={}", payload.sessionId());
            return;
        }
        List<InterviewQuestionDTO> questions = loadQuestionsForEvaluation(session);
        evaluationService.evaluateInterview(
                llmProviderRegistry.getChatClientOrDefault(session.getLlmProvider()),
                payload.sessionId(),
                resolveResumeText(session),
                questions
        );
        log.info("Interview evaluate task processed: sessionId={}, questionCount={}",
                payload.sessionId(), questions.size());
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {
        try {
            StreamMessageId messageId = redisService.addStreamMessage(streamKey(), buildRetryMessage(payload, retryCount));
            updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.PENDING, null);
            log.info("Interview evaluate task retried: sessionId={}, retryCount={}, messageId={}",
                    payload.sessionId(), retryCount, messageId);
        } catch (Exception e) {
            log.error("Retry interview evaluate task failed: sessionId={}, retryCount={}",
                    payload.sessionId(), retryCount, e);
            markFailed(payload, resolveRetryError(e));
        }
    }

    record EvaluatePayload(String sessionId) {
    }

    private InterviewSessionEntity loadSession(String sessionId) {
        Optional<InterviewSessionEntity> session = sessionRepository.findBySessionIdWithResume(sessionId);
        return session.orElse(null);
    }

    private List<InterviewQuestionDTO> loadQuestionsForEvaluation(InterviewSessionEntity session) {
        List<InterviewQuestionDTO> questions = deserializeQuestions(session.getQuestionsJson());
        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(session.getSessionId());
        if (answers.isEmpty()) {
            return questions;
        }
        Map<Integer, InterviewAnswerEntity> answerMap = answers.stream().collect(Collectors.toMap(
                InterviewAnswerEntity::getQuestionIndex,
                answer -> answer,
                (left, right) -> right
        ));
        return mergeQuestionsWithAnswers(questions, answerMap);
    }

    private List<InterviewQuestionDTO> deserializeQuestions(String questionsJson) {
        if (!StringUtils.hasText(questionsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    questionsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, InterviewQuestionDTO.class)
            );
        } catch (Exception e) {
            log.error("Deserialize interview questions failed", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化面试问题列表失败", e);
        }
    }

    private List<InterviewQuestionDTO> mergeQuestionsWithAnswers(
            List<InterviewQuestionDTO> questions,
            Map<Integer, InterviewAnswerEntity> answerMap
    ) {
        List<InterviewQuestionDTO> mergedQuestions = new ArrayList<>();
        for (InterviewQuestionDTO question : questions) {
            InterviewAnswerEntity answer = answerMap.get(question.questionIndex());
            if (answer == null) {
                mergedQuestions.add(question);
                continue;
            }
            mergedQuestions.add(new InterviewQuestionDTO(
                    question.questionIndex(),
                    question.question(),
                    question.type(),
                    question.category(),
                    question.topicSummary(),
                    answer.getUserAnswer(),
                    answer.getScore(),
                    answer.getFeedback(),
                    question.isFollowUp(),
                    question.parentQuestionIndex()
            ));
        }
        return mergedQuestions;
    }

    private String resolveResumeText(InterviewSessionEntity session) {
        if (session.getResumeId() == null) {
            return "";
        }
        return session.getResume() == null || !StringUtils.hasText(session.getResume().getResumeText())
                ? ""
                : session.getResume().getResumeText().strip();
    }

    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findBySessionId(sessionId).ifPresentOrElse(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(truncateError(error));
                sessionRepository.save(session);
                log.debug("Interview evaluate status updated: sessionId={}, status={}", sessionId, status);
            }, () -> log.warn("Interview session not found when updating evaluate status: sessionId={}, status={}",
                    sessionId, status));
        } catch (Exception e) {
            log.error("Update interview evaluate status failed: sessionId={}, status={}", sessionId, status, e);
        }
    }

    private Map<String, Object> buildRetryMessage(EvaluatePayload payload, int retryCount) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("taskType", taskDisplayName());
        message.put("sentAt", Instant.now().toString());
        message.put(AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId());
        message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
        return message;
    }

    private String resolveRetryError(Exception exception) {
        String error = exception.getMessage();
        if (!StringUtils.hasText(error)) {
            return "面试评估任务重试发送失败";
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
