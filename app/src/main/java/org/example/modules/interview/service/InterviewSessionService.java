package org.example.modules.interview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.infrastructure.redis.InterviewSessionCache;
import org.example.modules.interview.listener.EvaluateStreamProducer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionService {
    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
}
