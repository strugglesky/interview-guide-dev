package org.example.modules.knowledgebase.listener;

import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
@DisplayName("Vectorize stream producer test")
class VectorStreamProducerTest {

    @Mock
    private RedisService redisService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    private VectorizeStreamProducer vectorizeStreamProducer;

    private KnowledgeBaseEntity knowledgeBaseEntity;

    @BeforeEach
    void setUp() {
        knowledgeBaseEntity = new KnowledgeBaseEntity();
        knowledgeBaseEntity.setId(1L);
        knowledgeBaseEntity.setVectorStatus(VectorStatus.PENDING);
    }

    /**
     * Verify successful send writes a full stream message.
     */
    @Test
    @DisplayName("should write full stream message on success")
    void shouldSendVectorizeTaskSuccessfully() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY), anyMap()))
                .thenReturn(new StreamMessageId(1L, 2L));

        vectorizeStreamProducer.sendVectorizeTask(1L, "test content");

        ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisService).addStreamMessage(
                eq(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY),
                messageCaptor.capture()
        );
        Map<String, Object> message = messageCaptor.getValue();
        assertThat(message)
                .containsEntry(AsyncTaskStreamConstants.FIELD_KB_ID, "1")
                .containsEntry(AsyncTaskStreamConstants.FIELD_CONTENT, "test content")
                .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
        assertThat(message.get("taskType")).isInstanceOf(String.class);
        assertThat(message.get("sentAt")).isInstanceOf(String.class);
        verify(knowledgeBaseRepository, never()).findById(any());
        verify(knowledgeBaseRepository, never()).save(any());
    }

    /**
     * Verify runtime send failure is converted to a business exception and persisted as failed status.
     */
    @Test
    @DisplayName("should update vector status to failed when send fails")
    void shouldUpdateVectorStatusWhenSendFailed() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException("redis unavailable"));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(knowledgeBaseEntity));

        assertThatThrownBy(() -> vectorizeStreamProducer.sendVectorizeTask(1L, "test content"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());

        ArgumentCaptor<KnowledgeBaseEntity> entityCaptor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);
        verify(knowledgeBaseRepository).save(entityCaptor.capture());
        KnowledgeBaseEntity savedEntity = entityCaptor.getValue();
        assertThat(savedEntity.getVectorStatus()).isEqualTo(VectorStatus.FAILED);
        assertThat(savedEntity.getVectorError()).isEqualTo("redis unavailable");
    }

    /**
     * Verify long error messages are truncated to 500 characters before persistence.
     */
    @Test
    @DisplayName("should truncate long error message when send fails")
    void shouldTruncateErrorMessageWhenSendFailed() {
        String longError = "x".repeat(600);
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException(longError));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(knowledgeBaseEntity));

        assertThatThrownBy(() -> vectorizeStreamProducer.sendVectorizeTask(1L, "test content"))
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<KnowledgeBaseEntity> entityCaptor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);
        verify(knowledgeBaseRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getVectorError())
                .hasSize(500)
                .isEqualTo("x".repeat(500));
    }

    /**
     * Verify missing knowledge base on failure does not trigger save.
     */
    @Test
    @DisplayName("should not save when knowledge base is missing on failure")
    void shouldNotSaveWhenKnowledgeBaseNotFoundOnSendFailed() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY), anyMap()))
                .thenThrow(new RuntimeException("redis unavailable"));
        when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vectorizeStreamProducer.sendVectorizeTask(1L, "test content"))
                .isInstanceOf(BusinessException.class);

        verify(knowledgeBaseRepository, never()).save(any());
    }

    /**
     * Verify blank content is still sent under current implementation.
     */
    @Test
    @DisplayName("should send task when content is blank")
    void shouldSendTaskWhenContentIsBlank() {
        when(redisService.addStreamMessage(eq(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY), anyMap()))
                .thenReturn(new StreamMessageId(3L, 4L));

        vectorizeStreamProducer.sendVectorizeTask(2L, "");

        ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redisService).addStreamMessage(
                eq(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY),
                messageCaptor.capture()
        );
        assertThat(messageCaptor.getValue())
                .containsEntry(AsyncTaskStreamConstants.FIELD_KB_ID, "2")
                .containsEntry(AsyncTaskStreamConstants.FIELD_CONTENT, "");
    }
}
