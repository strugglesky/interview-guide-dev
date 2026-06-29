package org.example.modules.voiceinterview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.common.evaluation.EvaluationReport;
import org.example.common.evaluation.QaRecord;
import org.example.common.evaluation.UnifiedEvaluationService;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.modules.interview.skill.InterviewSkillService;
import org.example.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import org.example.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import org.example.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.example.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import org.example.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import org.example.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音面试评估服务
 * 复用 UnifiedEvaluationService 的分批评估 + 结构化输出 + 降级兜底
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceInterviewEvaluationService {
    private final UnifiedEvaluationService unifiedEvaluationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final InterviewSkillService skillService;
    private final TransactionTemplate transactionTemplate;

    private static final String USER_SPEECH = "USER_SPEECH";
    private static final String AI_SPEECH = "AI_SPEECH";
    private static final String EMPTY_EVALUATION_FEEDBACK = "本次语音面试暂无可评估的有效问答记录。";
    private static final String EMPTY_EVALUATION_IMPROVEMENT = "请至少完成一轮有效问答后再生成评估。";

    /**
     * 生成语音面试评估（由异步消费者调用）
     * LLM 调用在事务外执行，仅 DB 写入在事务内
     */
    public void generateEvaluation(Long sessionId) {
        validateSessionId(sessionId);
        try {
            VoiceInterviewSessionEntity session = loadSessionOrThrow(sessionId);
            updateEvaluateStatus(sessionId, AsyncTaskStatus.PROCESSING, null);
            List<QaRecord> qaRecords = buildQaRecords(loadMessages(sessionId));
            if (qaRecords.isEmpty()) {
                saveEmptyEvaluationInTransaction(sessionId, session);
                return;
            }
            EvaluationReport report = unifiedEvaluationService.evaluate(
                    resolveChatClient(session),
                    String.valueOf(sessionId),
                    qaRecords,
                    buildResumeContext(session),
                    buildReferenceContext(session)
            );
            saveEvaluationInTransaction(sessionId, session, report);
        } catch (Exception e) {
            log.error("生成语音面试评估失败: sessionId={}", sessionId, e);
            updateEvaluateFailedSafely(sessionId, e.getMessage());
            throw wrapEvaluationException(e);
        }
    }

    /**
     * 获取语音面试评估结果
     */
    public VoiceEvaluationDetailDTO getEvaluation(Long sessionId) {
        validateSessionId(sessionId);
        VoiceInterviewEvaluationEntity entity = evaluationRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                        "语音面试评估结果不存在"
                ));
        List<EvaluationReport.QuestionEvaluation> questions = parseQuestionEvaluations(entity);
        List<EvaluationReport.ReferenceAnswer> references = parseReferenceAnswers(entity);
        return VoiceEvaluationDetailDTO.builder()
                .sessionId(sessionId)
                .totalQuestions(questions.size())
                .overallScore(entity.getOverallScore() != null ? entity.getOverallScore() : 0)
                .overallFeedback(entity.getOverallFeedback())
                .strengths(parseStringList(entity.getStrengthsJson(), "strengthsJson", sessionId))
                .improvements(parseStringList(entity.getImprovementsJson(), "improvementsJson", sessionId))
                .answers(buildAnswerDetails(questions, references))
                .build();
    }

    /**
     * 保存语音面试评估结果（事务内）
     */
    @Transactional
    public void saveEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session,
                                            EvaluationReport report) {
        VoiceInterviewEvaluationEntity entity = loadOrCreateEvaluation(sessionId);
        entity.setSessionId(sessionId);
        entity.setOverallScore(report.overallScore());
        entity.setOverallFeedback(report.overallFeedback());
        entity.setQuestionEvaluationsJson(writeJson(report.questionDetails(), "questionDetails", sessionId));
        entity.setStrengthsJson(writeJson(report.strengths(), "strengths", sessionId));
        entity.setImprovementsJson(writeJson(report.improvements(), "improvements", sessionId));
        entity.setReferenceAnswersJson(writeJson(report.referenceAnswers(), "referenceAnswers", sessionId));
        applySessionSnapshot(entity, session);
        evaluationRepository.save(entity);
        markSessionEvaluated(sessionId, AsyncTaskStatus.COMPLETED, null);
    }

    /**
     * 保存空评估结果（事务内）
     */
    @Transactional
    public void saveEmptyEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session) {
        EvaluationReport report = new EvaluationReport(
                String.valueOf(sessionId),
                0,
                0,
                List.of(),
                List.of(),
                EMPTY_EVALUATION_FEEDBACK,
                List.of(),
                List.of(EMPTY_EVALUATION_IMPROVEMENT),
                List.of()
        );
        saveEvaluationTransactional(sessionId, session, report);
    }

    private VoiceInterviewSessionEntity loadSessionOrThrow(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VOICE_SESSION_NOT_FOUND,
                        "语音面试会话不存在: " + sessionId
                ));
    }

    private List<VoiceInterviewMessageEntity> loadMessages(Long sessionId) {
        return messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionId);
    }

    private List<QaRecord> buildQaRecords(List<VoiceInterviewMessageEntity> messages) {
        List<QaRecord> qaRecords = new ArrayList<>();
        PendingQuestion pendingQuestion = null;
        for (VoiceInterviewMessageEntity message : messages) {
            if (message == null) {
                continue;
            }
            if (AI_SPEECH.equals(message.getMessageType())) {
                pendingQuestion = buildPendingQuestion(message);
            } else if (USER_SPEECH.equals(message.getMessageType()) && pendingQuestion != null) {
                addQaRecordIfAnswered(qaRecords, pendingQuestion, message);
                pendingQuestion = null;
            }
        }
        return qaRecords;
    }

    private PendingQuestion buildPendingQuestion(VoiceInterviewMessageEntity message) {
        String question = normalizeText(message.getAiGeneratedText());
        if (!StringUtils.hasText(question)) {
            return null;
        }
        return new PendingQuestion(question, resolveCategory(message.getPhase()));
    }

    private void addQaRecordIfAnswered(List<QaRecord> qaRecords,
                                       PendingQuestion pendingQuestion,
                                       VoiceInterviewMessageEntity message) {
        if (pendingQuestion == null) {
            return;
        }
        String answer = normalizeText(message.getUserRecognizedText());
        if (!StringUtils.hasText(answer)) {
            return;
        }
        qaRecords.add(new QaRecord(
                qaRecords.size() + 1,
                pendingQuestion.question(),
                pendingQuestion.category(),
                answer
        ));
    }

    private ChatClient resolveChatClient(VoiceInterviewSessionEntity session) {
        return llmProviderRegistry.getPlainChatClient(session.getLlmProvider());
    }

    private String buildReferenceContext(VoiceInterviewSessionEntity session) {
        String skillReference = skillService.buildEvaluationReferenceSectionSafe(session.getSkillId());
        if (!StringUtils.hasText(session.getCustomJdText())) {
            return skillReference;
        }
        String jdSection = "### 岗位/JD补充\n" + session.getCustomJdText().strip();
        return StringUtils.hasText(skillReference) ? skillReference + "\n\n" + jdSection : jdSection;
    }

    private String buildResumeContext(VoiceInterviewSessionEntity session) {
        if (!StringUtils.hasText(session.getCustomJdText())) {
            return "";
        }
        return "岗位/JD信息：\n" + session.getCustomJdText().strip();
    }

    private List<VoiceEvaluationDetailDTO.AnswerDetail> buildAnswerDetails(
            List<EvaluationReport.QuestionEvaluation> questions,
            List<EvaluationReport.ReferenceAnswer> references
    ) {
        Map<Integer, EvaluationReport.ReferenceAnswer> referenceMap = buildReferenceMap(references);
        return questions.stream()
                .map(question -> buildAnswerDetail(question, referenceMap.get(question.questionIndex())))
                .toList();
    }

    private Map<Integer, EvaluationReport.ReferenceAnswer> buildReferenceMap(
            List<EvaluationReport.ReferenceAnswer> references
    ) {
        Map<Integer, EvaluationReport.ReferenceAnswer> referenceMap = new LinkedHashMap<>();
        for (EvaluationReport.ReferenceAnswer reference : references) {
            if (reference != null) {
                referenceMap.put(reference.questionIndex(), reference);
            }
        }
        return referenceMap;
    }

    private VoiceEvaluationDetailDTO.AnswerDetail buildAnswerDetail(
            EvaluationReport.QuestionEvaluation question,
            EvaluationReport.ReferenceAnswer reference
    ) {
        return VoiceEvaluationDetailDTO.AnswerDetail.builder()
                .questionIndex(question.questionIndex())
                .question(question.question())
                .category(question.category())
                .userAnswer(question.userAnswer())
                .score(question.score())
                .feedback(question.feedback())
                .referenceAnswer(reference != null ? reference.referenceAnswer() : "")
                .keyPoints(reference != null ? reference.keyPoints() : List.of())
                .build();
    }

    private List<EvaluationReport.QuestionEvaluation> parseQuestionEvaluations(
            VoiceInterviewEvaluationEntity entity
    ) {
        return parseList(
                entity.getQuestionEvaluationsJson(),
                "questionEvaluationsJson",
                entity.getSessionId(),
                new TypeReference<List<EvaluationReport.QuestionEvaluation>>() {}
        );
    }

    private List<EvaluationReport.ReferenceAnswer> parseReferenceAnswers(
            VoiceInterviewEvaluationEntity entity
    ) {
        return parseList(
                entity.getReferenceAnswersJson(),
                "referenceAnswersJson",
                entity.getSessionId(),
                new TypeReference<List<EvaluationReport.ReferenceAnswer>>() {}
        );
    }

    private List<String> parseStringList(String json, String fieldName, Long sessionId) {
        return parseList(json, fieldName, sessionId, new TypeReference<List<String>>() {});
    }

    private <T> T parseList(String json, String fieldName, Long sessionId, TypeReference<T> typeReference) {
        if (!StringUtils.hasText(json)) {
            return emptyListValue();
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JacksonException e) {
            log.error("反序列化语音评估数据失败: sessionId={}, fieldName={}", sessionId, fieldName, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化语音评估数据失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T emptyListValue() {
        return (T) List.of();
    }

    private String writeJson(Object value, String fieldName, Long sessionId) {
        try {
            return objectMapper.writeValueAsString(value != null ? value : List.of());
        } catch (JacksonException e) {
            log.error("序列化语音评估数据失败: sessionId={}, fieldName={}", sessionId, fieldName, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED, "序列化语音评估数据失败", e);
        }
    }

    private VoiceInterviewEvaluationEntity loadOrCreateEvaluation(Long sessionId) {
        return evaluationRepository.findBySessionId(sessionId)
                .orElseGet(VoiceInterviewEvaluationEntity::new);
    }

    private void applySessionSnapshot(VoiceInterviewEvaluationEntity entity,
                                      VoiceInterviewSessionEntity session) {
        entity.setInterviewerRole(resolveInterviewerRole(session));
        entity.setInterviewDate(resolveInterviewDate(session));
    }

    private void markSessionEvaluated(Long sessionId, AsyncTaskStatus status, String error) {
        VoiceInterviewSessionEntity persistedSession = loadSessionOrThrow(sessionId);
        persistedSession.setEvaluateStatus(status);
        persistedSession.setEvaluateError(error);
        sessionRepository.save(persistedSession);
    }

    private void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        transactionTemplate.executeWithoutResult(ignored -> markSessionEvaluated(sessionId, status, error));
    }

    private void saveEvaluationInTransaction(Long sessionId,
                                             VoiceInterviewSessionEntity session,
                                             EvaluationReport report) {
        transactionTemplate.executeWithoutResult(
                ignored -> saveEvaluationTransactional(sessionId, session, report)
        );
    }

    private void saveEmptyEvaluationInTransaction(Long sessionId, VoiceInterviewSessionEntity session) {
        transactionTemplate.executeWithoutResult(
                ignored -> saveEmptyEvaluationTransactional(sessionId, session)
        );
    }

    private void updateEvaluateFailedSafely(Long sessionId, String errorMessage) {
        try {
            updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, normalizeErrorMessage(errorMessage));
        } catch (Exception e) {
            log.error("更新语音面试评估失败状态失败: sessionId={}", sessionId, e);
        }
    }

    private BusinessException wrapEvaluationException(Exception e) {
        if (e instanceof BusinessException businessException) {
            return businessException;
        }
        return new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED, "语音面试评估失败", e);
    }

    private void validateSessionId(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "语音面试会话ID不合法");
        }
    }

    private String resolveCategory(VoiceInterviewSessionEntity.InterviewPhase phase) {
        if (phase == null) {
            return "未分类";
        }
        return switch (phase) {
            case INTRO -> "自我介绍";
            case TECH -> "技术能力";
            case PROJECT -> "项目经验";
            case HR -> "综合素质";
            case COMPLETED -> "已完成";
        };
    }

    private String resolveInterviewerRole(VoiceInterviewSessionEntity session) {
        if (StringUtils.hasText(session.getSkillId())) {
            return session.getSkillId().strip();
        }
        return StringUtils.hasText(session.getRoleType()) ? session.getRoleType().strip() : "voice-interviewer";
    }

    private LocalDateTime resolveInterviewDate(VoiceInterviewSessionEntity session) {
        if (session.getEndTime() != null) {
            return session.getEndTime();
        }
        return session.getStartTime() != null ? session.getStartTime() : LocalDateTime.now();
    }

    private String normalizeText(String text) {
        return StringUtils.hasText(text) ? text.strip() : "";
    }

    private String normalizeErrorMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "语音面试评估失败";
        }
        String normalized = message.strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private record PendingQuestion(String question, String category) {
    }


}
