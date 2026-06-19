package org.example.infrastructure.redis;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewSessionDTO;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import org.example.modules.interview.model.InterviewSessionDTO.SessionStatus;
import tools.jackson.core.type.TypeReference;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 面试会话 Redis 缓存服务
 * 管理面试会话在 Redis 中的存储
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionCache {
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    /**
     * 缓存键前缀
     */
    private static final String SESSION_KEY_PREFIX = "interview:session:";

    /**
     * 简历ID到会话ID的映射前缀（用于查找未完成会话）
     */
    private static final String RESUME_SESSION_KEY_PREFIX = "interview:resume:";

    /**
     * 会话默认过期时间（24小时）
     */
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    /**
     * 缓存的会话数据
     */
    @Data
    public static class CachedSession implements Serializable {
        private String sessionId;
        private String resumeText;
        private Long resumeId;
        private String questionsJson;  // 序列化的问题列表
        private int currentIndex;
        private SessionStatus status;

        public CachedSession() {
        }

        public CachedSession(String sessionId, String resumeText, Long resumeId,
                             List<InterviewQuestionDTO> questions, int currentIndex,
                             SessionStatus status, ObjectMapper objectMapper) {
            this.sessionId = sessionId;
            this.resumeText = resumeText;
            this.resumeId = resumeId;
            this.currentIndex = currentIndex;
            this.status = status;
            try {
                this.questionsJson = objectMapper.writeValueAsString(questions);
            } catch (JacksonException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化问题列表失败", e);
            }
        }

        public List<InterviewQuestionDTO> getQuestions(ObjectMapper objectMapper) {
            try {
                return objectMapper.readValue(questionsJson, new TypeReference<>() {});
            } catch (JacksonException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化问题列表失败");
            }
        }

    }

    /**
     * 保存会话到缓存
     */
    public void saveSession(String sessionId, String resumeText, Long resumeId,
                            List<InterviewQuestionDTO> questions, int currentIndex,
                            SessionStatus status) {
        validateSessionId(sessionId);
        CachedSession cachedSession = new CachedSession(
                sessionId,
                resumeText,
                resumeId,
                questions,
                currentIndex,
                status,
                objectMapper
        );
        redisService.set(buildSessionKey(sessionId), cachedSession, SESSION_TTL);
        saveResumeSessionMapping(resumeId, sessionId);
        log.info("面试会话已写入缓存: sessionId={}, resumeId={}, status={}", sessionId, resumeId, status);
    }

    /**
     * 获取缓存的会话
     */
    public Optional<CachedSession> getSession(String sessionId) {
        validateSessionId(sessionId);
        CachedSession cachedSession = redisService.get(buildSessionKey(sessionId));
        return Optional.ofNullable(cachedSession);
    }

    /**
     * 更新会话状态
     */
    public void updateSessionStatus(String sessionId, SessionStatus status) {
        updateSession(sessionId, cachedSession -> cachedSession.setStatus(status));
    }

    /**
     * 更新当前问题索引
     */
    public void updateCurrentIndex(String sessionId, int currentIndex) {
        updateSession(sessionId, cachedSession -> cachedSession.setCurrentIndex(currentIndex));
    }

    /**
     * 更新问题列表（用于保存答案）
     */
    public void updateQuestions(String sessionId, List<InterviewQuestionDTO> questions) {
        updateSession(sessionId, cachedSession -> {
            try {
                cachedSession.setQuestionsJson(objectMapper.writeValueAsString(questions));
            } catch (JacksonException e) {
                log.error("更新缓存问题列表失败: sessionId={}", sessionId, e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化问题列表失败", e);
            }
        });
    }

    /**
     * 删除会话缓存
     */
    public void deleteSession(String sessionId) {
        validateSessionId(sessionId);
        Optional<CachedSession> cachedSessionOpt = getSession(sessionId);
        redisService.delete(buildSessionKey(sessionId));
        cachedSessionOpt.map(CachedSession::getResumeId)
                .ifPresent(this::deleteResumeSessionMapping);
        log.info("面试会话缓存已删除: sessionId={}", sessionId);
    }

    /**
     * 根据简历ID查找未完成的会话ID
     */
    public Optional<String> findUnfinishedSessionId(Long resumeId) {
        if (resumeId == null) {
            return Optional.empty();
        }
        String sessionId = redisService.get(buildResumeSessionKey(resumeId));
        if (sessionId == null) {
            return Optional.empty();
        }
        Optional<CachedSession> cachedSessionOpt = getSession(sessionId);
        if (cachedSessionOpt.isEmpty()) {
            deleteResumeSessionMapping(resumeId);
            return Optional.empty();
        }
        SessionStatus status = cachedSessionOpt.get().getStatus();
        if (status == SessionStatus.COMPLETED || status == SessionStatus.EVALUATED) {
            deleteResumeSessionMapping(resumeId);
            return Optional.empty();
        }
        return Optional.of(sessionId);
    }

    /**
     * 刷新会话过期时间
     */
    public void refreshSessionTTL(String sessionId) {
        validateSessionId(sessionId);
        Optional<CachedSession> cachedSessionOpt = getSession(sessionId);
        if (cachedSessionOpt.isEmpty()) {
            return;
        }
        redisService.expire(buildSessionKey(sessionId), SESSION_TTL);
        if (cachedSessionOpt.get().getResumeId() != null) {
            redisService.expire(buildResumeSessionKey(cachedSessionOpt.get().getResumeId()), SESSION_TTL);
        }
    }

    /**
     * 检查会话是否在缓存中
     */
    public boolean exists(String sessionId) {
        validateSessionId(sessionId);
        return redisService.exists(buildSessionKey(sessionId));
    }

    private void updateSession(String sessionId, Consumer<CachedSession> updater) {
        validateSessionId(sessionId);
        CachedSession cachedSession = getSession(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
                        "缓存中的面试会话不存在: " + sessionId
                ));
        updater.accept(cachedSession);
        redisService.set(buildSessionKey(sessionId), cachedSession, SESSION_TTL);
        saveResumeSessionMapping(cachedSession.getResumeId(), sessionId);
        log.info("面试会话缓存已更新: sessionId={}", sessionId);
    }

    private void saveResumeSessionMapping(Long resumeId, String sessionId) {
        if (resumeId == null) {
            return;
        }
        redisService.set(buildResumeSessionKey(resumeId), sessionId, SESSION_TTL);
    }

    private void deleteResumeSessionMapping(Long resumeId) {
        if (resumeId == null) {
            return;
        }
        redisService.delete(buildResumeSessionKey(resumeId));
    }

    private String buildSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String buildResumeSessionKey(Long resumeId) {
        return RESUME_SESSION_KEY_PREFIX + resumeId;
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话ID不能为空");
        }
    }


}
