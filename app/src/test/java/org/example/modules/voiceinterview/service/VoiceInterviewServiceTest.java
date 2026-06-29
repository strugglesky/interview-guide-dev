package org.example.modules.voiceinterview.service;

import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.common.config.VoiceInterviewProperties;
import org.example.modules.voiceinterview.dto.CreateSessionRequest;
import org.example.modules.voiceinterview.dto.SessionMetaDTO;
import org.example.modules.voiceinterview.dto.SessionResponseDTO;
import org.example.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import org.example.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import org.example.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import org.example.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.example.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import org.example.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import org.example.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试服务测试")
class VoiceInterviewServiceTest {

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    @Mock
    private VoiceInterviewMessageRepository messageRepository;

    @Mock
    private VoiceInterviewEvaluationRepository evaluationRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    @Mock
    private RBucket<VoiceInterviewSessionEntity> sessionBucket;

    private VoiceInterviewService voiceInterviewService;
    private VoiceInterviewProperties properties;

    @BeforeEach
    void setUp() {
        properties = buildProperties();
        voiceInterviewService = new VoiceInterviewService(
                sessionRepository,
                messageRepository,
                evaluationRepository,
                redissonClient,
                properties,
                voiceEvaluateStreamProducer
        );
    }

    @Nested
    @DisplayName("创建会话")
    class CreateSessionTests {

        @Test
        @DisplayName("应保存会话 写入缓存并返回响应DTO")
        void shouldCreateSessionAndReturnResponse() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .skillId("java-backend")
                    .difficulty("senior")
                    .customJdText("  jd text  ")
                    .resumeId(12L)
                    .introEnabled(false)
                    .techEnabled(true)
                    .projectEnabled(true)
                    .hrEnabled(false)
                    .plannedDuration(45)
                    .llmProvider("dashscope")
                    .build();
            when(sessionRepository.save(any())).thenAnswer(invocation -> {
                VoiceInterviewSessionEntity entity = invocation.getArgument(0);
                entity.setId(100L);
                entity.setStartTime(LocalDateTime.of(2026, 6, 29, 10, 0));
                return entity;
            });

            SessionResponseDTO response = voiceInterviewService.createSession(request);

            ArgumentCaptor<VoiceInterviewSessionEntity> entityCaptor =
                    ArgumentCaptor.forClass(VoiceInterviewSessionEntity.class);
            verify(sessionRepository).save(entityCaptor.capture());
            VoiceInterviewSessionEntity saved = entityCaptor.getValue();
            assertThat(saved.getUserId()).isEqualTo("default");
            assertThat(saved.getRoleType()).isEqualTo("java-backend");
            assertThat(saved.getSkillId()).isEqualTo("java-backend");
            assertThat(saved.getDifficulty()).isEqualTo("senior");
            assertThat(saved.getCustomJdText()).isEqualTo("jd text");
            assertThat(saved.getCurrentPhase()).isEqualTo(VoiceInterviewSessionEntity.InterviewPhase.TECH);
            assertThat(saved.getStatus()).isEqualTo(VoiceInterviewSessionStatus.IN_PROGRESS);
            verify(sessionBucket).set(any(VoiceInterviewSessionEntity.class), eq(Duration.ofHours(1)));

            assertThat(response.getSessionId()).isEqualTo(100L);
            assertThat(response.getRoleType()).isEqualTo("java-backend");
            assertThat(response.getCurrentPhase()).isEqualTo("TECH");
            assertThat(response.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(response.getPlannedDuration()).isEqualTo(45);
            assertThat(response.getWebSocketUrl()).isEqualTo("ws://localhost:8080/ws/voice-interview/100");
        }

        @Test
        @DisplayName("未启用任何阶段时应抛出业务异常")
        void shouldThrowWhenNoPhaseEnabled() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .skillId("java-backend")
                    .introEnabled(false)
                    .techEnabled(false)
                    .projectEnabled(false)
                    .hrEnabled(false)
                    .build();

            assertThatThrownBy(() -> voiceInterviewService.createSession(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        }
    }

