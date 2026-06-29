package org.example.modules.voiceinterview.controller;

import org.example.common.constant.CommonConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.common.result.Result;
import org.example.modules.voiceinterview.dto.CreateSessionRequest;
import org.example.modules.voiceinterview.dto.SessionMetaDTO;
import org.example.modules.voiceinterview.dto.SessionResponseDTO;
import org.example.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import org.example.modules.voiceinterview.dto.VoiceEvaluationStatusDTO;
import org.example.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import org.example.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.example.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import org.example.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试控制器测试")
public class VoiceInterviewControllerTest {

    @Mock
    private VoiceInterviewService voiceInterviewService;

    @Mock
    private VoiceInterviewEvaluationService evaluationService;

    @Mock
    private VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    @InjectMocks
    private VoiceInterviewController voiceInterviewController;

    @Nested
    @DisplayName("会话管理")
    class SessionManagementTests {

        @Test
        @DisplayName("创建会话时应返回服务层结果")
        void shouldCreateSession() {
            CreateSessionRequest request = buildCreateSessionRequest();
            SessionResponseDTO expected = buildSessionResponse(101L, "IN_PROGRESS", "INTRO");
            when(voiceInterviewService.createSession(request)).thenReturn(expected);

            Result<SessionResponseDTO> result = voiceInterviewController.createSession(request);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(voiceInterviewService).createSession(request);
        }

        @Test
        @DisplayName("获取会话详情时应返回会话信息")
        void shouldGetSession() {
            SessionResponseDTO expected = buildSessionResponse(102L, "PAUSED", "TECH");
            when(voiceInterviewService.getSessionDTO(102L)).thenReturn(expected);

            Result<SessionResponseDTO> result = voiceInterviewController.getSession(102L);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(voiceInterviewService).getSessionDTO(102L);
        }

