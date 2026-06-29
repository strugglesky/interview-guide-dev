package org.example.modules.voiceinterview.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试评估服务测试")
public class VoiceInterviewEvaluationServiceTest {

    @Mock
    private UnifiedEvaluationService unifiedEvaluationService;

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private VoiceInterviewEvaluationRepository evaluationRepository;

    @Mock
    private VoiceInterviewMessageRepository messageRepository;

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    @Mock
    private InterviewSkillService skillService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VoiceInterviewEvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        evaluationService = new VoiceInterviewEvaluationService(
                unifiedEvaluationService,
                llmProviderRegistry,
                evaluationRepository,
                messageRepository,
                sessionRepository,
                objectMapper,
                skillService,
                transactionTemplate
        );
    }

    @Nested
    @DisplayName("生成评估")
    class GenerateEvaluationTests {

        @Test
        @DisplayName("应将语音消息转换为问答记录并保存评估结果")
        void shouldGenerateEvaluationAndSaveReport() {
            stubTransactionTemplate();
            VoiceInterviewSessionEntity session = buildSession();
            EvaluationReport report = buildReport();
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(1L))
                    .thenReturn(List.of(
                            aiMessage(1, "请说明 Redis 缓存击穿怎么处理。"),
                            userMessage(2, "我会用互斥锁和逻辑过期来处理。")
                    ));
            when(llmProviderRegistry.getPlainChatClient("dashscope")).thenReturn(chatClient);
            when(skillService.buildEvaluationReferenceSectionSafe("java-backend"))
                    .thenReturn("### 技能参考\nRedis 缓存高可用。");
            when(unifiedEvaluationService.evaluate(
                    eq(chatClient),
                    eq("1"),
                    any(),
                    eq("岗位/JD信息：\nJava 后端岗位"),
                    eq("### 技能参考\nRedis 缓存高可用。\n\n### 岗位/JD补充\nJava 后端岗位")
            )).thenReturn(report);
            when(evaluationRepository.findBySessionId(1L)).thenReturn(Optional.empty());

            evaluationService.generateEvaluation(1L);

            ArgumentCaptor<List<QaRecord>> qaCaptor = ArgumentCaptor.forClass(List.class);
            verify(unifiedEvaluationService).evaluate(
                    eq(chatClient),
                    eq("1"),
                    qaCaptor.capture(),
                    eq("岗位/JD信息：\nJava 后端岗位"),
                    eq("### 技能参考\nRedis 缓存高可用。\n\n### 岗位/JD补充\nJava 后端岗位")
            );
            assertThat(qaCaptor.getValue()).singleElement().satisfies(record -> {
                assertThat(record.questionIndex()).isEqualTo(1);
                assertThat(record.question()).isEqualTo("请说明 Redis 缓存击穿怎么处理。");
                assertThat(record.category()).isEqualTo("技术能力");
                assertThat(record.userAnswer()).isEqualTo("我会用互斥锁和逻辑过期来处理。");
            });

            ArgumentCaptor<VoiceInterviewEvaluationEntity> entityCaptor =
                    ArgumentCaptor.forClass(VoiceInterviewEvaluationEntity.class);
            verify(evaluationRepository).save(entityCaptor.capture());
            VoiceInterviewEvaluationEntity saved = entityCaptor.getValue();
            assertThat(saved.getSessionId()).isEqualTo(1L);
            assertThat(saved.getOverallScore()).isEqualTo(82);
            assertThat(saved.getOverallFeedback()).isEqualTo("整体表现良好。");
            assertThat(saved.getInterviewerRole()).isEqualTo("java-backend");
            assertThat(saved.getQuestionEvaluationsJson()).contains("Redis 缓存击穿");
            assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
            assertThat(session.getEvaluateError()).isNull();
        }

        @Test
        @DisplayName("没有有效问答时应保存空评估且不调用 LLM")
        void shouldSaveEmptyEvaluationWhenNoQaRecords() {
            stubTransactionTemplate();
            VoiceInterviewSessionEntity session = buildSession();
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(1L))
                    .thenReturn(List.of(aiMessage(1, "请介绍项目。")));
            when(evaluationRepository.findBySessionId(1L)).thenReturn(Optional.empty());

            evaluationService.generateEvaluation(1L);

            verify(llmProviderRegistry, never()).getPlainChatClient(anyString());
            verify(unifiedEvaluationService, never()).evaluate(any(), anyString(), any(), anyString(), anyString());
            ArgumentCaptor<VoiceInterviewEvaluationEntity> entityCaptor =
                    ArgumentCaptor.forClass(VoiceInterviewEvaluationEntity.class);
            verify(evaluationRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getOverallScore()).isZero();
            assertThat(entityCaptor.getValue().getOverallFeedback())
                    .isEqualTo("本次语音面试暂无可评估的有效问答记录。");
            assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
        }

        @Test
        @DisplayName("评估失败时应更新失败状态并抛出业务异常")
        void shouldMarkFailedWhenEvaluationFails() {
            stubTransactionTemplate();
            VoiceInterviewSessionEntity session = buildSession();
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(1L))
                    .thenReturn(List.of(
                            aiMessage(1, "请说明线程池参数。"),
                            userMessage(2, "我会按 CPU 和 IO 比例设置。")
                    ));
            when(llmProviderRegistry.getPlainChatClient("dashscope")).thenReturn(chatClient);
            when(skillService.buildEvaluationReferenceSectionSafe("java-backend")).thenReturn("");
            when(unifiedEvaluationService.evaluate(eq(chatClient), eq("1"), any(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("LLM timeout"));

            assertThatThrownBy(() -> evaluationService.generateEvaluation(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.VOICE_EVALUATION_FAILED.getCode()));

            assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.FAILED);
            assertThat(session.getEvaluateError()).isEqualTo("LLM timeout");
        }
    }

    @Nested
    @DisplayName("查询评估")
    class GetEvaluationTests {

        @Test
        @DisplayName("应反序列化评估结果并组装详情 DTO")
        void shouldGetEvaluationDetail() throws Exception {
            EvaluationReport report = buildReport();
            VoiceInterviewEvaluationEntity entity = new VoiceInterviewEvaluationEntity();
            entity.setSessionId(1L);
            entity.setOverallScore(report.overallScore());
            entity.setOverallFeedback(report.overallFeedback());
            entity.setQuestionEvaluationsJson(objectMapper.writeValueAsString(report.questionDetails()));
            entity.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            entity.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            entity.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            when(evaluationRepository.findBySessionId(1L)).thenReturn(Optional.of(entity));

            VoiceEvaluationDetailDTO result = evaluationService.getEvaluation(1L);

            assertThat(result.getSessionId()).isEqualTo(1L);
            assertThat(result.getTotalQuestions()).isEqualTo(1);
            assertThat(result.getOverallScore()).isEqualTo(82);
            assertThat(result.getOverallFeedback()).isEqualTo("整体表现良好。");
            assertThat(result.getStrengths()).containsExactly("能说明核心策略");
            assertThat(result.getImprovements()).containsExactly("补充更多边界条件");
            assertThat(result.getAnswers()).singleElement().satisfies(answer -> {
                assertThat(answer.getQuestionIndex()).isEqualTo(1);
                assertThat(answer.getQuestion()).isEqualTo("请说明 Redis 缓存击穿怎么处理。");
                assertThat(answer.getCategory()).isEqualTo("技术能力");
                assertThat(answer.getUserAnswer()).isEqualTo("我会用互斥锁和逻辑过期来处理。");
                assertThat(answer.getScore()).isEqualTo(82);
                assertThat(answer.getFeedback()).isEqualTo("回答覆盖了主要手段。");
                assertThat(answer.getReferenceAnswer()).isEqualTo("可使用互斥锁、逻辑过期和热点预热。");
                assertThat(answer.getKeyPoints()).containsExactly("互斥锁", "逻辑过期");
            });
        }

        @Test
        @DisplayName("评估结果不存在时应抛出业务异常")
        void shouldThrowWhenEvaluationMissing() {
            when(evaluationRepository.findBySessionId(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> evaluationService.getEvaluation(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.VOICE_EVALUATION_NOT_FOUND.getCode()));
        }
    }

    private VoiceInterviewSessionEntity buildSession() {
        VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
        session.setId(1L);
        session.setRoleType("backend");
        session.setSkillId("java-backend");
        session.setLlmProvider("dashscope");
        session.setCustomJdText("Java 后端岗位");
        session.setStartTime(LocalDateTime.of(2026, 6, 27, 10, 0));
        return session;
    }

    private void stubTransactionTemplate() {
        doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private EvaluationReport buildReport() {
        return new EvaluationReport(
                "1",
                1,
                82,
                List.of(new EvaluationReport.CategoryScore("技术能力", 82, 1)),
                List.of(new EvaluationReport.QuestionEvaluation(
                        1,
                        "请说明 Redis 缓存击穿怎么处理。",
                        "技术能力",
                        "我会用互斥锁和逻辑过期来处理。",
                        82,
                        "回答覆盖了主要手段。"
                )),
                "整体表现良好。",
                List.of("能说明核心策略"),
                List.of("补充更多边界条件"),
                List.of(new EvaluationReport.ReferenceAnswer(
                        1,
                        "请说明 Redis 缓存击穿怎么处理。",
                        "可使用互斥锁、逻辑过期和热点预热。",
                        List.of("互斥锁", "逻辑过期")
                ))
        );
    }

    private VoiceInterviewMessageEntity aiMessage(int sequenceNum, String text) {
        VoiceInterviewMessageEntity message = new VoiceInterviewMessageEntity();
        message.setSessionId(1L);
        message.setMessageType("AI_SPEECH");
        message.setPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);
        message.setAiGeneratedText(text);
        message.setSequenceNum(sequenceNum);
        return message;
    }

    private VoiceInterviewMessageEntity userMessage(int sequenceNum, String text) {
        VoiceInterviewMessageEntity message = new VoiceInterviewMessageEntity();
        message.setSessionId(1L);
        message.setMessageType("USER_SPEECH");
        message.setPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);
        message.setUserRecognizedText(text);
        message.setSequenceNum(sequenceNum);
        return message;
    }
}