    @Nested
    @DisplayName("会话查询")
    class GetSessionTests {

        @Test
        @DisplayName("缓存命中时应直接返回缓存会话")
        void shouldReturnCachedSessionWhenCacheHit() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity cached = buildSession(101L, VoiceInterviewSessionStatus.IN_PROGRESS);
            when(sessionBucket.get()).thenReturn(cached);

            VoiceInterviewSessionEntity result = voiceInterviewService.getSession(101L);

            assertThat(result).isSameAs(cached);
            verify(sessionBucket).expire(Duration.ofHours(1));
            verify(sessionRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("缓存未命中时应从数据库加载并写回缓存")
        void shouldLoadSessionFromRepositoryWhenCacheMiss() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(102L, VoiceInterviewSessionStatus.IN_PROGRESS);
            when(sessionBucket.get()).thenReturn(null);
            when(sessionRepository.findById(102L)).thenReturn(Optional.of(session));

            VoiceInterviewSessionEntity result = voiceInterviewService.getSession(102L);

            assertThat(result).isSameAs(session);
            verify(sessionRepository).findById(102L);
            verify(sessionBucket).set(session, Duration.ofHours(1));
        }
    }

    @Nested
    @DisplayName("消息保存")
    class SaveMessageTests {

        @Test
        @DisplayName("应按AI在前 用户在后保存两条消息并递增序号")
        void shouldSaveAiAndUserMessagesInOrder() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(103L, VoiceInterviewSessionStatus.IN_PROGRESS);
            session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);
            when(sessionBucket.get()).thenReturn(session);
            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(103L))
                    .thenReturn(List.of(buildMessage(103L, "SYSTEM", 4)));

            voiceInterviewService.saveMessage("103", "用户回答", "AI提问");

