package org.example.evaluation;

import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.config.InterviewEvaluationProperties;
import org.example.common.evaluation.EvaluationReport;
import org.example.common.evaluation.QaRecord;
import org.example.common.evaluation.UnifiedEvaluationService;
import org.example.common.model.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("统一面试评估服务测试")
class UnifiedEvaluationServiceTest {

    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    @Mock
    private ResourceLoader resourceLoader;

    @Mock
    private ChatClient chatClient;

    @Nested
    @DisplayName("评估流程")
    class EvaluateFlow {

        @Test
        @DisplayName("问答为空时应返回空报告并且不调用AI评估")
        void shouldReturnEmptyReportWhenQaRecordsAreEmpty() throws Exception {
            UnifiedEvaluationService service = buildService(2);

            EvaluationReport result = service.evaluate(chatClient, "  ", List.of(), "简历内容");

            assertThat(result.sessionId()).isEqualTo("未知会话");
            assertThat(result.totalQuestions()).isZero();
            assertThat(result.overallScore()).isZero();
            assertThat(result.overallFeedback()).isEqualTo("本次面试暂无可评估的问答记录。");
            assertThat(result.improvements()).containsExactly("请至少提供一道有效作答后再进行评估。");
            verifyNoInteractions(structuredOutputInvoker);
        }

