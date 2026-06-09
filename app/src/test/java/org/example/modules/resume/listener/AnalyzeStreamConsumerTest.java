package org.example.modules.resume.listener;

import org.example.common.constant.AsyncTaskStreamConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeRepository;
import org.example.modules.resume.service.ResumeGradingService;
import org.example.modules.resume.service.ResumePersistenceService;
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
@DisplayName("简历分析消费者测试")
class AnalyzeStreamConsumerTest {

    @Mock
    private RedisService redisService;

    @Mock
    private ResumeGradingService gradingService;

    @Mock
    private ResumePersistenceService persistenceService;

    @Mock
    private ResumeRepository resumeRepository;

    private AnalyzeStreamConsumer analyzeStreamConsumer;

    @BeforeEach
    void setUp() {
        analyzeStreamConsumer = new AnalyzeStreamConsumer(
                redisService,
                gradingService,
                persistenceService,
                resumeRepository
        );
    }

    @Nested
    @DisplayName("消息解析")
    class ParsePayloadTest {

        /**
         * 验证消息字段完整时能够正确解析出简历分析载荷。
         */
        @Test
        @DisplayName("字段完整时应成功解析消息")
        void shouldParsePayloadSuccessfullyWhenFieldsAreValid() {
            // 构造符合消费者约定的 Stream 消息字段。
            Map<String, String> data = Map.of(
                    AsyncTaskStreamConstants.FIELD_RESUME_ID, "1",
                    AsyncTaskStreamConstants.FIELD_CONTENT, "resume content"
            );

            AnalyzeStreamConsumer.AnalyzePayload payload = analyzeStreamConsumer.parsePayload(
                    new StreamMessageId(1L, 1L),
                    data
            );

            assertThat(payload.resumeId()).isEqualTo(1L);
            assertThat(payload.content()).isEqualTo("resume content");
        }

        /**
         * 验证缺少必要字段时会抛出参数错误，避免脏消息进入业务处理。
         */
        @Test
        @DisplayName("字段缺失时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenRequiredFieldMissing() {
            // 这里只提供 resumeId，模拟 content 缺失的非法消息。
            Map<String, String> data = Map.of(
                    AsyncTaskStreamConstants.FIELD_RESUME_ID, "1"
            );

            assertThatThrownBy(() -> analyzeStreamConsumer.parsePayload(new StreamMessageId(1L, 2L), data))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> {
                        BusinessException businessException = (BusinessException) exception;
                        assertThat(businessException.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
                    });
        }

        /**
         * 验证 resumeId 非数字时会抛出参数错误，避免消息标识污染后续状态更新。
         */
        @Test
        @DisplayName("resumeId 非法时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenResumeIdInvalid() {
            // 使用非数字 resumeId，验证消费者会在解析阶段直接拦截。
            Map<String, String> data = Map.of(
                    AsyncTaskStreamConstants.FIELD_RESUME_ID, "abc",
                    AsyncTaskStreamConstants.FIELD_CONTENT, "resume content"
            );

            assertThatThrownBy(() -> analyzeStreamConsumer.parsePayload(new StreamMessageId(1L, 3L), data))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> {
                        BusinessException businessException = (BusinessException) exception;
                        assertThat(businessException.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
                    });
        }
    }

    @Nested
    @DisplayName("业务处理")
    class ProcessBusinessTest {

        /**
         * 验证简历存在时会调用评分服务并持久化分析结果。
         */
        @Test
        @DisplayName("简历存在时应完成评分并持久化")
        void shouldAnalyzeAndPersistWhenResumeExists() {
            ResumeEntity resume = buildResume(1L, AsyncTaskStatus.PENDING, null);
            AnalyzeStreamConsumer.AnalyzePayload payload =
                    new AnalyzeStreamConsumer.AnalyzePayload(1L, "resume content");
            ResumeAnalysisResponse response = buildResponse(85);
            when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));
            when(gradingService.analyzeResume("resume content")).thenReturn(response);

            // 触发业务处理，验证评分结果会继续传给持久化服务。
            analyzeStreamConsumer.processBusiness(payload);

