package org.example.modules.interview.listener;

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
import org.example.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试评估消费者测试")
class EvaluateStreamConsumerTest {

    @Mock
    private RedisService redisService;

    @Mock
    private InterviewSessionRepository sessionRepository;

    @Mock
    private AnswerEvaluationService evaluationService;

    @Mock
    private InterviewPersistenceService persistenceService;

    @Mock
    private org.example.common.ai.LlmProviderRegistry llmProviderRegistry;

    @Mock
    private ChatClient chatClient;

    private EvaluateStreamConsumer evaluateStreamConsumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        evaluateStreamConsumer = new EvaluateStreamConsumer(
                redisService,
                sessionRepository,
                evaluationService,
                persistenceService,
                objectMapper,
                llmProviderRegistry
        );
    }

    @Nested
    @DisplayName("消息解析")
    class ParsePayloadTest {

        @Test
        @DisplayName("字段完整时应成功解析消息")
        void shouldParsePayloadSuccessfullyWhenFieldsAreValid() {
            Map<String, String> data = Map.of(
                    AsyncTaskStreamConstants.FIELD_SESSION_ID, " session-001 "
            );

            EvaluateStreamConsumer.EvaluatePayload payload = evaluateStreamConsumer.parsePayload(
                    new StreamMessageId(1L, 1L),
                    data
            );

            assertThat(payload.sessionId()).isEqualTo("session-001");
        }

        @Test
        @DisplayName("字段缺失时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenSessionIdMissing() {
            assertThatThrownBy(() -> evaluateStreamConsumer.parsePayload(
                    new StreamMessageId(1L, 2L),
                    Map.of()
            )).isInstanceOf(BusinessException.class)
                    .satisfies(exception -> {
                        BusinessException businessException = (BusinessException) exception;
                        assertThat(businessException.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
                    });
        }

        @Test
        @DisplayName("sessionId 为空白时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenSessionIdBlank() {
            assertThatThrownBy(() -> evaluateStreamConsumer.parsePayload(
                    new StreamMessageId(1L, 3L),
                    Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, "   ")
            )).isInstanceOf(BusinessException.class)
                    .satisfies(exception -> {
                        BusinessException businessException = (BusinessException) exception;
                        assertThat(businessException.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
                    });
        }
    }

    @Nested
    @DisplayName("业务处理")
    class ProcessBusinessTest {

        @Test
        @DisplayName("会话存在时应合并答案后执行评估")
        void shouldEvaluateInterviewWhenSessionExists() throws Exception {
            InterviewSessionEntity session = buildSession("session-001", "dashscope", buildQuestionsJson());
            attachResume(session, 1L, buildResume("resume text"));
            InterviewAnswerEntity answer = buildAnswer(1, "我的答案", 90, "回答完整");
            when(persistenceService.findBySessionId("session-001")).thenReturn(Optional.of(session));
            when(persistenceService.findAnswersBySessionId("session-001")).thenReturn(List.of(answer));
            when(llmProviderRegistry.getChatClientOrDefault("dashscope")).thenReturn(chatClient);

            evaluateStreamConsumer.processBusiness(new EvaluateStreamConsumer.EvaluatePayload("session-001"));

            ArgumentCaptor<List<InterviewQuestionDTO>> questionsCaptor = ArgumentCaptor.forClass(List.class);
            verify(evaluationService).evaluateInterview(
                    eq(chatClient),
                    eq("session-001"),
                    eq("resume text"),
                    questionsCaptor.capture()
            );
            List<InterviewQuestionDTO> evaluatedQuestions = questionsCaptor.getValue();
            assertThat(evaluatedQuestions).hasSize(2);
            assertThat(evaluatedQuestions.get(0).userAnswer()).isEqualTo("我的答案");
            assertThat(evaluatedQuestions.get(0).score()).isEqualTo(90);
            assertThat(evaluatedQuestions.get(0).feedback()).isEqualTo("回答完整");
            assertThat(evaluatedQuestions.get(1).userAnswer()).isNull();
        }

        @Test
        @DisplayName("会话不存在时应跳过评估")
        void shouldSkipEvaluateWhenSessionMissing() {
            when(persistenceService.findBySessionId("missing-session")).thenReturn(Optional.empty());

            evaluateStreamConsumer.processBusiness(new EvaluateStreamConsumer.EvaluatePayload("missing-session"));

            verify(persistenceService, never()).findAnswersBySessionId(any());
            verify(llmProviderRegistry, never()).getChatClientOrDefault(any());
            verify(evaluationService, never()).evaluateInterview(any(), any(), any(), anyList());
        }

        @Test
        @DisplayName("无简历时应使用空字符串作为简历文本")
        void shouldUseEmptyResumeTextWhenResumeMissing() throws Exception {
            InterviewSessionEntity session = buildSession("session-002", "dashscope", buildQuestionsJson());
            when(persistenceService.findBySessionId("session-002")).thenReturn(Optional.of(session));
            when(persistenceService.findAnswersBySessionId("session-002")).thenReturn(List.of());
            when(llmProviderRegistry.getChatClientOrDefault("dashscope")).thenReturn(chatClient);

            evaluateStreamConsumer.processBusiness(new EvaluateStreamConsumer.EvaluatePayload("session-002"));

            verify(evaluationService).evaluateInterview(
                    eq(chatClient),
                    eq("session-002"),
                    eq(""),
                    anyList()
            );
        }
    }

    @Nested
    @DisplayName("状态更新")
    class StatusUpdateTest {

        @Test
        @DisplayName("markProcessing 应更新为处理中")
        void shouldMarkSessionProcessing() {
            InterviewSessionEntity session = buildSession("session-003", "dashscope", "[]");
            session.setEvaluateStatus(AsyncTaskStatus.FAILED);
            session.setEvaluateError("old error");
            when(sessionRepository.findBySessionId("session-003")).thenReturn(Optional.of(session));

            evaluateStreamConsumer.markProcessing(new EvaluateStreamConsumer.EvaluatePayload("session-003"));

            ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
            verify(sessionRepository).save(captor.capture());
            assertThat(captor.getValue().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PROCESSING);
            assertThat(captor.getValue().getEvaluateError()).isNull();
        }

        @Test
        @DisplayName("markCompleted 应更新为已完成")
        void shouldMarkSessionCompleted() {
            InterviewSessionEntity session = buildSession("session-004", "dashscope", "[]");
            session.setEvaluateStatus(AsyncTaskStatus.PROCESSING);
            session.setEvaluateError("old error");
            when(sessionRepository.findBySessionId("session-004")).thenReturn(Optional.of(session));

            evaluateStreamConsumer.markCompleted(new EvaluateStreamConsumer.EvaluatePayload("session-004"));

            ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
            verify(sessionRepository).save(captor.capture());
            assertThat(captor.getValue().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
            assertThat(captor.getValue().getEvaluateError()).isNull();
        }

        @Test
        @DisplayName("markFailed 应保存失败状态并截断错误")
        void shouldMarkSessionFailedAndTruncateError() {
            InterviewSessionEntity session = buildSession("session-005", "dashscope", "[]");
            String longError = "x".repeat(600);
            when(sessionRepository.findBySessionId("session-005")).thenReturn(Optional.of(session));

            evaluateStreamConsumer.markFailed(
                    new EvaluateStreamConsumer.EvaluatePayload("session-005"),
                    longError
            );

            ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
            verify(sessionRepository).save(captor.capture());
            assertThat(captor.getValue().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.FAILED);
            assertThat(captor.getValue().getEvaluateError()).hasSize(500);
        }
    }

    @Nested
    @DisplayName("重试处理")
    class RetryMessageTest {

        @Test
        @DisplayName("重试成功时应重新投递并重置状态")
        void shouldRetryMessageAndResetStatusWhenRetrySucceeds() {
            InterviewSessionEntity session = buildSession("session-006", "dashscope", "[]");
            session.setEvaluateStatus(AsyncTaskStatus.FAILED);
            session.setEvaluateError("old error");
            when(redisService.addStreamMessage(
                    eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY),
                    any()
            )).thenReturn(new StreamMessageId(2L, 3L));
            when(sessionRepository.findBySessionId("session-006")).thenReturn(Optional.of(session));

            evaluateStreamConsumer.retryMessage(new EvaluateStreamConsumer.EvaluatePayload("session-006"), 2);

            ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisService).addStreamMessage(
                    eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY),
                    messageCaptor.capture()
            );
            assertThat(messageCaptor.getValue())
                    .containsEntry(AsyncTaskStreamConstants.FIELD_SESSION_ID, "session-006")
                    .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "2");
            assertThat(messageCaptor.getValue().get("taskType")).isEqualTo("评估");
            assertThat(messageCaptor.getValue().get("sentAt")).isInstanceOf(String.class);

            ArgumentCaptor<InterviewSessionEntity> entityCaptor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
            verify(sessionRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
            assertThat(entityCaptor.getValue().getEvaluateError()).isNull();
        }

        @Test
        @DisplayName("重试失败时应回写失败状态")
        void shouldMarkFailedWhenRetryThrowsException() {
            InterviewSessionEntity session = buildSession("session-007", "dashscope", "[]");
            when(redisService.addStreamMessage(
                    eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY),
                    any()
            )).thenThrow(new RuntimeException("redis unavailable"));
            when(sessionRepository.findBySessionId("session-007")).thenReturn(Optional.of(session));

            evaluateStreamConsumer.retryMessage(new EvaluateStreamConsumer.EvaluatePayload("session-007"), 3);

            ArgumentCaptor<InterviewSessionEntity> entityCaptor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
            verify(sessionRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.FAILED);
            assertThat(entityCaptor.getValue().getEvaluateError()).isEqualTo("redis unavailable");
        }
    }

    private InterviewSessionEntity buildSession(String sessionId, String llmProvider, String questionsJson) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setLlmProvider(llmProvider);
        session.setQuestionsJson(questionsJson);
        return session;
    }

    private ResumeEntity buildResume(String resumeText) {
        ResumeEntity resume = new ResumeEntity();
        resume.setResumeText(resumeText);
        return resume;
    }

    private void attachResume(InterviewSessionEntity session, Long resumeId, ResumeEntity resume) throws Exception {
        session.setResume(resume);
        Field resumeIdField = InterviewSessionEntity.class.getDeclaredField("resumeId");
        resumeIdField.setAccessible(true);
        resumeIdField.set(session, resumeId);
    }

    private InterviewAnswerEntity buildAnswer(int questionIndex, String userAnswer, Integer score, String feedback) {
        InterviewAnswerEntity answer = new InterviewAnswerEntity();
        answer.setQuestionIndex(questionIndex);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);
        return answer;
    }

    private String buildQuestionsJson() throws Exception {
        List<InterviewQuestionDTO> questions = List.of(
                new InterviewQuestionDTO(1, "什么是 Agent？", "CORE", "AGENT", "Agent 基础", null, null, null, false, null),
                new InterviewQuestionDTO(2, "如何设计终止条件？", "CORE", "AGENT", "终止条件", null, null, null, true, 1)
        );
        return objectMapper.writeValueAsString(questions);
    }
}
