package org.example.modules.interview.listener;

import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试评估生产者测试")
class EvaluateStreamProducerTest {

    @Mock
    private RedisService redisService;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    private EvaluateStreamProducer evaluateStreamProducer;
    private InterviewSessionEntity sessionEntity;

    @BeforeEach
    void setUp() {
        evaluateStreamProducer = new EvaluateStreamProducer(redisService, interviewSessionRepository);
        sessionEntity = new InterviewSessionEntity();
        sessionEntity.setSessionId("session-001");
        sessionEntity.setEvaluateStatus(AsyncTaskStatus.PENDING);
    }

    @Test
    @DisplayName("发送成功时应写入完整消息")
    void shouldSendEvaluateTaskSuccessfully() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY), anyMap()))
                .thenReturn(new StreamMessageId(1L, 2L));

        evaluateStreamProducer.sendEvaluateTask("session-001");

        ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisService).addStreamMessage(
                eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY),
                messageCaptor.capture()
        );
        Map<String, Object> message = messageCaptor.getValue();
        assertThat(message)
                .containsEntry(AsyncTaskStreamConstants.FIELD_SESSION_ID, "session-001")
                .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
        assertThat(message.get("taskType")).isEqualTo("评估");
        assertThat(message.get("sentAt")).isInstanceOf(String.class);
        verify(interviewSessionRepository, never()).findBySessionId(any());
        verify(interviewSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("发送失败时应更新评估状态为失败")
    void shouldUpdateEvaluateStatusWhenSendFailed() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException("redis unavailable"));
        when(interviewSessionRepository.findBySessionId("session-001")).thenReturn(Optional.of(sessionEntity));

        assertThatThrownBy(() -> evaluateStreamProducer.sendEvaluateTask("session-001"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());

        ArgumentCaptor<InterviewSessionEntity> entityCaptor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
        verify(interviewSessionRepository).save(entityCaptor.capture());
        InterviewSessionEntity savedEntity = entityCaptor.getValue();
        assertThat(savedEntity.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(savedEntity.getEvaluateError()).isEqualTo("redis unavailable");
    }

    @Test
    @DisplayName("发送失败时应截断超长错误信息")
    void shouldTruncateLongErrorMessageWhenSendFailed() {
        String longError = "x".repeat(600);
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException(longError));
        when(interviewSessionRepository.findBySessionId("session-001")).thenReturn(Optional.of(sessionEntity));

        assertThatThrownBy(() -> evaluateStreamProducer.sendEvaluateTask("session-001"))
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<InterviewSessionEntity> entityCaptor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
        verify(interviewSessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getEvaluateError())
                .hasSize(500)
                .isEqualTo("x".repeat(500));
    }

    @Test
    @DisplayName("发送失败且会话不存在时不应保存状态")
    void shouldNotSaveWhenSessionNotFoundOnSendFailed() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException("redis unavailable"));
        when(interviewSessionRepository.findBySessionId("session-001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> evaluateStreamProducer.sendEvaluateTask("session-001"))
                .isInstanceOf(BusinessException.class);

        verify(interviewSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("会话ID为空字符串时仍应发送任务")
    void shouldSendTaskWhenSessionIdIsBlank() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY), anyMap()))
                .thenReturn(new StreamMessageId(3L, 4L));

        evaluateStreamProducer.sendEvaluateTask("");

        ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisService).addStreamMessage(
                eq(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY),
                messageCaptor.capture()
        );
        assertThat(messageCaptor.getValue())
                .containsEntry(AsyncTaskStreamConstants.FIELD_SESSION_ID, "")
                .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
    }
}
