package org.example.modules.voiceinterview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.config.VoiceInterviewProperties;
import org.example.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import org.example.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import org.example.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import org.example.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

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

}