        @Test
        @DisplayName("应按批次评估并汇总最终报告")
        void shouldEvaluateByBatchAndMergeFinalReport() throws Exception {
            UnifiedEvaluationService service = buildService(1);
            List<QaRecord> records = List.of(
                    buildQaRecord(1, "解释JVM内存模型", "Java", "分为堆、栈、方法区，并说明线程共享与私有区域"),
                    buildQaRecord(0, "什么是索引覆盖", "MySQL", "避免回表，提高查询效率")
            );

            Object firstBatch = buildBatchReport(
                    92,
                    "第一批表现优秀",
                    List.of("数据库基础扎实"),
                    List.of("补充更多索引失效场景"),
                    List.of(buildQuestionEval(
                            0,
                            92,
                            "回答准确，能说明覆盖索引的核心价值",
                            "覆盖索引通过索引直接返回所需字段，避免回表",
                            List.of("减少回表", "提升查询效率")
                    ))
            );
            Object secondBatch = buildBatchReport(
                    84,
                    "第二批表现良好",
                    List.of("Java基础较好"),
                    List.of("补充垃圾回收器细节"),
                    List.of(buildQuestionEval(
                            1,
                            84,
                            "回答较完整，但还可以展开GC和对象生命周期",
                            "JVM内存模型包含堆、虚拟机栈、本地方法栈、方法区、程序计数器",
                            List.of("线程共享区域", "线程私有区域")
                    ))
            );
            Object summary = buildSummary(
                    "整体表现良好，数据库和Java基础较扎实。",
                    List.of("数据库基础扎实", "Java基础较好"),
                    List.of("补充更多索引失效场景", "补充垃圾回收器细节")
            );
            mockStructuredOutputs(firstBatch, secondBatch, summary);

            EvaluationReport result = service.evaluate(
                    chatClient,
                    "session-1",
                    records,
                    "五年Java后端开发经验",
                    "参考答案上下文"
            );

            assertThat(result.sessionId()).isEqualTo("session-1");
            assertThat(result.totalQuestions()).isEqualTo(2);
            assertThat(result.overallScore()).isEqualTo(88);
            assertThat(result.overallFeedback()).isEqualTo("整体表现良好，数据库和Java基础较扎实。");
            assertThat(result.strengths()).containsExactly("数据库基础扎实", "Java基础较好");
            assertThat(result.improvements()).containsExactly("补充更多索引失效场景", "补充垃圾回收器细节");
            assertThat(result.categoryScores())
                    .extracting(EvaluationReport.CategoryScore::category, EvaluationReport.CategoryScore::score)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("MySQL", 92),
                            org.assertj.core.groups.Tuple.tuple("Java", 84)
                    );
            assertThat(result.questionDetails())
                    .extracting(EvaluationReport.QuestionEvaluation::questionIndex,
                            EvaluationReport.QuestionEvaluation::score)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(0, 92),
                            org.assertj.core.groups.Tuple.tuple(1, 84)
                    );
            assertThat(result.referenceAnswers())
                    .extracting(EvaluationReport.ReferenceAnswer::referenceAnswer)
                    .containsExactly(
                            "覆盖索引通过索引直接返回所需字段，避免回表",
                            "JVM内存模型包含堆、虚拟机栈、本地方法栈、方法区、程序计数器"
                    );
        }

        @Test
        @DisplayName("批次评估失败时应使用兜底评分继续生成报告")
        void shouldUseFallbackBatchResultWhenBatchEvaluationFails() throws Exception {
            UnifiedEvaluationService service = buildService(2);
            List<QaRecord> records = List.of(
                    buildQaRecord(0, "请解释分布式锁", "Redis", "不知道")
            );
            Object summary = buildSummary(
                    "整体表现偏弱，关键知识点掌握还不够稳定。",
                    List.of("回答中体现出一定的相关技术知识。"),
                    List.of("建议补充更具体的实现细节、边界条件和取舍分析。")
            );
            mockStructuredOutputs(new RuntimeException("batch failed"), summary);

            EvaluationReport result = service.evaluate(
                    chatClient,
                    "session-2",
                    records,
                    "三年后端经验",
                    "Redis分布式锁参考答案"
            );

            assertThat(result.totalQuestions()).isEqualTo(1);
            assertThat(result.overallScore()).isZero();
            assertThat(result.overallFeedback()).isEqualTo("整体表现偏弱，关键知识点掌握还不够稳定。");
            assertThat(result.questionDetails()).singleElement().satisfies(detail -> {
                assertThat(detail.score()).isZero();
                assertThat(detail.feedback()).isEqualTo("回答未体现有效的技术内容，无法支撑该题评估。");
            });
        }

        @Test
        @DisplayName("总结生成失败时应使用批次信息兜底汇总")
        void shouldUseFallbackSummaryWhenSummaryGenerationFails() throws Exception {
            UnifiedEvaluationService service = buildService(2);
            List<QaRecord> records = List.of(
                    buildQaRecord(0, "Spring事务失效场景有哪些", "Spring",
                            "同类调用、异常被吃掉、非public方法等都会导致事务失效")
            );
            Object batchReport = buildBatchReport(
                    78,
                    "批次评估完成",
                    List.of("Spring基础较好"),
                    List.of("补充传播行为和隔离级别"),
                    List.of(buildQuestionEval(
                            0,
                            78,
                            "回答覆盖了常见失效场景，但缺少隔离级别说明",
                            "事务失效常见于同类调用、异常处理不当、代理失效等场景",
                            List.of("同类调用", "异常回滚规则")
                    ))
            );
            mockStructuredOutputs(batchReport, new RuntimeException("summary failed"));

            EvaluationReport result = service.evaluate(
                    chatClient,
                    "session-3",
                    records,
                    "具备Spring Boot项目经验",
                    null
            );

            assertThat(result.overallScore()).isEqualTo(78);
            assertThat(result.overallFeedback()).isEqualTo("整体表现良好，核心知识基本具备，但仍有进一步展开的空间。");
            assertThat(result.strengths()).containsExactly("Spring基础较好");
            assertThat(result.improvements()).containsExactly("补充传播行为和隔离级别");
        }
    }

    private UnifiedEvaluationService buildService(int batchSize) throws Exception {
        when(resourceLoader.getResource(anyString()))
                .thenReturn(new ByteArrayResource("测试提示词".getBytes(StandardCharsets.UTF_8)));
        InterviewEvaluationProperties properties = new InterviewEvaluationProperties();
        properties.setBatchSize(batchSize);
        properties.setSystemPromptPath("classpath:prompts/test-system.st");
        properties.setUserPromptPath("classpath:prompts/test-user.st");
        properties.setSummarySystemPromptPath("classpath:prompts/test-summary-system.st");
        properties.setSummaryUserPromptPath("classpath:prompts/test-summary-user.st");
        return new UnifiedEvaluationService(structuredOutputInvoker, resourceLoader, properties);
    }

    private void mockStructuredOutputs(Object... responses) {
        AtomicInteger index = new AtomicInteger();
        when(structuredOutputInvoker.invoke(
                any(ChatClient.class),
                anyString(),
                anyString(),
                any(BeanOutputConverter.class),
                any(ErrorCode.class),
                anyString(),
                anyString(),
                any(Logger.class)
        )).thenAnswer(invocation -> {
            Object response = responses[index.getAndIncrement()];
            if (response instanceof Throwable throwable) {
                throw throwable;
            }
            return response;
        });
    }

    private Object buildBatchReport(
            int overallScore,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<Object> questionEvaluations
    ) throws Exception {
        return instantiatePrivateRecord(
                "org.example.common.evaluation.UnifiedEvaluationService$BatchReportDTO",
                new Class<?>[]{int.class, String.class, List.class, List.class, List.class},
                overallScore, overallFeedback, strengths, improvements, questionEvaluations
        );
    }

    private Object buildQuestionEval(
            int questionIndex,
            int score,
            String feedback,
            String referenceAnswer,
            List<String> keyPoints
    ) throws Exception {
        return instantiatePrivateRecord(
                "org.example.common.evaluation.UnifiedEvaluationService$QuestionEvalDTO",
                new Class<?>[]{int.class, int.class, String.class, String.class, List.class},
                questionIndex, score, feedback, referenceAnswer, keyPoints
        );
    }

    private Object buildSummary(
            String overallFeedback,
            List<String> strengths,
            List<String> improvements
    ) throws Exception {
        return instantiatePrivateRecord(
                "org.example.common.evaluation.UnifiedEvaluationService$SummaryDTO",
                new Class<?>[]{String.class, List.class, List.class},
                overallFeedback, strengths, improvements
        );
    }

    private Object instantiatePrivateRecord(
            String className,
            Class<?>[] parameterTypes,
            Object... args
    ) throws Exception {
        Class<?> type = Class.forName(className);
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private QaRecord buildQaRecord(
            int questionIndex,
            String question,
            String category,
            String userAnswer
    ) {
        return new QaRecord(questionIndex, question, category, userAnswer);
    }
}
