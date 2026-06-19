package org.example.modules.interview.listener;

import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.common.async.AbstractStreamConsumer;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.interview.repository.InterviewSessionRepository;
import org.example.modules.interview.service.AnswerEvaluationService;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 面试评估 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行评估
 */
@Slf4j
@Component
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload> {
    private final InterviewSessionRepository sessionRepository;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;


    protected EvaluateStreamConsumer(RedisService redisService,
                                     InterviewSessionRepository interviewSessionRepository,
                                     AnswerEvaluationService evaluationService,
                                      InterviewPersistenceService persistenceService,
                                      ObjectMapper objectMapper,
                                     LlmProviderRegistry llmProviderRegistry
    ) {
        super(redisService);
        this.sessionRepository = interviewSessionRepository;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
    }

    @Override
    protected String taskDisplayName() {
        return "";
    }

    @Override
    protected String streamKey() {
        return "";
    }

    @Override
    protected String groupName() {
        return "";
    }

    @Override
    protected String consumerPrefix() {
        return "";
    }

    @Override
    protected String threadName() {
        return "";
    }

    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        return null;
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "";
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) {

    }

    @Override
    protected void processBusiness(EvaluatePayload payload) {

    }

    @Override
    protected void markCompleted(EvaluatePayload payload) {

    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {

    }

    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {

    }

    record EvaluatePayload(String sessionId) {}

}
