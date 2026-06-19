package org.example.modules.interview.service;

import lombok.RequiredArgsConstructor;
import org.example.common.evaluation.EvaluationReport;
import org.example.common.evaluation.QaRecord;
import org.example.common.evaluation.UnifiedEvaluationService;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewReportDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.skill.InterviewSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文字面试答案评估服务
 * 职责：DTO 适配器，将 InterviewQuestionDTO 转为通用 QaRecord，调用 UnifiedEvaluationService
 */
@Service
@RequiredArgsConstructor
public class AnswerEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSkillService skillService;

    /**
     * 评估完整面试并生成报告
     */
    public InterviewReportDTO evaluateInterview(ChatClient chatClient, String sessionId, String resumeText,
                                                List<InterviewQuestionDTO> questions) {
        List<InterviewQuestionDTO> safeQuestions = questions == null ? List.of() : questions;
        log.info("开始评估面试: sessionId={}, questionCount={}", sessionId, safeQuestions.size());
        InterviewSessionEntity session = loadSession(sessionId);
        List<QaRecord> qaRecords = buildQaRecords(safeQuestions);
        String referenceContext = buildReferenceContext(session, safeQuestions);
        EvaluationReport evaluationReport = unifiedEvaluationService.evaluate(
                chatClient,
                sessionId,
                qaRecords,
                normalizeResumeText(resumeText),
                referenceContext
        );
        InterviewReportDTO report = toInterviewReport(sessionId, evaluationReport);
        persistenceService.saveReport(sessionId, report);
        log.info("面试评估完成: sessionId={}, overallScore={}", sessionId, report.overallScore());
        return report;
    }

    private InterviewSessionEntity loadSession(String sessionId) {
        return persistenceService.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
                        "面试会话不存在: " + sessionId
                ));
    }

    private List<QaRecord> buildQaRecords(List<InterviewQuestionDTO> questions) {
        List<QaRecord> qaRecords = new ArrayList<>();
        for (InterviewQuestionDTO question : questions) {
            if (question == null) {
                continue;
            }
            qaRecords.add(new QaRecord(
                    question.questionIndex(),
                    question.question(),
                    resolveCategory(question),
                    normalizeAnswer(question.userAnswer())
            ));
        }
        return qaRecords;
    }

    private String buildReferenceContext(InterviewSessionEntity session, List<InterviewQuestionDTO> questions) {
        String skillReference = skillService.buildEvaluationReferenceSectionSafe(session.getSkillId());
        String historyReference = buildHistoryQuestionContext(session, questions);
        if (!StringUtils.hasText(skillReference)) {
            return historyReference;
        }
        if (!StringUtils.hasText(historyReference)) {
            return skillReference;
        }
        return skillReference + "\n\n### 历史题目参考\n" + historyReference;
    }

    private String buildHistoryQuestionContext(InterviewSessionEntity session,
                                               List<InterviewQuestionDTO> questions) {
        if (!StringUtils.hasText(session.getSkillId())) {
            return "";
        }
        List<InterviewQuestionDTO> historyQuestions = persistenceService.getHistoryQuestions(
                session.getSkillId(),
                session.getResumeId()
        );
        if (historyQuestions == null || historyQuestions.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (InterviewQuestionDTO historyQuestion : historyQuestions) {
            if (historyQuestion == null || !StringUtils.hasText(historyQuestion.question())) {
                continue;
            }
            if (isCurrentQuestion(historyQuestion, questions)) {
                continue;
            }
            builder.append(index++)
                    .append(". [")
                    .append(resolveCategory(historyQuestion))
                    .append("] ")
                    .append(historyQuestion.question().strip());
            if (StringUtils.hasText(historyQuestion.topicSummary())) {
                builder.append(" - ").append(historyQuestion.topicSummary().strip());
            }
            builder.append('\n');
            if (index > 10) {
                break;
            }
        }
        return builder.toString().strip();
    }

    private boolean isCurrentQuestion(InterviewQuestionDTO historyQuestion,
                                      List<InterviewQuestionDTO> currentQuestions) {
        for (InterviewQuestionDTO currentQuestion : currentQuestions) {
            if (currentQuestion == null || !StringUtils.hasText(currentQuestion.question())) {
                continue;
            }
            if (historyQuestion.questionIndex() == currentQuestion.questionIndex()
                    && historyQuestion.question().equals(currentQuestion.question())) {
                return true;
            }
        }
        return false;
    }

    private InterviewReportDTO toInterviewReport(String sessionId, EvaluationReport evaluationReport) {
        return new InterviewReportDTO(
                sessionId,
                evaluationReport.totalQuestions(),
                evaluationReport.overallScore(),
                mapCategoryScores(evaluationReport),
                mapQuestionDetails(evaluationReport),
                evaluationReport.overallFeedback(),
                evaluationReport.strengths(),
                evaluationReport.improvements(),
                mapReferenceAnswers(evaluationReport)
        );
    }

    private List<InterviewReportDTO.CategoryScore> mapCategoryScores(EvaluationReport evaluationReport) {
        return evaluationReport.categoryScores().stream()
                .map(categoryScore -> new InterviewReportDTO.CategoryScore(
                        categoryScore.category(),
                        categoryScore.score(),
                        categoryScore.questionCount()
                ))
                .toList();
    }

    private List<InterviewReportDTO.QuestionEvaluation> mapQuestionDetails(
            EvaluationReport evaluationReport
    ) {
        return evaluationReport.questionDetails().stream()
                .map(question -> new InterviewReportDTO.QuestionEvaluation(
                        question.questionIndex(),
                        question.question(),
                        question.category(),
                        question.userAnswer(),
                        question.score(),
                        question.feedback()
                ))
                .toList();
    }

    private List<InterviewReportDTO.ReferenceAnswer> mapReferenceAnswers(
            EvaluationReport evaluationReport
    ) {
        return evaluationReport.referenceAnswers().stream()
                .map(answer -> new InterviewReportDTO.ReferenceAnswer(
                        answer.questionIndex(),
                        answer.question(),
                        answer.referenceAnswer(),
                        answer.keyPoints()
                ))
                .toList();
    }

    private String resolveCategory(InterviewQuestionDTO question) {
        if (StringUtils.hasText(question.category())) {
            return question.category().strip();
        }
        if (StringUtils.hasText(question.type())) {
            return question.type().strip();
        }
        return "未分类";
    }

    private String normalizeResumeText(String resumeText) {
        return StringUtils.hasText(resumeText) ? resumeText.strip() : "";
    }

    private String normalizeAnswer(String answer) {
        return StringUtils.hasText(answer) ? answer.strip() : null;
    }
}