        @Test
        @DisplayName("获取不存在会话时应抛出业务异常")
        void shouldThrowWhenSessionNotFound() {
            when(voiceInterviewService.getSessionDTO(103L)).thenReturn(null);

            assertThatThrownBy(() -> voiceInterviewController.getSession(103L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.VOICE_SESSION_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("结束会话且未生成评估时应触发异步评估")
        void shouldEndSessionAndTriggerEvaluation() {
            VoiceInterviewSessionEntity session = buildSessionEntity(104L, null, null);
            when(voiceInterviewService.getSession(104L)).thenReturn(session);

            Result<Void> result = voiceInterviewController.endSession(104L);

            assertSuccess(result);
            verify(voiceInterviewService).endSession("104");
            verify(voiceInterviewService).getSession(104L);
            verify(voiceInterviewService).updateEvaluateStatus(104L, AsyncTaskStatus.PENDING, null);
            verify(voiceEvaluateStreamProducer).sendEvaluateTask("104");
        }

        @Test
        @DisplayName("结束会话时已完成评估不应重复触发")
        void shouldNotTriggerEvaluationWhenCompleted() {
            VoiceInterviewSessionEntity session = buildSessionEntity(105L, AsyncTaskStatus.COMPLETED, null);
            VoiceEvaluationDetailDTO detail = buildEvaluationDetail(105L);
            when(voiceInterviewService.getSession(105L)).thenReturn(session);
            when(evaluationService.getEvaluation(105L)).thenReturn(detail);

            Result<Void> result = voiceInterviewController.endSession(105L);

            assertSuccess(result);
            verify(voiceInterviewService).endSession("105");
            verify(evaluationService).getEvaluation(105L);
            verify(voiceInterviewService, never()).updateEvaluateStatus(105L, AsyncTaskStatus.PENDING, null);
            verify(voiceEvaluateStreamProducer, never()).sendEvaluateTask("105");
        }

        @Test
        @DisplayName("暂停会话时应使用去空白后的原因")
        void shouldPauseSessionWithTrimmedReason() {
            Result<Void> result = voiceInterviewController.pauseSession(
                    106L, Map.of("reason", " timeout "));

            assertSuccess(result);
            verify(voiceInterviewService).pauseSession("106", "timeout");
        }

        @Test
        @DisplayName("暂停会话时空请求体应使用默认原因")
        void shouldPauseSessionWithDefaultReason() {
            Result<Void> result = voiceInterviewController.pauseSession(107L, null);

            assertSuccess(result);
            verify(voiceInterviewService).pauseSession("107", "user_initiated");
        }

        @Test
        @DisplayName("恢复会话时应返回恢复后的会话信息")
        void shouldResumeSession() {
            SessionResponseDTO expected = buildSessionResponse(108L, "IN_PROGRESS", "PROJECT");
            when(voiceInterviewService.resumeSession("108")).thenReturn(expected);

            Result<SessionResponseDTO> result = voiceInterviewController.resumeSession(108L);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(voiceInterviewService).resumeSession("108");
        }

        @Test
        @DisplayName("查询会话列表时应透传筛选条件")
        void shouldGetAllSessions() {
            List<SessionMetaDTO> expected = List.of(buildSessionMeta(109L, "COMPLETED"));
            when(voiceInterviewService.getAllSessions("user-1", "completed")).thenReturn(expected);

            Result<List<SessionMetaDTO>> result =
                    voiceInterviewController.getAllSessions("user-1", "completed");

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(voiceInterviewService).getAllSessions("user-1", "completed");
        }

        @Test
        @DisplayName("删除会话时应委托服务层")
        void shouldDeleteSession() {
            Result<Void> result = voiceInterviewController.deleteSession(110L);

            assertSuccess(result);
            assertThat(result.getData()).isNull();
            verify(voiceInterviewService).deleteSession(110L);
        }
    }

    @Nested
    @DisplayName("消息与评估查询")
    class MessageAndEvaluationTests {

        @Test
        @DisplayName("获取消息列表时应返回服务层结果")
        void shouldGetMessages() {
            List<VoiceInterviewMessageDTO> expected = List.of(buildMessage(111L, 1));
            when(voiceInterviewService.getConversationHistoryDTO("111")).thenReturn(expected);

            Result<List<VoiceInterviewMessageDTO>> result = voiceInterviewController.getMessages(111L);

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(voiceInterviewService).getConversationHistoryDTO("111");
        }

        @Test
        @DisplayName("获取评估状态时应返回处理中状态")
        void shouldGetEvaluationProgressStatus() {
            VoiceInterviewSessionEntity session = buildSessionEntity(112L, AsyncTaskStatus.PROCESSING, null);
            when(voiceInterviewService.getSession(112L)).thenReturn(session);

            Result<VoiceEvaluationStatusDTO> result = voiceInterviewController.getEvaluation(112L);

            assertSuccess(result);
            assertThat(result.getData().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PROCESSING.name());
            assertThat(result.getData().getEvaluation()).isNull();
            verify(voiceInterviewService).getSession(112L);
            verifyNoInteractions(evaluationService);
        }

        @Test
        @DisplayName("获取评估状态时已完成应返回评估详情")
        void shouldGetCompletedEvaluation() {
            VoiceInterviewSessionEntity session = buildSessionEntity(113L, AsyncTaskStatus.COMPLETED, null);
            VoiceEvaluationDetailDTO detail = buildEvaluationDetail(113L);
            when(voiceInterviewService.getSession(113L)).thenReturn(session);
            when(evaluationService.getEvaluation(113L)).thenReturn(detail);

            Result<VoiceEvaluationStatusDTO> result = voiceInterviewController.getEvaluation(113L);

            assertSuccess(result);
            assertThat(result.getData().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED.name());
            assertThat(result.getData().getEvaluation()).isEqualTo(detail);
            verify(evaluationService).getEvaluation(113L);
        }

        @Test
        @DisplayName("已完成但评估结果缺失时应降级为失败状态")
        void shouldFallbackToFailedWhenCompletedEvaluationMissing() {
            VoiceInterviewSessionEntity session = buildSessionEntity(114L, AsyncTaskStatus.COMPLETED, null);
            when(voiceInterviewService.getSession(114L)).thenReturn(session);
            when(evaluationService.getEvaluation(114L)).thenThrow(new BusinessException(
                    ErrorCode.VOICE_EVALUATION_NOT_FOUND, "语音面试评估结果不存在"));

            Result<VoiceEvaluationStatusDTO> result = voiceInterviewController.getEvaluation(114L);

            assertSuccess(result);
            assertThat(result.getData().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.FAILED.name());
            assertThat(result.getData().getEvaluateError()).isEqualTo("评估结果缺失，请重试生成");
        }

        @Test
        @DisplayName("未开始评估时查询应抛出业务异常")
        void shouldThrowWhenEvaluationNotStarted() {
            VoiceInterviewSessionEntity session = buildSessionEntity(115L, null, null);
            when(voiceInterviewService.getSession(115L)).thenReturn(session);

            assertThatThrownBy(() -> voiceInterviewController.getEvaluation(115L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.VOICE_EVALUATION_NOT_FOUND.getCode()));
            verifyNoInteractions(evaluationService);
        }
    }

    @Nested
    @DisplayName("评估触发")
    class EvaluationTriggerTests {

        @Test
        @DisplayName("生成评估时未开始应入队并返回待处理状态")
        void shouldGenerateEvaluationWhenNotStarted() {
            VoiceInterviewSessionEntity session = buildSessionEntity(116L, null, null);
            when(voiceInterviewService.getSession(116L)).thenReturn(session);

            Result<VoiceEvaluationStatusDTO> result = voiceInterviewController.generateEvaluation(116L);

            assertSuccess(result);
            assertThat(result.getData().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING.name());
            verify(voiceInterviewService).updateEvaluateStatus(116L, AsyncTaskStatus.PENDING, null);
            verify(voiceEvaluateStreamProducer).sendEvaluateTask("116");
        }

        @Test
        @DisplayName("生成评估时处理中应直接复用当前状态")
        void shouldReuseEvaluationWhenProcessing() {
            VoiceInterviewSessionEntity session = buildSessionEntity(117L, AsyncTaskStatus.PROCESSING, "处理中");
            when(voiceInterviewService.getSession(117L)).thenReturn(session);

            Result<VoiceEvaluationStatusDTO> result = voiceInterviewController.generateEvaluation(117L);

            assertSuccess(result);
            assertThat(result.getData().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PROCESSING.name());
            assertThat(result.getData().getEvaluateError()).isEqualTo("处理中");
            verify(voiceInterviewService, never()).updateEvaluateStatus(117L, AsyncTaskStatus.PENDING, null);
            verify(voiceEvaluateStreamProducer, never()).sendEvaluateTask("117");
        }

        @Test
        @DisplayName("生成评估时失败状态应重新入队")
        void shouldRegenerateEvaluationWhenFailed() {
            VoiceInterviewSessionEntity session = buildSessionEntity(118L, AsyncTaskStatus.FAILED, "上次失败");
            when(voiceInterviewService.getSession(118L)).thenReturn(session);

            Result<VoiceEvaluationStatusDTO> result = voiceInterviewController.generateEvaluation(118L);

            assertSuccess(result);
            assertThat(result.getData().getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING.name());
            verify(voiceInterviewService).updateEvaluateStatus(118L, AsyncTaskStatus.PENDING, null);
            verify(voiceEvaluateStreamProducer).sendEvaluateTask("118");
        }

        @Test
        @DisplayName("生成评估时会话不存在应抛出业务异常")
        void shouldThrowWhenGenerateEvaluationSessionMissing() {
            when(voiceInterviewService.getSession(119L)).thenReturn(null);

            assertThatThrownBy(() -> voiceInterviewController.generateEvaluation(119L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.VOICE_SESSION_NOT_FOUND.getCode()));
        }
    }

    private void assertSuccess(Result<?> result) {
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(CommonConstants.StatusCode.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
    }

    private CreateSessionRequest buildCreateSessionRequest() {
        return CreateSessionRequest.builder()
                .roleType("java-backend")
                .skillId("java-backend")
                .difficulty("mid")
                .customJdText("负责后端服务开发")
                .resumeId(1L)
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(true)
                .hrEnabled(true)
                .plannedDuration(30)
                .llmProvider("dashscope")
                .build();
    }

    private SessionResponseDTO buildSessionResponse(Long sessionId, String status, String phase) {
        return SessionResponseDTO.builder()
                .sessionId(sessionId)
                .roleType("java-backend")
                .status(status)
                .currentPhase(phase)
                .startTime(LocalDateTime.of(2026, 6, 29, 10, 0))
                .plannedDuration(30)
                .webSocketUrl("ws://localhost:8080/ws/voice-interview/" + sessionId)
                .build();
    }

    private SessionMetaDTO buildSessionMeta(Long sessionId, String status) {
        return SessionMetaDTO.builder()
                .sessionId(sessionId)
                .roleType("java-backend")
                .status(status)
                .currentPhase("COMPLETED")
                .createdAt(LocalDateTime.of(2026, 6, 29, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 29, 9, 30))
                .actualDuration(28)
                .messageCount(6L)
                .evaluateStatus(AsyncTaskStatus.COMPLETED.name())
                .build();
    }

    private VoiceInterviewMessageDTO buildMessage(Long sessionId, int sequenceNum) {
        return VoiceInterviewMessageDTO.builder()
                .id((long) sequenceNum)
                .sessionId(sessionId)
                .messageType("AI_SPEECH")
                .phase("TECH")
                .aiGeneratedText("请介绍一下 JVM 内存模型")
                .timestamp(LocalDateTime.of(2026, 6, 29, 10, sequenceNum))
                .sequenceNum(sequenceNum)
                .build();
    }

    private VoiceInterviewSessionEntity buildSessionEntity(
            Long sessionId,
            AsyncTaskStatus status,
            String error) {
        return VoiceInterviewSessionEntity.builder()
                .id(sessionId)
                .userId("default")
                .roleType("java-backend")
                .skillId("java-backend")
                .difficulty("mid")
                .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                .plannedDuration(30)
                .startTime(LocalDateTime.of(2026, 6, 29, 10, 0))
                .evaluateStatus(status)
                .evaluateError(error)
                .build();
    }

    private VoiceEvaluationDetailDTO buildEvaluationDetail(Long sessionId) {
        return VoiceEvaluationDetailDTO.builder()
                .sessionId(sessionId)
                .totalQuestions(1)
                .overallScore(88)
                .overallFeedback("整体表现良好")
                .strengths(List.of("表达清晰"))
                .improvements(List.of("补充更多细节"))
                .answers(List.of(VoiceEvaluationDetailDTO.AnswerDetail.builder()
                        .questionIndex(1)
                        .question("请介绍一下 JVM 内存模型")
                        .category("技术能力")
                        .userAnswer("我会从堆、栈和方法区展开")
                        .score(88)
                        .feedback("回答方向正确")
                        .referenceAnswer("从线程私有和线程共享区域展开")
                        .keyPoints(List.of("堆", "栈", "方法区"))
                        .build()))
                .build();
    }
}
