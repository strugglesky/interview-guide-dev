package org.example.modules.voiceinterview.listener;

import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.example.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import org.example.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试评估消费者测试")
class VoiceEvaluateStreamConsumerTest {

    @Mock
    private RedisService redisService;

    @Mock
    private VoiceInterviewService voiceInterviewService;

    @Mock
    private VoiceInterviewEvaluationService evaluationService;

    private VoiceEvaluateStreamConsumer voiceEvaluateStreamConsumer;

    @BeforeEach
    void setUp() {
        voiceEvaluateStreamConsumer = new VoiceEvaluateStreamConsumer(
                redisService,
                voiceInterviewService,
                evaluationService
        );
    }

    @Nested
    @DisplayName("消息解析")
    class ParsePayloadTests {

        @Test
        @DisplayName("字段完整时应成功解析消息")
        void shouldParsePayloadSuccessfullyWhenFieldsAreValid() {
            Map<String, String> data = Map.of(
                    AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, " 123 "
            );

            VoiceEvaluateStreamConsumer.VoiceEvaluatePayload payload = voiceEvaluateStreamConsumer.parsePayload(
                    new StreamMessageId(1L, 1L),
                    data
            );

            assertThat(payload.sessionId()).isEqualTo("123");
        }

        @Test
        @DisplayName("字段缺失时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenSessionIdMissing() {
            assertThatThrownBy(() -> voiceEvaluateStreamConsumer.parsePayload(
                    new StreamMessageId(1L, 2L),
                    Map.of()
            )).isInstanceOf(BusinessException.class)
                    .satisfies(exception -> {
                        BusinessException businessException = (BusinessException) exception;
                        assertThat(businessException.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
                    });
        }

        @Test
        @DisplayName("voiceSessionId 非数字时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenSessionIdNotNumeric() {
            assertThatThrownBy(() -> voiceEvaluateStreamConsumer.parsePayload(
                    new StreamMessageId(1L, 3L),
                    Map.of(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, "abc")
            )).isInstanceOf(BusinessException.class)
                    .satisfies(exception -> {
                        BusinessException businessException = (BusinessException) exception;
                        assertThat(businessException.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
                    });
        }
    }

    @Nested
    @DisplayName("业务处理")
    class ProcessBusinessTests {

        @Test
        @DisplayName("会话存在时应执行语音评估")
        void shouldGenerateEvaluationWhenSessionExists() {
            VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
            session.setId(123L);
            when(voiceInterviewService.getSession(123L)).thenReturn(session);

            voiceEvaluateStreamConsumer.processBusiness(
                    new VoiceEvaluateStreamConsumer.VoiceEvaluatePayload("123")
            );

            verify(voiceInterviewService).getSession(123L);
            verify(evaluationService).generateEvaluation(123L);
        }

        @Test
        @DisplayName("会话不存在时应跳过评估")
        void shouldSkipEvaluationWhenSessionMissing() {
            when(voiceInterviewService.getSession(456L)).thenReturn(null);

            voiceEvaluateStreamConsumer.processBusiness(
                    new VoiceEvaluateStreamConsumer.VoiceEvaluatePayload("456")
            );

            verify(voiceInterviewService).getSession(456L);
            verify(evaluationService, never()).generateEvaluation(any());
        }
    }

    @Nested
    @DisplayName("状态更新")
    class StatusUpdateTests {

        @Test
        @DisplayName("markProcessing 应更新为处理中")
        void shouldMarkProcessing() {
            voiceEvaluateStreamConsumer.markProcessing(
                    new VoiceEvaluateStreamConsumer.VoiceEvaluatePayload("11")
            );

            verify(voiceInterviewService).updateEvaluateStatus(11L, AsyncTaskStatus.PROCESSING, null);
        }

        @Test
        @DisplayName("markCompleted 应更新为已完成")
        void shouldMarkCompleted() {
            voiceEvaluateStreamConsumer.markCompleted(
                    new VoiceEvaluateStreamConsumer.VoiceEvaluatePayload("12")
            );

            verify(voiceInterviewService).updateEvaluateStatus(12L, AsyncTaskStatus.COMPLETED, null);
        }

        @Test
        @DisplayName("markFailed 应保存失败状态并截断错误")
        void shouldMarkFailedAndTruncateError() {
            String longError = "x".repeat(600);

            voiceEvaluateStreamConsumer.markFailed(
                    new VoiceEvaluateStreamConsumer.VoiceEvaluatePayload("13"),
                    longError
            );

            ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
            verify(voiceInterviewService).updateEvaluateStatus(
                    eq(13L),
                    eq(AsyncTaskStatus.FAILED),
                    errorCaptor.capture()
            );
            assertThat(errorCaptor.getValue()).hasSize(500);
        }
    }

    @Nested
    @DisplayName("重试处理")
    class RetryMessageTests {

        @Test
        @DisplayName("重试成功时应重新投递并重置状态")
        void shouldRetryMessageAndResetStatusWhenRetrySucceeds() {
            when(redisService.addStreamMessage(
                    eq(AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY),
                    any()
            )).thenReturn(new StreamMessageId(2L, 3L));

            voiceEvaluateStreamConsumer.retryMessage(
                    new VoiceEvaluateStreamConsumer.VoiceEvaluatePayload("14"),
                    2
            );

            ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisService).addStreamMessage(
                    eq(AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY),
                    messageCaptor.capture()
            );
            assertThat(messageCaptor.getValue())
                    .containsEntry(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, "14")
                    .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "2");
            assertThat(messageCaptor.getValue().get("taskType")).isEqualTo("语音面试评估");
            assertThat(messageCaptor.getValue().get("sentAt")).isInstanceOf(String.class);
            verify(voiceInterviewService).updateEvaluateStatus(14L, AsyncTaskStatus.PENDING, null);
        }

        @Test
        @DisplayName("重试失败时应回写失败状态")
        void shouldMarkFailedWhenRetryThrowsException() {
            when(redisService.addStreamMessage(
                    eq(AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY),
                    any()
            )).thenThrow(new RuntimeException("redis unavailable"));

            voiceEvaluateStreamConsumer.retryMessage(
                    new VoiceEvaluateStreamConsumer.VoiceEvaluatePayload("15"),
                    3
            );

            verify(voiceInterviewService).updateEvaluateStatus(15L, AsyncTaskStatus.FAILED, "redis unavailable");
        }
    }
}
