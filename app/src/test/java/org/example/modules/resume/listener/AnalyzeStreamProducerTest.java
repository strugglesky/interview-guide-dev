package org.example.modules.resume.listener;

import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeRepository;
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
@DisplayName("简历分析生产者测试")
class AnalyzeStreamProducerTest {

    @Mock
    private RedisService redisService;

    @Mock
    private ResumeRepository resumeRepository;

    private TestableAnalyzeStreamProducer analyzeStreamProducer;
    private ResumeEntity resumeEntity;

    @BeforeEach
    void setUp() {
        analyzeStreamProducer = new TestableAnalyzeStreamProducer(redisService, resumeRepository);
        resumeEntity = new ResumeEntity();
        resumeEntity.setId(1L);
        resumeEntity.setAnalyzeStatus(AsyncTaskStatus.PENDING);
    }

    /**
     * 验证发送成功时会写入完整的 Stream 消息体。
     */
    @Test
    @DisplayName("发送成功时应写入完整消息")
    void shouldSendAnalyzeTaskSuccessfully() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY), anyMap()))
                .thenReturn(new StreamMessageId(1L, 2L));

        // 通过测试子类暴露的入口触发 sendTask，验证生产者构造出的消息内容。
        analyzeStreamProducer.sendAnalyzeTask(1L, "resume content");

        ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisService).addStreamMessage(
                eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
                messageCaptor.capture()
        );
        Map<String, Object> message = messageCaptor.getValue();
        assertThat(message)
                .containsEntry(AsyncTaskStreamConstants.FIELD_RESUME_ID, "1")
                .containsEntry(AsyncTaskStreamConstants.FIELD_CONTENT, "resume content")
                .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
        assertThat(message.get("taskType")).isEqualTo("简历分析");
        assertThat(message.get("sentAt")).isInstanceOf(String.class);
        verify(resumeRepository, never()).findById(any());
        verify(resumeRepository, never()).save(any());
    }

    /**
     * 验证发送失败时会转为业务异常，并把简历状态更新为失败。
     */
    @Test
    @DisplayName("发送失败时应更新分析状态为失败")
    void shouldUpdateAnalyzeStatusWhenSendFailed() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException("redis unavailable"));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resumeEntity));

        assertThatThrownBy(() -> analyzeStreamProducer.sendAnalyzeTask(1L, "resume content"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());

        // 捕获保存后的实体，验证失败状态和错误信息已经回写。
        ArgumentCaptor<ResumeEntity> entityCaptor = ArgumentCaptor.forClass(ResumeEntity.class);
        verify(resumeRepository).save(entityCaptor.capture());
        ResumeEntity savedEntity = entityCaptor.getValue();
        assertThat(savedEntity.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(savedEntity.getAnalyzeError()).isEqualTo("redis unavailable");
    }

    /**
     * 验证超长错误消息会在持久化前截断到字段允许的长度。
     */
    @Test
    @DisplayName("发送失败时应截断超长错误信息")
    void shouldTruncateLongErrorMessageWhenSendFailed() {
        String longError = "x".repeat(600);
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException(longError));
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resumeEntity));

        assertThatThrownBy(() -> analyzeStreamProducer.sendAnalyzeTask(1L, "resume content"))
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<ResumeEntity> entityCaptor = ArgumentCaptor.forClass(ResumeEntity.class);
        verify(resumeRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getAnalyzeError())
                .hasSize(500)
                .isEqualTo("x".repeat(500));
    }

    /**
     * 验证简历不存在时不会发生状态持久化。
     */
    @Test
    @DisplayName("发送失败且简历不存在时不应保存状态")
    void shouldNotSaveWhenResumeNotFoundOnSendFailed() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException("redis unavailable"));
        when(resumeRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analyzeStreamProducer.sendAnalyzeTask(1L, "resume content"))
                .isInstanceOf(BusinessException.class);

        verify(resumeRepository, never()).save(any());
    }

    /**
     * 验证当前实现下空字符串内容仍会被写入 Stream。
     */
    @Test
    @DisplayName("内容为空字符串时仍应发送任务")
    void shouldSendTaskWhenContentIsBlank() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY), anyMap()))
                .thenReturn(new StreamMessageId(3L, 4L));

        // 保持与当前生产代码一致，空字符串内容仍然允许发送。
        analyzeStreamProducer.sendAnalyzeTask(2L, "");

        ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisService).addStreamMessage(
                eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
                messageCaptor.capture()
        );
        assertThat(messageCaptor.getValue())
                .containsEntry(AsyncTaskStreamConstants.FIELD_RESUME_ID, "2")
                .containsEntry(AsyncTaskStreamConstants.FIELD_CONTENT, "");
    }

    private static final class TestableAnalyzeStreamProducer extends AnalyzeStreamProducer {

        private TestableAnalyzeStreamProducer(
                RedisService redisService,
                ResumeRepository resumeRepository
        ) {
            super(redisService, resumeRepository);
        }

        private void sendAnalyzeTask(Long resumeId, String content) {
            sendTask(new AnalyzeStreamProducer.AnalyzeTaskPayload(resumeId, content));
        }
    }
}
