package org.example.modules.interview.service;

import org.example.common.evaluation.EvaluationReport;
import org.example.common.evaluation.QaRecord;
import org.example.common.evaluation.UnifiedEvaluationService;
import org.example.common.exception.BusinessException;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewReportDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.skill.InterviewSkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文字面试答案评估服务测试")
class AnswerEvaluationServiceTest {

    @Mock
    private UnifiedEvaluationService unifiedEvaluationService;

    @Mock
    private InterviewPersistenceService persistenceService;

    @Mock
    private InterviewSkillService skillService;

    @Mock
    private ChatClient chatClient;

    @InjectMocks
    private AnswerEvaluationService answerEvaluationService;

    @Nested
    @DisplayName("面试评估")
    class EvaluateInterview {

        @Test
        @DisplayName("应转换问答并保存评估报告")
        void shouldEvaluateInterviewAndSaveReport() {
            InterviewSessionEntity session = buildSession("session-1", "java-backend");
            List<InterviewQuestionDTO> questions = List.of(
                    buildQuestion(1, "解释JVM内存模型", "JAVA", "Java", "从线程共享和私有区域展开",
                            " 分为堆、栈、方法区 "),
                    buildQuestion(0, "什么是覆盖索引", "MYSQL", "MySQL", null,
                            " 避免回表，提高查询性能 ")
            );
            List<InterviewQuestionDTO> historyQuestions = List.of(
                    InterviewQuestionDTO.create(8, "解释MySQL事务隔离级别", "MYSQL", "MySQL",
                            "重点关注隔离性", false, null)
            );
            EvaluationReport evaluationReport = buildEvaluationReport("session-1");
            when(persistenceService.findBySessionId("session-1")).thenReturn(Optional.of(session));
            when(skillService.buildEvaluationReferenceSectionSafe("java-backend"))
                    .thenReturn("技能参考内容");
            when(persistenceService.getHistoryQuestions("java-backend", null)).thenReturn(historyQuestions);
            when(unifiedEvaluationService.evaluate(
                    eq(chatClient),
                    eq("session-1"),
                    any(List.class),
                    eq("五年后端开发经验"),
                    eq("技能参考内容\n\n### 历史题目参考\n1. [MySQL] 解释MySQL事务隔离级别 - 重点关注隔离性")
            )).thenReturn(evaluationReport);

            InterviewReportDTO result = answerEvaluationService.evaluateInterview(
                    chatClient,
                    "session-1",
                    " 五年后端开发经验 ",
                    questions
            );

            ArgumentCaptor<List<QaRecord>> qaCaptor = ArgumentCaptor.forClass(List.class);
            verify(unifiedEvaluationService).evaluate(
                    eq(chatClient),
                    eq("session-1"),
                    qaCaptor.capture(),
                    eq("五年后端开发经验"),
                    eq("技能参考内容\n\n### 历史题目参考\n1. [MySQL] 解释MySQL事务隔离级别 - 重点关注隔离性")
            );
            List<QaRecord> qaRecords = qaCaptor.getValue();
            assertThat(qaRecords).containsExactly(
                    new QaRecord(1, "解释JVM内存模型", "Java", "分为堆、栈、方法区"),
                    new QaRecord(0, "什么是覆盖索引", "MySQL", "避免回表，提高查询性能")
            );
            assertThat(result.sessionId()).isEqualTo("session-1");
            assertThat(result.totalQuestions()).isEqualTo(2);
            assertThat(result.overallScore()).isEqualTo(88);
            assertThat(result.categoryScores())
                    .extracting(InterviewReportDTO.CategoryScore::category,
                            InterviewReportDTO.CategoryScore::score)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("MySQL", 92),
                            org.assertj.core.groups.Tuple.tuple("Java", 84)
                    );
            assertThat(result.questionDetails())
                    .extracting(InterviewReportDTO.QuestionEvaluation::questionIndex,
                            InterviewReportDTO.QuestionEvaluation::feedback)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(0, "回答准确，能够说明覆盖索引的核心价值"),
                            org.assertj.core.groups.Tuple.tuple(1, "回答较完整，但仍可补充GC细节")
                    );
            verify(persistenceService).saveReport("session-1", result);
        }

        @Test
        @DisplayName("无技能参考时应仅使用历史题目作为参考上下文")
        void shouldUseOnlyHistoryReferenceWhenSkillReferenceIsBlank() {
            InterviewSessionEntity session = buildSession("session-2", "redis");
            List<InterviewQuestionDTO> questions = List.of(
                    buildQuestion(0, "什么是缓存击穿", "REDIS", "Redis", null, "热点key失效导致请求直达数据库")
            );
            List<InterviewQuestionDTO> historyQuestions = List.of(
                    InterviewQuestionDTO.create(9, "解释Redis持久化机制", "REDIS", "Redis",
                            "RDB 与 AOF 对比", false, null)
            );
            EvaluationReport evaluationReport = buildSingleQuestionReport("session-2", "Redis");
            when(persistenceService.findBySessionId("session-2")).thenReturn(Optional.of(session));
            when(skillService.buildEvaluationReferenceSectionSafe("redis")).thenReturn("");
            when(persistenceService.getHistoryQuestions("redis", null)).thenReturn(historyQuestions);
            when(unifiedEvaluationService.evaluate(
                    eq(chatClient),
                    eq("session-2"),
                    any(List.class),
                    eq(""),
                    eq("1. [Redis] 解释Redis持久化机制 - RDB 与 AOF 对比")
            )).thenReturn(evaluationReport);

            InterviewReportDTO result = answerEvaluationService.evaluateInterview(
                    chatClient,
                    "session-2",
                    null,
                    questions
            );

            assertThat(result.totalQuestions()).isEqualTo(1);
            verify(unifiedEvaluationService).evaluate(
                    eq(chatClient),
                    eq("session-2"),
                    any(List.class),
                    eq(""),
                    eq("1. [Redis] 解释Redis持久化机制 - RDB 与 AOF 对比")
            );
            verify(persistenceService).saveReport("session-2", result);
        }

        @Test
        @DisplayName("问题分类为空时应回退到type字段")
        void shouldFallbackToTypeWhenCategoryIsBlank() {
            InterviewSessionEntity session = buildSession("session-3", "backend");
            List<InterviewQuestionDTO> questions = List.of(
                    new InterviewQuestionDTO(
                            0,
                            "解释线程池参数",
                            "JAVA_CONCURRENCY",
                            " ",
                            null,
                            "核心线程数、最大线程数、阻塞队列",
                            null,
                            null,
                            false,
                            null
                    )
            );
            EvaluationReport evaluationReport = buildSingleQuestionReport("session-3", "JAVA_CONCURRENCY");
            when(persistenceService.findBySessionId("session-3")).thenReturn(Optional.of(session));
            when(skillService.buildEvaluationReferenceSectionSafe("backend")).thenReturn("");
            when(persistenceService.getHistoryQuestions("backend", null)).thenReturn(List.of());
            when(unifiedEvaluationService.evaluate(
                    eq(chatClient),
                    eq("session-3"),
                    any(List.class),
                    eq("简历"),
                    eq("")
            )).thenReturn(evaluationReport);

            answerEvaluationService.evaluateInterview(chatClient, "session-3", "简历", questions);

            ArgumentCaptor<List<QaRecord>> qaCaptor = ArgumentCaptor.forClass(List.class);
            verify(unifiedEvaluationService).evaluate(
                    eq(chatClient),
                    eq("session-3"),
                    qaCaptor.capture(),
                    eq("简历"),
                    eq("")
            );
            assertThat(qaCaptor.getValue()).containsExactly(
                    new QaRecord(0, "解释线程池参数", "JAVA_CONCURRENCY", "核心线程数、最大线程数、阻塞队列")
            );
        }

        @Test
        @DisplayName("会话不存在时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenSessionNotFound() {
            when(persistenceService.findBySessionId("missing-session")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> answerEvaluationService.evaluateInterview(
                    chatClient,
                    "missing-session",
                    "简历",
                    List.of()
            )).isInstanceOf(BusinessException.class)
                    .hasMessage("面试会话不存在: missing-session");

            verifyNoInteractions(unifiedEvaluationService);
            verifyNoInteractions(skillService);
        }
    }

    private EvaluationReport buildEvaluationReport(String sessionId) {
        return new EvaluationReport(
                sessionId,
                2,
                88,
                List.of(
                        new EvaluationReport.CategoryScore("MySQL", 92, 1),
                        new EvaluationReport.CategoryScore("Java", 84, 1)
                ),
                List.of(
                        new EvaluationReport.QuestionEvaluation(
                                0,
                                "什么是覆盖索引",
                                "MySQL",
                                "避免回表，提高查询性能",
                                92,
                                "回答准确，能够说明覆盖索引的核心价值"
                        ),
                        new EvaluationReport.QuestionEvaluation(
                                1,
                                "解释JVM内存模型",
                                "Java",
                                "分为堆、栈、方法区",
                                84,
                                "回答较完整，但仍可补充GC细节"
                        )
                ),
                "整体表现良好，数据库和Java基础较扎实。",
                List.of("数据库基础扎实", "Java基础较好"),
                List.of("补充更多索引失效场景", "补充垃圾回收器细节"),
                List.of(
                        new EvaluationReport.ReferenceAnswer(
                                0,
                                "什么是覆盖索引",
                                "覆盖索引通过索引直接返回所需字段，避免回表",
                                List.of("减少回表", "提高查询性能")
                        ),
                        new EvaluationReport.ReferenceAnswer(
                                1,
                                "解释JVM内存模型",
                                "JVM内存模型包含堆、虚拟机栈、本地方法栈、方法区、程序计数器",
                                List.of("线程共享区域", "线程私有区域")
                        )
                )
        );
    }

    private EvaluationReport buildSingleQuestionReport(String sessionId, String category) {
        return new EvaluationReport(
                sessionId,
                1,
                80,
                List.of(new EvaluationReport.CategoryScore(category, 80, 1)),
                List.of(new EvaluationReport.QuestionEvaluation(
                        0,
                        "示例问题",
                        category,
                        "示例回答",
                        80,
                        "示例反馈"
                )),
                "整体表现良好。",
                List.of("基础较好"),
                List.of("建议进一步展开细节"),
                List.of(new EvaluationReport.ReferenceAnswer(
                        0,
                        "示例问题",
                        "示例参考答案",
                        List.of("关键点")
                ))
        );
    }

    private InterviewSessionEntity buildSession(String sessionId, String skillId) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setSkillId(skillId);
        return session;
    }

    private InterviewQuestionDTO buildQuestion(
            int questionIndex,
            String question,
            String type,
            String category,
            String topicSummary,
            String userAnswer
    ) {
        return new InterviewQuestionDTO(
                questionIndex,
                question,
                type,
                category,
                topicSummary,
                userAnswer,
                null,
                null,
                false,
                null
        );
    }
}