            verify(gradingService).analyzeResume("resume content");
            verify(persistenceService).saveAnalysis(resume, response);
        }

        /**
         * 验证简历已经不存在时会直接跳过处理，不调用评分和持久化。
         */
        @Test
        @DisplayName("简历不存在时应跳过业务处理")
        void shouldSkipProcessWhenResumeMissing() {
            AnalyzeStreamConsumer.AnalyzePayload payload =
                    new AnalyzeStreamConsumer.AnalyzePayload(2L, "resume content");
            when(resumeRepository.findById(2L)).thenReturn(Optional.empty());

            // 简历被删除后，消费者应直接丢弃当前消息。
            analyzeStreamConsumer.processBusiness(payload);

            verify(gradingService, never()).analyzeResume(any());
            verify(persistenceService, never()).saveAnalysis(any(), any());
        }
    }

    @Nested
    @DisplayName("状态更新")
    class StatusUpdateTest {

        /**
         * 验证开始消费时会把简历状态更新为处理中并清空旧错误信息。
         */
        @Test
        @DisplayName("markProcessing 应更新为处理中")
        void shouldMarkResumeProcessing() {
            ResumeEntity resume = buildResume(3L, AsyncTaskStatus.FAILED, "old error");
            when(resumeRepository.findById(3L)).thenReturn(Optional.of(resume));

            // 调用处理中标记，验证状态流转和错误清理都生效。
            analyzeStreamConsumer.markProcessing(
                    new AnalyzeStreamConsumer.AnalyzePayload(3L, "resume content")
            );

            ArgumentCaptor<ResumeEntity> captor = ArgumentCaptor.forClass(ResumeEntity.class);
            verify(resumeRepository).save(captor.capture());
            assertThat(captor.getValue().getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.PROCESSING);
            assertThat(captor.getValue().getAnalyzeError()).isNull();
        }

        /**
         * 验证消费完成后会把简历状态更新为完成。
         */
        @Test
        @DisplayName("markCompleted 应更新为已完成")
        void shouldMarkResumeCompleted() {
            ResumeEntity resume = buildResume(4L, AsyncTaskStatus.PROCESSING, "old error");
            when(resumeRepository.findById(4L)).thenReturn(Optional.of(resume));

            // 调用完成标记，验证错误信息被同时清空。
            analyzeStreamConsumer.markCompleted(
                    new AnalyzeStreamConsumer.AnalyzePayload(4L, "resume content")
            );

            ArgumentCaptor<ResumeEntity> captor = ArgumentCaptor.forClass(ResumeEntity.class);
            verify(resumeRepository).save(captor.capture());
            assertThat(captor.getValue().getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
            assertThat(captor.getValue().getAnalyzeError()).isNull();
        }

        /**
         * 验证失败标记会保存失败状态，并把超长错误信息截断到字段长度限制内。
         */
        @Test
        @DisplayName("markFailed 应保存失败状态并截断错误")
        void shouldMarkResumeFailedAndTruncateError() {
            ResumeEntity resume = buildResume(5L, AsyncTaskStatus.PROCESSING, null);
            String longError = "x".repeat(600);
            when(resumeRepository.findById(5L)).thenReturn(Optional.of(resume));

            // 使用超长错误消息，验证实体保存前会被安全截断。
            analyzeStreamConsumer.markFailed(
                    new AnalyzeStreamConsumer.AnalyzePayload(5L, "resume content"),
                    longError
            );

            ArgumentCaptor<ResumeEntity> captor = ArgumentCaptor.forClass(ResumeEntity.class);
            verify(resumeRepository).save(captor.capture());
            assertThat(captor.getValue().getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.FAILED);
            assertThat(captor.getValue().getAnalyzeError()).hasSize(500);
        }
    }

    @Nested
    @DisplayName("重试处理")
    class RetryMessageTest {

        /**
         * 验证重新投递成功时会写入重试消息，并把简历状态恢复为待处理。
         */
        @Test
        @DisplayName("重试成功时应重新投递并重置状态")
        void shouldRetryMessageAndResetStatusWhenRetrySucceeds() {
            ResumeEntity resume = buildResume(6L, AsyncTaskStatus.FAILED, "old error");
            AnalyzeStreamConsumer.AnalyzePayload payload =
                    new AnalyzeStreamConsumer.AnalyzePayload(6L, "resume content");
            when(redisService.addStreamMessage(
                    eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
                    anyMap()
            )).thenReturn(new StreamMessageId(2L, 3L));
            when(resumeRepository.findById(6L)).thenReturn(Optional.of(resume));

            // 触发重试逻辑，验证重试消息字段和状态恢复同时生效。
            analyzeStreamConsumer.retryMessage(payload, 2);

            ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
            verify(redisService).addStreamMessage(
                    eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
                    messageCaptor.capture()
            );
            assertThat(messageCaptor.getValue())
                    .containsEntry(AsyncTaskStreamConstants.FIELD_RESUME_ID, "6")
                    .containsEntry(AsyncTaskStreamConstants.FIELD_CONTENT, "resume content")
                    .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "2");

            ArgumentCaptor<ResumeEntity> entityCaptor = ArgumentCaptor.forClass(ResumeEntity.class);
            verify(resumeRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.PENDING);
            assertThat(entityCaptor.getValue().getAnalyzeError()).isNull();
        }

        /**
         * 验证重新投递失败时会回写失败状态，并记录解析后的错误信息。
         */
        @Test
        @DisplayName("重试失败时应回写失败状态")
        void shouldMarkFailedWhenRetryThrowsException() {
            ResumeEntity resume = buildResume(7L, AsyncTaskStatus.PROCESSING, null);
            AnalyzeStreamConsumer.AnalyzePayload payload =
                    new AnalyzeStreamConsumer.AnalyzePayload(7L, "resume content");
            when(redisService.addStreamMessage(
                    eq(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY),
                    anyMap()
            )).thenThrow(new RuntimeException("redis unavailable"));
            when(resumeRepository.findById(7L)).thenReturn(Optional.of(resume));

            // 重试投递异常时，消费者应将任务状态显式置为失败。
            analyzeStreamConsumer.retryMessage(payload, 3);

            ArgumentCaptor<ResumeEntity> entityCaptor = ArgumentCaptor.forClass(ResumeEntity.class);
            verify(resumeRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.FAILED);
            assertThat(entityCaptor.getValue().getAnalyzeError()).isEqualTo("redis unavailable");
        }
    }

    private ResumeEntity buildResume(Long id, AsyncTaskStatus status, String error) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(id);
        resume.setAnalyzeStatus(status);
        resume.setAnalyzeError(error);
        return resume;
    }

    private ResumeAnalysisResponse buildResponse(int overallScore) {
        return new ResumeAnalysisResponse(
                overallScore,
                new ResumeAnalysisResponse.ScoreDetail(20, 18, 17, 15, 15),
                "分析完成",
                java.util.List.of("技术基础扎实"),
                java.util.List.of(),
                "resume content"
        );
    }
}
