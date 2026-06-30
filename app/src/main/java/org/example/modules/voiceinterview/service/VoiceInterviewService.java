package org.example.modules.voiceinterview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.config.VoiceInterviewProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.modules.voiceinterview.dto.CreateSessionRequest;
import org.example.modules.voiceinterview.dto.SessionMetaDTO;
import org.example.modules.voiceinterview.dto.SessionResponseDTO;
import org.example.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import org.example.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import org.example.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.example.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import org.example.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import org.example.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Voice Interview Service
 * 语音面试服务
 * 为语音访谈会话管理提供业务逻辑，包括
 * 会话生命周期管理（创建、结束、检索）
 * 阶段转换和状态跟踪
 * 信息持久性和会话历史
 * 活动会话的 Redis 缓存
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewService {
    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final RedissonClient redissonClient;
    private final VoiceInterviewProperties properties;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";
    private static final int CACHE_TTL_HOURS = 1;
    private static final String DEFAULT_USER_ID = "default";

    /**
     * Create a new voice interview session
     * 创建新的语音面试会话
     *
     * @param request Session creation request with role type and phase configuration
     * @return SessionResponseDTO with session details and WebSocket URL
     */
    @Transactional
    public SessionResponseDTO createSession(CreateSessionRequest request) {
        validateCreateSessionRequest(request);
        VoiceInterviewSessionEntity session = buildSessionEntity(request);
        VoiceInterviewSessionEntity savedSession = sessionRepository.save(session);
        cacheSession(savedSession);
        log.info("Voice interview session created: sessionId={}, roleType={}, skillId={}",
                savedSession.getId(), savedSession.getRoleType(), savedSession.getSkillId());
        return toSessionResponse(savedSession);
    }

    /**
     * 仅当会话处于 IN_PROGRESS 状态时结束，用于 WebSocket 异常断开的兜底。
     * 正常结束的 endSession 已设为 COMPLETED，此方法不会重复操作。
     */
    @Transactional
    public void endSessionIfInProgress(String sessionId) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(parseSessionId(sessionId));
        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        completeSession(session);
    }

    /**
     * End interview session and update status
     * 结束面试会话并更新状态
     *
     * @param sessionId Session ID (String format, will be converted to Long)
     */
    @Transactional
    public void endSession(String sessionId) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(parseSessionId(sessionId));
        if (session.getStatus() == VoiceInterviewSessionStatus.COMPLETED) {
            return;
        }
        completeSession(session);
    }

    /**
     * Get session by ID with Redis cache fallback
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId Session ID as Long
     * @return VoiceInterviewSessionEntity or null if not found
     */
    public VoiceInterviewSessionEntity getSession(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            return null;
        }
        VoiceInterviewSessionEntity cachedSession = getCachedSession(sessionId);
        if (cachedSession != null) {
            return cachedSession;
        }
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            cacheSession(session);
        }
        return session;
    }

    /**
     * Start a new interview phase
     * 开始新的面试阶段
     *
     * @param sessionId Session ID (String format)
     * @param phaseStr  Phase as string (INTRO, TECH, PROJECT, HR)
     */
    @Transactional
    public void startPhase(String sessionId, String phaseStr) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(parseSessionId(sessionId));
        VoiceInterviewSessionEntity.InterviewPhase phase = parsePhase(phaseStr);
        session.setCurrentPhase(phase);
        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());
        sessionRepository.save(session);
        cacheSession(session);
        log.info("Voice interview phase started: sessionId={}, phase={}", session.getId(), phase);
    }

    /**
     * Get current phase for session
     * 获取会话当前阶段
     *
     * @param sessionId Session ID (String format)
     * @return Current InterviewPhase or null if session not found
     */
    public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(parseSessionId(sessionId));
        return session == null ? null : session.getCurrentPhase();
    }

    /**
     * Save dialogue message (user and AI text) to database
     * 保存对话消息（用户和AI文本）到数据库
     *
     * @param sessionId Session ID (String format)
     * @param userText  User's recognized speech text
     * @param aiText    AI's generated response text
     */
    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(parseSessionId(sessionId));
        int nextSequenceNum = resolveNextSequenceNum(session.getId());
        List<VoiceInterviewMessageEntity> messagesToSave = buildMessagesToSave(
                session, nextSequenceNum, userText, aiText
        );
        if (messagesToSave.isEmpty()) {
            return;
        }
        messageRepository.saveAll(messagesToSave);
        cacheSession(session);
        log.info("Voice interview messages saved: sessionId={}, count={}", session.getId(), messagesToSave.size());
    }

    /**
     * Get conversation history for a session
     * 获取会话的对话历史记录
     *
     * @param sessionId Session ID (String format)
     * @return List of messages ordered by sequence number
     */
    public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
        Long parsedSessionId = parseSessionId(sessionId);
        ensureSessionExists(parsedSessionId);
        return messageRepository.findBySessionIdOrderBySequenceNumAsc(parsedSessionId);
    }

    /**
     * Get conversation history as DTOs (for frontend)
     */
    public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
        return getConversationHistory(sessionId).stream()
                .map(this::toMessageDTO)
                .toList();
    }

    /**
     * Pause interview session
     * 暂停面试会话
     *
     * @param sessionId Session ID
     * @param reason Pause reason (user_initiated or timeout)
     */
    @Transactional
    public void pauseSession(String sessionId, String reason) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(parseSessionId(sessionId));
        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());
        sessionRepository.save(session);
        cacheSession(session);
        log.info("Voice interview session paused: sessionId={}, reason={}", session.getId(), normalizeReason(reason));
    }

    /**
     * Resume interview session
     * 恢复面试会话
     *
     * @param sessionId Session ID
     * @return SessionResponseDTO with WebSocket URL
     */
    @Transactional
    public SessionResponseDTO resumeSession(String sessionId) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(parseSessionId(sessionId));
        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());
        sessionRepository.save(session);
        cacheSession(session);
        log.info("Voice interview session resumed: sessionId={}", session.getId());
        return toSessionResponse(session);
    }

    /**
     * Get all sessions for a user
     * 获取用户所有会话
     *
     * @param userId User ID (optional, defaults to DEFAULT_USER_ID)
     * @param status Filter by status (optional)
     * @return List of session metadata
     */
    public List<SessionMetaDTO> getAllSessions(String userId, String status) {
        String normalizedUserId = resolveUserId(userId);
        List<VoiceInterviewSessionEntity> sessions = loadSessionsForUser(normalizedUserId, status);
        return sessions.stream()
                .map(this::toSessionMeta)
                .toList();
    }

    /**
     * Get session DTO by ID
     * 通过ID获取会话DTO
     *
     * @param sessionId Session ID as Long
     * @return SessionResponseDTO with session details or null if not found
     */
    public SessionResponseDTO getSessionDTO(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session == null ? null : toSessionResponse(session);
    }

    /**
     * Check if session should transition to next phase based on duration and question count
     * 检查是否应该转换到下一个阶段（基于时长和问题数量）
     *
     * @param session        Current session
     * @param phaseStartTime Time when current phase started
     * @param questionCount  Number of questions asked in current phase
     * @return true if should transition, false otherwise
     */
    public boolean shouldTransitionToNextPhase(VoiceInterviewSessionEntity session,
                                               LocalDateTime phaseStartTime,
                                               int questionCount) {
        if (session == null || phaseStartTime == null || session.getCurrentPhase() == null) {
            return false;
        }
        VoiceInterviewProperties.DurationConfig durationConfig = resolveDurationConfig(session.getCurrentPhase());
        long elapsedMinutes = Math.max(0, ChronoUnit.MINUTES.between(phaseStartTime, LocalDateTime.now()));
        if (questionCount >= durationConfig.getMaxQuestions()) {
            return true;
        }
        if (elapsedMinutes < durationConfig.getMinDuration()) {
            return false;
        }
        return questionCount >= durationConfig.getMinQuestions()
                || elapsedMinutes >= durationConfig.getSuggestedDuration();
    }

    /**
     * Get the next enabled phase after current phase
     * 获取当前阶段之后的下一个启用的阶段
     *
     * @param session Current session
     * @return Next InterviewPhase or COMPLETED if no more phases
     */
    public VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
        if (session == null || session.getCurrentPhase() == null) {
            return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
        }
        List<VoiceInterviewSessionEntity.InterviewPhase> enabledPhases = resolveEnabledPhases(session);
        int currentIndex = enabledPhases.indexOf(session.getCurrentPhase());
        if (currentIndex < 0 || currentIndex + 1 >= enabledPhases.size()) {
            return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
        }
        return enabledPhases.get(currentIndex + 1);
    }

    /**
     * 更新会话实体的评估状态（由生产者/消费者/控制器共享）
     */
    public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findById(sessionId).ifPresent(session -> {
                saveEvaluateStatus(session, status, error);
            });
        } catch (Exception e) {
            log.error("Failed to update evaluation status: sessionId={}, status={}, error={}",
                    sessionId, status, e.getMessage(), e);
        }
    }

    /**
     * 触发会话的异步评估（由控制器调用）
     */
    public void triggerEvaluation(Long sessionId) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(sessionId);
        if (!shouldTriggerEvaluation(session.getEvaluateStatus())) {
            return;
        }
        saveEvaluateStatus(session, AsyncTaskStatus.PENDING, null);
        voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
    }

    /**
     * 删除语音面试会话及其关联的消息和评估记录
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        VoiceInterviewSessionEntity session = loadSessionOrThrow(sessionId);
        try {
            messageRepository.deleteBySessionId(sessionId);
            evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);
            sessionRepository.delete(session);
            deleteCache(sessionId);
            log.info("Voice interview session deleted: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("Delete voice interview session failed: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "删除语音面试会话失败", e);
        }
    }

    private void validateCreateSessionRequest(CreateSessionRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "创建语音面试会话请求不能为空");
        }
        if (!hasAnyPhaseEnabled(request)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少需要启用一个面试阶段");
        }
    }

    private VoiceInterviewSessionEntity buildSessionEntity(CreateSessionRequest request) {
        VoiceInterviewSessionEntity.InterviewPhase firstPhase = resolveFirstEnabledPhase(request);
        String skillId = StringUtils.hasText(request.getSkillId()) ? request.getSkillId().strip() : "java-backend";
        String roleType = resolveRoleType(request, skillId);
        return VoiceInterviewSessionEntity.builder()
                .userId(DEFAULT_USER_ID)
                .roleType(roleType)
                .skillId(skillId)
                .difficulty(defaultIfBlank(request.getDifficulty(), "mid"))
                .customJdText(stripToNull(request.getCustomJdText()))
                .resumeId(request.getResumeId())
                .introEnabled(Boolean.TRUE.equals(request.getIntroEnabled()))
                .techEnabled(Boolean.TRUE.equals(request.getTechEnabled()))
                .projectEnabled(Boolean.TRUE.equals(request.getProjectEnabled()))
                .hrEnabled(Boolean.TRUE.equals(request.getHrEnabled()))
                .llmProvider(defaultIfBlank(request.getLlmProvider(), properties.getLlmProvider()))
                .currentPhase(firstPhase)
                .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                .plannedDuration(request.getPlannedDuration() != null ? request.getPlannedDuration() : 30)
                .evaluateStatus(null)
                .evaluateError(null)
                .build();
    }

    private void completeSession(VoiceInterviewSessionEntity session) {
        LocalDateTime endTime = LocalDateTime.now();
        boolean shouldScheduleEvaluation = shouldTriggerEvaluation(session.getEvaluateStatus());
        session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        session.setEndTime(endTime);
        session.setActualDuration(resolveActualDurationMinutes(session.getStartTime(), endTime));
        if (shouldScheduleEvaluation) {
            session.setEvaluateStatus(AsyncTaskStatus.PENDING);
            session.setEvaluateError(null);
        }
        sessionRepository.save(session);
        cacheSession(session);
        log.info("Voice interview session ended: sessionId={}, actualDuration={}",
                session.getId(), session.getActualDuration());
        if (shouldScheduleEvaluation) {
            scheduleEvaluationAfterCommit(session.getId());
        }
    }

    private void saveEvaluateStatus(VoiceInterviewSessionEntity session, AsyncTaskStatus status, String error) {
        session.setEvaluateStatus(status);
        session.setEvaluateError(error);
        sessionRepository.save(session);
        cacheSession(session);
        log.debug("Evaluation status updated: sessionId={}, status={}", session.getId(), status);
    }

    private boolean shouldTriggerEvaluation(AsyncTaskStatus status) {
        return status == null || status == AsyncTaskStatus.FAILED;
    }

    private void scheduleEvaluationAfterCommit(Long sessionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendEvaluationTask(sessionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendEvaluationTask(sessionId);
            }
        });
    }

    private void sendEvaluationTask(Long sessionId) {
        try {
            voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
        } catch (Exception e) {
            log.error("Send voice interview evaluation task failed: sessionId={}", sessionId, e);
            updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, e.getMessage());
        }
    }

    private List<VoiceInterviewMessageEntity> buildMessagesToSave(
            VoiceInterviewSessionEntity session,
            int nextSequenceNum,
            String userText,
            String aiText) {
        List<VoiceInterviewMessageEntity> messages = new ArrayList<>();
        int sequenceNum = nextSequenceNum;
        if (StringUtils.hasText(aiText)) {
            messages.add(buildMessageEntity(
                    session.getId(),
                    "AI_SPEECH",
                    session.getCurrentPhase(),
                    null,
                    aiText.strip(),
                    sequenceNum++
            ));
        }
        if (StringUtils.hasText(userText)) {
            messages.add(buildMessageEntity(
                    session.getId(),
                    "USER_SPEECH",
                    session.getCurrentPhase(),
                    userText.strip(),
                    null,
                    sequenceNum
            ));
        }
        return messages;
    }

    private VoiceInterviewMessageEntity buildMessageEntity(
            Long sessionId,
            String messageType,
            VoiceInterviewSessionEntity.InterviewPhase phase,
            String userText,
            String aiText,
            int sequenceNum) {
        return VoiceInterviewMessageEntity.builder()
                .sessionId(sessionId)
                .messageType(messageType)
                .phase(phase)
                .userRecognizedText(userText)
                .aiGeneratedText(aiText)
                .sequenceNum(sequenceNum)
                .build();
    }

    private VoiceInterviewSessionEntity loadSessionOrThrow(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "语音面试会话ID不合法");
        }
        VoiceInterviewSessionEntity session = getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "语音面试会话不存在: " + sessionId);
        }
        return session;
    }

    private void ensureSessionExists(Long sessionId) {
        loadSessionOrThrow(sessionId);
    }

    private Long parseSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "语音面试会话ID不能为空");
        }
        try {
            long parsed = Long.parseLong(sessionId.strip());
            if (parsed <= 0) {
                throw new NumberFormatException("sessionId must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "语音面试会话ID不合法", e);
        }
    }

    private VoiceInterviewSessionEntity.InterviewPhase parsePhase(String phaseStr) {
        if (!StringUtils.hasText(phaseStr)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试阶段不能为空");
        }
        try {
            return VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的面试阶段: " + phaseStr, e);
        }
    }

    private VoiceInterviewSessionEntity getCachedSession(Long sessionId) {
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(buildCacheKey(sessionId));
        VoiceInterviewSessionEntity cachedSession = bucket.get();
        if (cachedSession != null) {
            bucket.expire(Duration.ofHours(CACHE_TTL_HOURS));
        }
        return cachedSession;
    }

    private void cacheSession(VoiceInterviewSessionEntity session) {
        if (session == null || session.getId() == null) {
            return;
        }
        redissonClient.<VoiceInterviewSessionEntity>getBucket(buildCacheKey(session.getId()))
                .set(session, Duration.ofHours(CACHE_TTL_HOURS));
    }

    private void deleteCache(Long sessionId) {
        redissonClient.getBucket(buildCacheKey(sessionId)).delete();
    }

    private String buildCacheKey(Long sessionId) {
        return SESSION_CACHE_KEY_PREFIX + sessionId;
    }

    private SessionResponseDTO toSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .currentPhase(session.getCurrentPhase() == null ? null : session.getCurrentPhase().name())
                .status(session.getStatus() == null ? null : session.getStatus().name())
                .startTime(session.getStartTime())
                .plannedDuration(session.getPlannedDuration())
                .webSocketUrl(buildWebSocketUrl(session.getId()))
                .build();
    }

    private SessionMetaDTO toSessionMeta(VoiceInterviewSessionEntity session) {
        return SessionMetaDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .status(session.getStatus() == null ? null : session.getStatus().name())
                .currentPhase(session.getCurrentPhase() == null ? null : session.getCurrentPhase().name())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .actualDuration(session.getActualDuration())
                .messageCount(messageRepository.countBySessionId(session.getId()))
                .evaluateStatus(session.getEvaluateStatus() == null ? null : session.getEvaluateStatus().name())
                .evaluateError(session.getEvaluateError())
                .build();
    }

    private VoiceInterviewMessageDTO toMessageDTO(VoiceInterviewMessageEntity message) {
        return VoiceInterviewMessageDTO.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .messageType(message.getMessageType())
                .phase(message.getPhase() == null ? null : message.getPhase().name())
                .userRecognizedText(message.getUserRecognizedText())
                .aiGeneratedText(message.getAiGeneratedText())
                .timestamp(message.getTimestamp())
                .sequenceNum(message.getSequenceNum())
                .build();
    }

    private List<VoiceInterviewSessionEntity> loadSessionsForUser(String userId, String status) {
        if (!StringUtils.hasText(status)) {
            return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        }
        VoiceInterviewSessionStatus sessionStatus = parseStatus(status);
        return sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, sessionStatus);
    }

    private VoiceInterviewSessionStatus parseStatus(String status) {
        try {
            return VoiceInterviewSessionStatus.valueOf(status.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的会话状态: " + status, e);
        }
    }

    private int resolveNextSequenceNum(Long sessionId) {
        List<VoiceInterviewMessageEntity> history = messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionId);
        if (history.isEmpty()) {
            return 1;
        }
        return history.getLast().getSequenceNum() + 1;
    }

    private String buildWebSocketUrl(Long sessionId) {
        return "ws://localhost:8080/ws/voice-interview/" + sessionId;
    }

    private boolean hasAnyPhaseEnabled(CreateSessionRequest request) {
        return Boolean.TRUE.equals(request.getIntroEnabled())
                || Boolean.TRUE.equals(request.getTechEnabled())
                || Boolean.TRUE.equals(request.getProjectEnabled())
                || Boolean.TRUE.equals(request.getHrEnabled());
    }

    private VoiceInterviewSessionEntity.InterviewPhase resolveFirstEnabledPhase(CreateSessionRequest request) {
        if (Boolean.TRUE.equals(request.getIntroEnabled())) {
            return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        }
        if (Boolean.TRUE.equals(request.getTechEnabled())) {
            return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        }
        if (Boolean.TRUE.equals(request.getProjectEnabled())) {
            return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        }
        if (Boolean.TRUE.equals(request.getHrEnabled())) {
            return VoiceInterviewSessionEntity.InterviewPhase.HR;
        }
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    private List<VoiceInterviewSessionEntity.InterviewPhase> resolveEnabledPhases(VoiceInterviewSessionEntity session) {
        List<VoiceInterviewSessionEntity.InterviewPhase> phases = new ArrayList<>();
        if (Boolean.TRUE.equals(session.getIntroEnabled())) {
            phases.add(VoiceInterviewSessionEntity.InterviewPhase.INTRO);
        }
        if (Boolean.TRUE.equals(session.getTechEnabled())) {
            phases.add(VoiceInterviewSessionEntity.InterviewPhase.TECH);
        }
        if (Boolean.TRUE.equals(session.getProjectEnabled())) {
            phases.add(VoiceInterviewSessionEntity.InterviewPhase.PROJECT);
        }
        if (Boolean.TRUE.equals(session.getHrEnabled())) {
            phases.add(VoiceInterviewSessionEntity.InterviewPhase.HR);
        }
        if (phases.isEmpty()) {
            phases.add(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        }
        return phases;
    }

    private VoiceInterviewProperties.DurationConfig resolveDurationConfig(
            VoiceInterviewSessionEntity.InterviewPhase phase) {
        return switch (phase) {
            case INTRO -> properties.getPhase().getIntro();
            case TECH -> properties.getPhase().getTech();
            case PROJECT -> properties.getPhase().getProject();
            case HR, COMPLETED -> properties.getPhase().getHr();
        };
    }

    private int resolveActualDurationMinutes(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return (int) Math.max(0, ChronoUnit.MINUTES.between(startTime, endTime));
    }

    private String resolveUserId(String userId) {
        return StringUtils.hasText(userId) ? userId.strip() : DEFAULT_USER_ID;
    }

    private String resolveRoleType(CreateSessionRequest request, String skillId) {
        if (StringUtils.hasText(request.getRoleType())) {
            return request.getRoleType().strip();
        }
        return skillId;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.strip() : defaultValue;
    }

    private String stripToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.strip();
    }

    private String normalizeReason(String reason) {
        return StringUtils.hasText(reason) ? reason.strip() : "unknown";
    }
}