            ArgumentCaptor<List<VoiceInterviewMessageEntity>> listCaptor = ArgumentCaptor.forClass(List.class);
            verify(messageRepository).saveAll(listCaptor.capture());
            List<VoiceInterviewMessageEntity> savedMessages = listCaptor.getValue();
            assertThat(savedMessages).hasSize(2);
            assertThat(savedMessages.get(0).getMessageType()).isEqualTo("AI_SPEECH");
            assertThat(savedMessages.get(0).getAiGeneratedText()).isEqualTo("AI提问");
            assertThat(savedMessages.get(0).getSequenceNum()).isEqualTo(5);
            assertThat(savedMessages.get(1).getMessageType()).isEqualTo("USER_SPEECH");
            assertThat(savedMessages.get(1).getUserRecognizedText()).isEqualTo("用户回答");
            assertThat(savedMessages.get(1).getSequenceNum()).isEqualTo(6);
        }

        @Test
        @DisplayName("文本都为空时不应保存消息")
        void shouldNotSaveMessageWhenTextsAreBlank() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(104L, VoiceInterviewSessionStatus.IN_PROGRESS);
            when(sessionBucket.get()).thenReturn(session);
            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(104L)).thenReturn(List.of());

            voiceInterviewService.saveMessage("104", "   ", "   ");

            verify(messageRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("状态流转")
    class SessionStateTests {

        @Test
        @DisplayName("暂停会话时应更新状态并记录暂停时间")
        void shouldPauseSession() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(105L, VoiceInterviewSessionStatus.IN_PROGRESS);
            when(sessionBucket.get()).thenReturn(session);

            voiceInterviewService.pauseSession("105", "user_initiated");

            verify(sessionRepository).save(session);
            assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.PAUSED);
            assertThat(session.getPausedAt()).isNotNull();
        }

        @Test
        @DisplayName("恢复会话时应更新状态并返回会话DTO")
        void shouldResumeSession() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(106L, VoiceInterviewSessionStatus.PAUSED);
            when(sessionBucket.get()).thenReturn(session);

            SessionResponseDTO response = voiceInterviewService.resumeSession("106");

            verify(sessionRepository).save(session);
            assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.IN_PROGRESS);
            assertThat(session.getResumedAt()).isNotNull();
            assertThat(response.getSessionId()).isEqualTo(106L);
            assertThat(response.getStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("结束进行中的会话时应置为完成并计算实际时长")
        void shouldEndSessionAndSetCompleted() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(107L, VoiceInterviewSessionStatus.IN_PROGRESS);
            session.setStartTime(LocalDateTime.now().minusMinutes(25));
            when(sessionBucket.get()).thenReturn(session);

            voiceInterviewService.endSession("107");

            verify(sessionRepository).save(session);
            assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.COMPLETED);
            assertThat(session.getCurrentPhase()).isEqualTo(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
            assertThat(session.getEndTime()).isNotNull();
            assertThat(session.getActualDuration()).isGreaterThanOrEqualTo(25);
        }

        @Test
        @DisplayName("兜底结束时非进行中会话不应重复保存")
        void shouldSkipEndSessionIfNotInProgress() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(108L, VoiceInterviewSessionStatus.PAUSED);
            when(sessionBucket.get()).thenReturn(session);

            voiceInterviewService.endSessionIfInProgress("108");

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("启动阶段时应更新当前阶段")
        void shouldStartPhase() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(109L, VoiceInterviewSessionStatus.PAUSED);
            when(sessionBucket.get()).thenReturn(session);

            voiceInterviewService.startPhase("109", "project");

            verify(sessionRepository).save(session);
            assertThat(session.getCurrentPhase()).isEqualTo(VoiceInterviewSessionEntity.InterviewPhase.PROJECT);
            assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("查询与映射")
    class QueryAndMappingTests {

        @Test
        @DisplayName("应返回消息DTO列表")
        void shouldReturnConversationHistoryDto() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(110L, VoiceInterviewSessionStatus.IN_PROGRESS);
            VoiceInterviewMessageEntity message = buildMessage(110L, "AI_SPEECH", 1);
            message.setPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO);
            message.setAiGeneratedText("你好，请自我介绍");
            when(sessionBucket.get()).thenReturn(session);
            when(messageRepository.findBySessionIdOrderBySequenceNumAsc(110L)).thenReturn(List.of(message));

            List<VoiceInterviewMessageDTO> result = voiceInterviewService.getConversationHistoryDTO("110");

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getMessageType()).isEqualTo("AI_SPEECH");
            assertThat(result.getFirst().getPhase()).isEqualTo("INTRO");
            assertThat(result.getFirst().getAiGeneratedText()).isEqualTo("你好，请自我介绍");
        }

        @Test
        @DisplayName("按状态查询列表时应组装元数据")
        void shouldReturnSessionMetaListByStatus() {
            VoiceInterviewSessionEntity session = buildSession(111L, VoiceInterviewSessionStatus.COMPLETED);
            session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
            session.setEvaluateError(null);
            session.setActualDuration(33);
            session.setCreatedAt(LocalDateTime.of(2026, 6, 28, 10, 0));
            session.setUpdatedAt(LocalDateTime.of(2026, 6, 28, 10, 40));
            when(sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc("default", VoiceInterviewSessionStatus.COMPLETED))
                    .thenReturn(List.of(session));
            when(messageRepository.countBySessionId(111L)).thenReturn(8L);

            List<SessionMetaDTO> result = voiceInterviewService.getAllSessions(null, "completed");

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getSessionId()).isEqualTo(111L);
            assertThat(result.getFirst().getStatus()).isEqualTo("COMPLETED");
            assertThat(result.getFirst().getCurrentPhase()).isEqualTo("TECH");
            assertThat(result.getFirst().getMessageCount()).isEqualTo(8L);
            assertThat(result.getFirst().getEvaluateStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("获取会话DTO时不存在会话应返回null")
        void shouldReturnNullWhenSessionDtoNotFound() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            when(sessionBucket.get()).thenReturn(null);
            when(sessionRepository.findById(999L)).thenReturn(Optional.empty());

            SessionResponseDTO result = voiceInterviewService.getSessionDTO(999L);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("阶段判断")
    class PhaseDecisionTests {

        @Test
        @DisplayName("达到最小时长且问题数满足条件时应切换下一阶段")
        void shouldTransitionToNextPhaseWhenThresholdReached() {
            VoiceInterviewSessionEntity session = buildSession(112L, VoiceInterviewSessionStatus.IN_PROGRESS);
            session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);

            boolean result = voiceInterviewService.shouldTransitionToNextPhase(
                    session,
                    LocalDateTime.now().minusMinutes(12),
                    3
            );

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("应返回当前阶段之后的下一个启用阶段")
        void shouldReturnNextEnabledPhase() {
            VoiceInterviewSessionEntity session = buildSession(113L, VoiceInterviewSessionStatus.IN_PROGRESS);
            session.setIntroEnabled(false);
            session.setTechEnabled(true);
            session.setProjectEnabled(true);
            session.setHrEnabled(false);
            session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);

            VoiceInterviewSessionEntity.InterviewPhase result = voiceInterviewService.getNextPhase(session);

            assertThat(result).isEqualTo(VoiceInterviewSessionEntity.InterviewPhase.PROJECT);
        }
    }

    @Nested
    @DisplayName("评估与删除")
    class EvaluationAndDeleteTests {

        @Test
        @DisplayName("触发评估时应先更新状态再发送任务")
        void shouldTriggerEvaluation() {
            VoiceInterviewSessionEntity session = buildSession(114L, VoiceInterviewSessionStatus.IN_PROGRESS);
            when(sessionRepository.findById(114L)).thenReturn(Optional.of(session));

            voiceInterviewService.triggerEvaluation(114L);

            verify(sessionRepository).save(session);
            assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
            verify(voiceEvaluateStreamProducer).sendEvaluateTask("114");
        }

        @Test
        @DisplayName("删除会话时应删除消息 评估 会话及缓存")
        void shouldDeleteSessionAndRelatedData() {
            when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
            VoiceInterviewSessionEntity session = buildSession(115L, VoiceInterviewSessionStatus.COMPLETED);
            VoiceInterviewEvaluationEntity evaluation = new VoiceInterviewEvaluationEntity();
            evaluation.setSessionId(115L);
            when(sessionBucket.get()).thenReturn(session);
            when(evaluationRepository.findBySessionId(115L)).thenReturn(Optional.of(evaluation));

            voiceInterviewService.deleteSession(115L);

            verify(messageRepository).deleteBySessionId(115L);
            verify(evaluationRepository).delete(evaluation);
            verify(sessionRepository).delete(session);
            verify(sessionBucket).delete();
        }
    }

    private VoiceInterviewProperties buildProperties() {
        VoiceInterviewProperties value = new VoiceInterviewProperties();
        value.setLlmProvider("dashscope");
        return value;
    }

    private VoiceInterviewSessionEntity buildSession(Long sessionId, VoiceInterviewSessionStatus status) {
        VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
        session.setId(sessionId);
        session.setUserId("default");
        session.setRoleType("java-backend");
        session.setSkillId("java-backend");
        session.setDifficulty("mid");
        session.setIntroEnabled(false);
        session.setTechEnabled(true);
        session.setProjectEnabled(true);
        session.setHrEnabled(true);
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);
        session.setStatus(status);
        session.setPlannedDuration(30);
        session.setStartTime(LocalDateTime.of(2026, 6, 29, 10, 0));
        session.setCreatedAt(LocalDateTime.of(2026, 6, 29, 10, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 10, 10));
        return session;
    }

    private VoiceInterviewMessageEntity buildMessage(Long sessionId, String messageType, int sequenceNum) {
        VoiceInterviewMessageEntity message = new VoiceInterviewMessageEntity();
        message.setId((long) sequenceNum);
        message.setSessionId(sessionId);
        message.setMessageType(messageType);
        message.setSequenceNum(sequenceNum);
        message.setTimestamp(LocalDateTime.of(2026, 6, 29, 10, 0).plusMinutes(sequenceNum));
        return message;
    }
}
