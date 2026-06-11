package org.example.modules.interview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.model.AsyncTaskStatus;
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewReportDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.resume.repository.ResumeRepository;
import org.example.modules.interview.repository.InterviewAnswerRepository;
import org.example.modules.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewPersistenceService {
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;

    /**
     * 保存新的面试会话（支持可选简历）
     * @param sessionId 会话ID
     * @param resumeId 简历ID
     * @param totalQuestions 总问题数
     * @param questions 问题列表
     * @param llmProvider LLM供应商
     * @param skillId 技能ID
     * @param difficulty 技能难度
     * @return 新的面试会话实体
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveNewSession(String sessionId, Long resumeId,
                                                 int totalQuestions,
                                                 List<InterviewQuestionDTO> questions,
                                                 String llmProvider,
                                                 String skillId,
                                                 String difficulty){
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setTotalQuestions(totalQuestions);
        session.setLlmProvider(llmProvider);
        session.setSkillId(skillId);
        session.setDifficulty(difficulty);
        session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);

        if (resumeId != null) {
            session.setResume(resumeRepository.getReferenceById(resumeId));
        }

        try {
            session.setQuestionsJson(objectMapper.writeValueAsString(questions));
        } catch (Exception e) {
            log.error("问题列表序列化为 JSON 失败", e);
            throw new RuntimeException("问题序列化失败", e);
        }

        return sessionRepository.save(session);
    }

    /**
     * 更新会话状态
     * @param sessionId 会话ID
     * @param status 状态
     * @param error 错误信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, AsyncTaskStatus status, String error) {
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (status == AsyncTaskStatus.COMPLETED) {
            session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
            session.setCompletedAt(java.time.LocalDateTime.now());
        } else if (status == AsyncTaskStatus.FAILED) {
            session.setEvaluateError(error);
        }

        sessionRepository.save(session);
    }

    /**
     * 更新评估状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        session.setEvaluateStatus(status);

        if (status == AsyncTaskStatus.COMPLETED) {
            session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
        } else if (status == AsyncTaskStatus.FAILED) {
            session.setEvaluateError(error);
        }

        sessionRepository.save(session);
    }

    /**
     * 更新当前问题索引
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        session.setCurrentQuestionIndex(index);

        if (session.getStatus() == InterviewSessionEntity.SessionStatus.CREATED) {
            session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        }

        sessionRepository.save(session);
    }

    /**
     * 保存面试答案
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, int score, String feedback) {
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        InterviewAnswerEntity answer = new InterviewAnswerEntity();
        answer.setSession(session);
        answer.setQuestionIndex(questionIndex);
        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);

        return answerRepository.save(answer);
    }

    /**
     * 保存面试报告
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report){
        InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        session.setOverallScore(report.overallScore());
        session.setOverallFeedback(report.overallFeedback());

        try {
            session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
        } catch (Exception e) {
            log.error("报告数据序列化为 JSON 失败", e);
            throw new RuntimeException("报告序列化失败", e);
        }

        for (InterviewReportDTO.QuestionEvaluation detail : report.questionDetails()) {
            Optional<InterviewAnswerEntity> answerOpt = answerRepository
                    .findBySession_SessionIdAndQuestionIndex(sessionId, detail.questionIndex());

            if (answerOpt.isPresent()) {
                InterviewAnswerEntity answer = answerOpt.get();
                answer.setScore(detail.score());
                answer.setFeedback(detail.feedback());
                answerRepository.save(answer);
            }
        }

        for (InterviewReportDTO.ReferenceAnswer refAnswer : report.referenceAnswers()) {
            Optional<InterviewAnswerEntity> answerOpt = answerRepository
                    .findBySession_SessionIdAndQuestionIndex(sessionId, refAnswer.questionIndex());

            if (answerOpt.isPresent()) {
                InterviewAnswerEntity answer = answerOpt.get();
                answer.setReferenceAnswer(refAnswer.referenceAnswer());
                try {
                    answer.setKeyPointsJson(objectMapper.writeValueAsString(refAnswer.keyPoints()));
                } catch (Exception e) {
                    log.error("关键点序列化为 JSON 失败", e);
                }
                answerRepository.save(answer);
            }
        }

        sessionRepository.save(session);
    }

    /**
     * 根据会话ID获取会话
     */
    public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    /**
     * 获取简历的所有面试记录
     */
    public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    /**
     * 获取所有面试记录（按创建时间倒序）
     */
    public List<InterviewSessionEntity> findAll() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 删除简历的所有面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionsByResumeId(Long resumeId){
        List<InterviewSessionEntity> sessions = sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
        sessionRepository.deleteAll(sessions);
    }

    /**
     * 删除单个面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId){
        sessionRepository.findBySessionId(sessionId)
                .ifPresent(sessionRepository::delete);
    }

    /**
     * 查找未完成的面试会话（CREATED或IN_PROGRESS状态）
     */
    public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
        return sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
                resumeId,
                List.of(InterviewSessionEntity.SessionStatus.CREATED,
                        InterviewSessionEntity.SessionStatus.IN_PROGRESS)
        );
    }

    /**
     * 根据会话ID查找所有答案
     */
    public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
    }

    /**
     * 获取历史提问列表（结构化，按分类压缩用）。
     * 有 resumeId 时精确匹配 resumeId + skillId；无 resumeId 时按 skillId 查全部（通用模式兜底）。
     */
    public List<InterviewQuestionDTO> getHistoryQuestions(String skillId,Long resumeId) {
        List<InterviewSessionEntity> sessions;

        if (resumeId != null) {
            sessions = sessionRepository.findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(resumeId, skillId);
        } else {
            sessions = sessionRepository.findTop10BySkillIdOrderByCreatedAtDesc(skillId);
        }

        log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size());

        List<InterviewQuestionDTO> historyQuestions = new ArrayList<>();

        for (InterviewSessionEntity session : sessions) {
            if (session.getQuestionsJson() != null) {
                try {
                    List<InterviewQuestionDTO> questions = objectMapper.readValue(
                            session.getQuestionsJson(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, InterviewQuestionDTO.class)
                    );
                    historyQuestions.addAll(questions);
                } catch (Exception e) {
                    log.warn("会话 {} 的问题列表反序列化失败", session.getSessionId(), e);
                }
            }
        }

        return historyQuestions;
    }


}
