package org.example.modules.voiceinterview.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.common.result.Result;
import org.example.modules.voiceinterview.dto.CreateSessionRequest;
import org.example.modules.voiceinterview.dto.SessionMetaDTO;
import org.example.modules.voiceinterview.dto.SessionResponseDTO;
import org.example.modules.voiceinterview.dto.VoiceEvaluationStatusDTO;
import org.example.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import org.example.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.example.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import org.example.modules.voiceinterview.service.VoiceInterviewService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Voice Interview Controller
 * 语音面试控制器
 * <p>
 * REST API endpoints for voice interview session management:
 * - Session lifecycle (create, retrieve, end)
 * - Message history retrieval
 * - Async evaluation trigger and status polling
 * </p>
 */
@RestController
@RequestMapping("/api/voice-interview")
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewController {
    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    /**
     * 创建新的语音访谈会话
     */
    @PostMapping("/sessions")
    public Result<SessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(voiceInterviewService.createSession(request));
    }

    /**
     * Get session details by ID
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<SessionResponseDTO> getSession(@PathVariable Long sessionId) {
        SessionResponseDTO session = voiceInterviewService.getSessionDTO(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "语音面试会话不存在: " + sessionId);
        }
        return Result.success(session);
    }

    /**
     * End interview session
     * <p>
     * This also triggers async evaluation via Redis Stream.
     * </p>
     */
    @PostMapping("/sessions/{sessionId}/end")
    public Result<Void> endSession(@PathVariable Long sessionId) {
        voiceInterviewService.endSession(String.valueOf(sessionId));
        triggerEvaluationIfNeeded(sessionId);
        return Result.success();
    }

    /**
     * Pause interview session
     */
    @PutMapping("/sessions/{sessionId}/pause")
    public Result<Void> pauseSession(@PathVariable Long sessionId,
                                     @RequestBody Map<String, String> request) {
        voiceInterviewService.pauseSession(String.valueOf(sessionId), resolvePauseReason(request));
        return Result.success();
    }

    /**
     * Resume interview session
     */
    @PutMapping("/sessions/{sessionId}/resume")
    public Result<SessionResponseDTO> resumeSession(@PathVariable Long sessionId) {
        return Result.success(voiceInterviewService.resumeSession(String.valueOf(sessionId)));
    }

    /**
     * Get all sessions for user
     */
    @GetMapping("/sessions")
    public Result<List<SessionMetaDTO>> getAllSessions(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status
    ) {
        return Result.success(voiceInterviewService.getAllSessions(userId, status));
    }

    /**
     * 删除语音面试会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        voiceInterviewService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * Get conversation history for a session
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<VoiceInterviewMessageDTO>> getMessages(@PathVariable Long sessionId) {
        return Result.success(voiceInterviewService.getConversationHistoryDTO(String.valueOf(sessionId)));
    }

    /**
     * 获取会话的评估状态和结果
     * <p>
     * 返回当前评估状态（PENDING/PROCESSING/COMPLETED/FAILED）
     * 以及完成时的评估结果。
     * 前台轮询此端点，直到评估完成。
     * </p>
     */
    @GetMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> getEvaluation(@PathVariable Long sessionId) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(sessionId);
        VoiceEvaluationStatusDTO status = loadCurrentEvaluationStatus(sessionId, session);
        if (status == null) {
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND, "语音面试评估尚未开始");
        }
        return Result.success(status);
    }

    /**
     * Trigger async evaluation for a session
     * <p>
     * Enqueues evaluation task to Redis Stream and returns immediately.
     * Frontend should then poll GET /evaluation to track progress.
     * If evaluation is already in progress or completed, returns current status.
     * </p>
     */
    @PostMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> generateEvaluation(@PathVariable Long sessionId) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(sessionId);
        VoiceEvaluationStatusDTO currentStatus = loadCurrentEvaluationStatus(sessionId, session);
        if (shouldReuseCurrentStatus(currentStatus)) {
            return Result.success(currentStatus);
        }
        enqueueEvaluationTask(sessionId);
        return Result.success(buildProgressStatus(AsyncTaskStatus.PENDING, null));
    }

    private VoiceInterviewSessionEntity loadSessionOrThrow(Long sessionId) {
        VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "语音面试会话不存在: " + sessionId);
        }
        return session;
    }

    private void triggerEvaluationIfNeeded(Long sessionId) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(sessionId);
        VoiceEvaluationStatusDTO currentStatus = loadCurrentEvaluationStatus(sessionId, session);
        if (shouldReuseCurrentStatus(currentStatus)) {
            return;
        }
        enqueueEvaluationTask(sessionId);
    }

    private void enqueueEvaluationTask(Long sessionId) {
        voiceInterviewService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        voiceEvaluateStreamProducer.sendEvaluateTask(String.valueOf(sessionId));
    }

    private VoiceEvaluationStatusDTO loadCurrentEvaluationStatus(Long sessionId,
                                                                 VoiceInterviewSessionEntity session) {
        if (session.getEvaluateStatus() == null) {
            return null;
        }
        if (AsyncTaskStatus.COMPLETED.equals(session.getEvaluateStatus())) {
            return loadCompletedEvaluationStatus(sessionId);
        }
        return buildProgressStatus(session.getEvaluateStatus(), session.getEvaluateError());
    }

    private VoiceEvaluationStatusDTO loadCompletedEvaluationStatus(Long sessionId) {
        try {
            return VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(AsyncTaskStatus.COMPLETED.name())
                    .evaluation(evaluationService.getEvaluation(sessionId))
                    .build();
        } catch (BusinessException e) {
            if (!ErrorCode.VOICE_EVALUATION_NOT_FOUND.getCode().equals(e.getCode())) {
                throw e;
            }
            log.error("Voice interview evaluation result missing: sessionId={}", sessionId, e);
            return buildProgressStatus(AsyncTaskStatus.FAILED, "评估结果缺失，请重试生成");
        }
    }

    private VoiceEvaluationStatusDTO buildProgressStatus(AsyncTaskStatus status, String error) {
        return VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(status.name())
                .evaluateError(error)
                .build();
    }

    private boolean shouldReuseCurrentStatus(VoiceEvaluationStatusDTO currentStatus) {
        if (currentStatus == null || !StringUtils.hasText(currentStatus.getEvaluateStatus())) {
            return false;
        }
        return !AsyncTaskStatus.FAILED.name().equals(currentStatus.getEvaluateStatus());
    }

    private String resolvePauseReason(Map<String, String> request) {
        if (request == null) {
            return "user_initiated";
        }
        String reason = request.get("reason");
        return StringUtils.hasText(reason) ? reason.strip() : "user_initiated";
    }
}
