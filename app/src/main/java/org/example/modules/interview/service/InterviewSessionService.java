package org.example.modules.interview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.InterviewSessionCache;
import org.example.modules.interview.listener.EvaluateStreamProducer;
import org.example.modules.interview.model.CreateInterviewRequest;
import org.example.modules.interview.model.HistoricalQuestion;
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewReportDTO;
import org.example.modules.interview.model.InterviewSessionDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.model.SubmitAnswerRequest;
import org.example.modules.interview.model.SubmitAnswerResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionService {
    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request){
        try {
            validateCreateRequest(request);
            Optional<InterviewSessionDTO> unfinishedSession = loadReusableSession(request);
            if (unfinishedSession.isPresent()) {
                return unfinishedSession.get();
            }
            return createFreshSession(request);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建面试会话失败: skillId={}, resumeId={}",
                    request != null ? request.skillId() : null,
                    request != null ? request.resumeId() : null,
                    e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建面试会话失败", e);
        }
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        InterviewSessionCache.CachedSession cachedSession = loadSessionOrThrow(sessionId);
        sessionCache.refreshSessionTTL(sessionId);
        return toSessionDTO(cachedSession);
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        if (resumeId == null) {
            return Optional.empty();
        }
        Optional<String> cachedSessionId = sessionCache.findUnfinishedSessionId(resumeId);
        if (cachedSessionId.isPresent()) {
            return Optional.of(getSession(cachedSessionId.get()));
        }
        Optional<InterviewSessionEntity> sessionEntity = persistenceService.findUnfinishedSession(resumeId);
        if (sessionEntity.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toSessionDTO(restoreSessionToCache(sessionEntity.get())));
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    public InterviewSessionCache.CachedSession findUnfinishedSessionOrThrow(Long resumeId) {
        if (resumeId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历ID不能为空");
        }
        Optional<String> cachedSessionId = sessionCache.findUnfinishedSessionId(resumeId);
        if (cachedSessionId.isPresent()) {
            return loadSessionOrThrow(cachedSessionId.get());
        }
        InterviewSessionEntity sessionEntity = persistenceService.findUnfinishedSession(resumeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
                        "未找到未完成的面试会话"
                ));
        return restoreSessionToCache(sessionEntity);
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                    "completed", true,
                    "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
                "completed", false,
                "question", question
        );
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId){
        InterviewSessionCache.CachedSession cachedSession = loadSessionOrThrow(sessionId);
        List<InterviewQuestionDTO> questions = cachedSession.getQuestions(objectMapper);
        int currentIndex = cachedSession.getCurrentIndex();
        if (currentIndex < 0 || currentIndex >= questions.size()) {
            return null;
        }
        sessionCache.refreshSessionTTL(sessionId);
        return questions.get(currentIndex);
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest  request){
        try {
            InterviewSessionCache.CachedSession cachedSession = loadSessionOrThrow(request.sessionId());
            validateSessionCanAnswer(cachedSession, request.questionIndex());
            List<InterviewQuestionDTO> updatedQuestions = applyAnswerToQuestions(
                    cachedSession.getQuestions(objectMapper),
                    cachedSession.getCurrentIndex(),
                    normalizeAnswer(request.answer())
            );
            sessionCache.updateQuestions(request.sessionId(), updatedQuestions);
            persistAnsweredQuestion(request.sessionId(), updatedQuestions, cachedSession.getCurrentIndex());
            int nextIndex = cachedSession.getCurrentIndex() + 1;
            if (nextIndex >= updatedQuestions.size()) {
                completeInterview(request.sessionId());
                return new SubmitAnswerResponse(false, null, updatedQuestions.size(), updatedQuestions.size());
            }
            updateSessionProgress(request.sessionId(), nextIndex);
            sessionCache.refreshSessionTTL(request.sessionId());
            return new SubmitAnswerResponse(
                    true,
                    updatedQuestions.get(nextIndex),
                    nextIndex,
                    updatedQuestions.size()
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("提交面试答案失败: sessionId={}, questionIndex={}",
                    request != null ? request.sessionId() : null,
                    request != null ? request.questionIndex() : null,
                    e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "提交答案失败", e);
        }
    }

    /**
     * 暂存答案（不进入下一题）
     */
    public void saveAnswer(SubmitAnswerRequest request){
        InterviewSessionCache.CachedSession cachedSession = loadSessionOrThrow(request.sessionId());
        validateSessionCanAnswer(cachedSession, request.questionIndex());
        List<InterviewQuestionDTO> updatedQuestions = applyAnswerToQuestions(
                cachedSession.getQuestions(objectMapper),
                cachedSession.getCurrentIndex(),
                normalizeAnswer(request.answer())
        );
        sessionCache.updateQuestions(request.sessionId(), updatedQuestions);
        sessionCache.refreshSessionTTL(request.sessionId());
        log.info("面试答案已暂存: sessionId={}, currentIndex={}",
                request.sessionId(), cachedSession.getCurrentIndex());
    }

    /**
     * 提前交卷（触发异步评估）
     */
    public void completeInterview(String sessionId){
        InterviewSessionCache.CachedSession cachedSession = loadSessionOrThrow(sessionId);
        validateSessionCanComplete(cachedSession);
        int completedIndex = cachedSession.getQuestions(objectMapper).size();
        sessionCache.updateCurrentIndex(sessionId, completedIndex);
        sessionCache.updateSessionStatus(sessionId, InterviewSessionDTO.SessionStatus.COMPLETED);
        persistenceService.updateCurrentQuestionIndex(sessionId, completedIndex);
        persistenceService.updateSessionStatus(sessionId, AsyncTaskStatus.COMPLETED, null);
        persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        evaluateStreamProducer.sendEvaluateTask(sessionId);
        log.info("面试会话已完成并发送评估任务: sessionId={}", sessionId);
    }

    /**
     * 生成评估报告
     */
    public InterviewReportDTO generateReport(String sessionId) {
        InterviewSessionCache.CachedSession cachedSession = loadSessionOrThrow(sessionId);
        validateSessionCanGenerateReport(cachedSession);
        try {
            InterviewSessionEntity sessionEntity = persistenceService.findBySessionId(sessionId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
                            "面试会话不存在: " + sessionId
                    ));
            return buildInterviewReport(sessionId, cachedSession, sessionEntity);
        } catch (BusinessException e) {
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("生成面试评估报告失败: sessionId={}", sessionId, e);
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, e.getMessage());
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "生成面试评估报告失败", e);
        }
    }

    private Optional<InterviewSessionDTO> loadReusableSession(CreateInterviewRequest request) {
        if (Boolean.TRUE.equals(request.forceCreate()) || request.resumeId() == null) {
            return Optional.empty();
        }
        return findUnfinishedSession(request.resumeId());
    }

    private InterviewSessionDTO createFreshSession(CreateInterviewRequest request) {
        String resumeText = normalizeResumeText(request.resumeText());
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkills(
                llmProviderRegistry.getChatClientOrDefault(request.llmProvider()),
                request.skillId(),
                request.difficulty(),
                resumeText,
                request.questionCount(),
                loadHistoricalQuestions(request.skillId(), request.resumeId()),
                request.customCategories(),
                request.jdText()
        );
        String sessionId = UUID.randomUUID().toString();
        persistenceService.saveNewSession(
                sessionId,
                request.resumeId(),
                questions.size(),
                questions,
                request.llmProvider(),
                request.skillId(),
                request.difficulty()
        );
        sessionCache.saveSession(
                sessionId, resumeText, request.resumeId(), questions, 0, InterviewSessionDTO.SessionStatus.CREATED
        );
        log.info("面试会话创建成功: sessionId={}, resumeId={}, totalQuestions={}",
                sessionId, request.resumeId(), questions.size());
        return new InterviewSessionDTO(
                sessionId, resumeText, questions.size(), 0, questions, InterviewSessionDTO.SessionStatus.CREATED
        );
    }

    private void validateCreateRequest(CreateInterviewRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "创建面试请求不能为空");
        }
        if (!StringUtils.hasText(request.skillId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试主题不能为空");
        }
        if (request.questionCount() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "题目数量必须大于0");
        }
    }

    private InterviewSessionCache.CachedSession loadSessionOrThrow(String sessionId) {
        Optional<InterviewSessionCache.CachedSession> cachedSession = sessionCache.getSession(sessionId);
        if (cachedSession.isPresent()) {
            return cachedSession.get();
        }
        InterviewSessionEntity sessionEntity = persistenceService.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
                        "面试会话不存在: " + sessionId
                ));
        return restoreSessionToCache(sessionEntity);
    }

    private InterviewSessionCache.CachedSession restoreSessionToCache(InterviewSessionEntity sessionEntity) {
        List<InterviewQuestionDTO> questions = hydrateQuestionsWithAnswers(
                deserializeQuestions(sessionEntity.getQuestionsJson()),
                persistenceService.findAnswersBySessionId(sessionEntity.getSessionId())
        );
        sessionCache.saveSession(
                sessionEntity.getSessionId(),
                "",
                sessionEntity.getResumeId(),
                questions,
                safeCurrentIndex(sessionEntity.getCurrentQuestionIndex()),
                InterviewSessionDTO.SessionStatus.valueOf(sessionEntity.getStatus().name())
        );
        return sessionCache.getSession(sessionEntity.getSessionId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
                        "恢复缓存后的面试会话不存在: " + sessionEntity.getSessionId()
                ));
    }

    private InterviewSessionDTO toSessionDTO(InterviewSessionCache.CachedSession cachedSession) {
        List<InterviewQuestionDTO> questions = cachedSession.getQuestions(objectMapper);
        return new InterviewSessionDTO(
                cachedSession.getSessionId(),
                normalizeResumeText(cachedSession.getResumeText()),
                questions.size(),
                cachedSession.getCurrentIndex(),
                questions,
                cachedSession.getStatus()
        );
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
            log.error("反序列化面试问题列表失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化面试问题列表失败", e);
        }
    }

    private List<HistoricalQuestion> loadHistoricalQuestions(String skillId, Long resumeId) {
        List<InterviewQuestionDTO> historyQuestions = persistenceService.getHistoryQuestions(skillId, resumeId);
        if (historyQuestions == null || historyQuestions.isEmpty()) {
            return List.of();
        }
        List<HistoricalQuestion> historicalQuestions = new ArrayList<>();
        for (InterviewQuestionDTO question : historyQuestions) {
            if (question == null || !StringUtils.hasText(question.question())) {
                continue;
            }
            historicalQuestions.add(new HistoricalQuestion(
                    question.question(),
                    question.type(),
                    question.topicSummary()
            ));
        }
        return historicalQuestions;
    }

    private List<InterviewQuestionDTO> hydrateQuestionsWithAnswers(
            List<InterviewQuestionDTO> questions,
            List<InterviewAnswerEntity> answers) {
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        Map<Integer, InterviewAnswerEntity> answerMap = answers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        InterviewAnswerEntity::getQuestionIndex,
                        answer -> answer,
                        (left, right) -> right
                ));
        List<InterviewQuestionDTO> hydratedQuestions = new ArrayList<>();
        for (InterviewQuestionDTO question : questions) {
            InterviewAnswerEntity answer = answerMap.get(question.questionIndex());
            if (answer == null) {
                hydratedQuestions.add(question);
                continue;
            }
            hydratedQuestions.add(new InterviewQuestionDTO(
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
        return hydratedQuestions;
    }

    private void persistAnsweredQuestion(String sessionId, List<InterviewQuestionDTO> questions, int currentIndex) {
        InterviewQuestionDTO answeredQuestion = questions.get(currentIndex);
        persistenceService.saveAnswer(
                sessionId,
                answeredQuestion.questionIndex(),
                answeredQuestion.question(),
                answeredQuestion.category(),
                answeredQuestion.userAnswer(),
                0,
                null
        );
    }

    private void validateSessionCanAnswer(InterviewSessionCache.CachedSession cachedSession, Integer questionIndex) {
        if (cachedSession.getStatus() == InterviewSessionDTO.SessionStatus.COMPLETED
                || cachedSession.getStatus() == InterviewSessionDTO.SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "面试已完成，不能继续作答");
        }
        List<InterviewQuestionDTO> questions = cachedSession.getQuestions(objectMapper);
        int currentIndex = cachedSession.getCurrentIndex();
        if (currentIndex < 0 || currentIndex >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "所有问题已回答完毕");
        }
        int currentQuestionIndex = questions.get(currentIndex).questionIndex();
        if (questionIndex == null || questionIndex != currentQuestionIndex) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "问题索引不匹配，当前问题索引为: " + currentQuestionIndex
            );
        }
    }

    private void validateSessionCanGenerateReport(InterviewSessionCache.CachedSession cachedSession) {
        if (cachedSession.getStatus() == InterviewSessionDTO.SessionStatus.CREATED
                || cachedSession.getStatus() == InterviewSessionDTO.SessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }
    }

    private List<InterviewQuestionDTO> applyAnswerToQuestions(
            List<InterviewQuestionDTO> questions,
            int currentIndex,
            String answer) {
        List<InterviewQuestionDTO> updatedQuestions = new ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            InterviewQuestionDTO question = questions.get(i);
            updatedQuestions.add(i == currentIndex ? question.withAnswer(answer) : question);
        }
        return updatedQuestions;
    }

    private void updateSessionProgress(String sessionId, int nextIndex) {
        sessionCache.updateCurrentIndex(sessionId, nextIndex);
        sessionCache.updateSessionStatus(sessionId, InterviewSessionDTO.SessionStatus.IN_PROGRESS);
        persistenceService.updateCurrentQuestionIndex(sessionId, nextIndex);
    }

    private InterviewReportDTO buildInterviewReport(
            String sessionId,
            InterviewSessionCache.CachedSession cachedSession,
            InterviewSessionEntity sessionEntity) {
        List<InterviewQuestionDTO> questions = hydrateQuestionsWithAnswers(
                cachedSession.getQuestions(objectMapper),
                persistenceService.findAnswersBySessionId(sessionId)
        );
        sessionCache.updateQuestions(sessionId, questions);
        InterviewReportDTO report = evaluationService.evaluateInterview(
                llmProviderRegistry.getChatClientOrDefault(sessionEntity.getLlmProvider()),
                sessionId,
                cachedSession.getResumeText(),
                questions
        );
        sessionCache.updateSessionStatus(sessionId, InterviewSessionDTO.SessionStatus.EVALUATED);
        persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.COMPLETED, null);
        sessionCache.refreshSessionTTL(sessionId);
        log.info("面试评估报告生成成功: sessionId={}, overallScore={}", sessionId, report.overallScore());
        return report;
    }

    private void validateSessionCanComplete(InterviewSessionCache.CachedSession cachedSession) {
        if (cachedSession.getStatus() == InterviewSessionDTO.SessionStatus.COMPLETED
                || cachedSession.getStatus() == InterviewSessionDTO.SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "面试已完成");
        }
    }

    private String normalizeResumeText(String resumeText) {
        return StringUtils.hasText(resumeText) ? resumeText.strip() : "";
    }

    private String normalizeAnswer(String answer) {
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "答案不能为空");
        }
        return answer.strip();
    }

    private int safeCurrentIndex(Integer currentIndex) {
        return currentIndex == null ? 0 : currentIndex;
    }



}
