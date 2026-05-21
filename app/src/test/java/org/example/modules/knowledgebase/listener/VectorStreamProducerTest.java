package org.example.modules.knowledgebase.listener;

import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.redis.RedisService;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("向量化任务生产者测试")
class VectorStreamProducerTest {

    /**
     * @return 测试方法无返回值
     */
    @Test
    @DisplayName("正常发送向量化任务")
    void shouldSendVectorizeTaskSuccessfully() {
        StubRedisService redisService = new StubRedisService();
        KnowledgeBaseRepository repository = repositoryStub(true);
        VectorizeStreamProducer producer = new VectorizeStreamProducer(redisService, repository);

        StreamMessageId messageId = producer.sendVectorizeTask(1L, "  hello world  ");

        assertNotNull(messageId);
        assertTrue(redisService.invoked);
        assertEquals("stream:knowledgebase:vectorize", redisService.lastStreamKey);
        assertEquals(1L, redisService.lastPayload.get("kbId"));
        assertEquals("hello world", redisService.lastPayload.get("content"));
        assertEquals("knowledgeBase:1", redisService.lastPayload.get("businessKey"));
        assertEquals("vectorizeStreamProducer", redisService.lastPayload.get("producer"));
        assertEquals("KNOWLEDGE_BASE_VECTORIZATION", redisService.lastPayload.get("taskType"));
    }

    /**
     * @return 测试方法无返回值
     */
    @Test
    @DisplayName("知识库不存在时抛出业务异常")
    void shouldThrowWhenKnowledgeBaseNotFound() {
        StubRedisService redisService = new StubRedisService();
        KnowledgeBaseRepository repository = repositoryStub(false);
        VectorizeStreamProducer producer = new VectorizeStreamProducer(redisService, repository);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> producer.sendVectorizeTask(1L)
        );

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode(), exception.getCode());
        assertFalse(redisService.invoked);
    }

    /**
     * @return 测试方法无返回值
     */
    @Test
    @DisplayName("非法知识库 ID 时抛出业务异常")
    void shouldThrowWhenKnowledgeBaseIdInvalid() {
        StubRedisService redisService = new StubRedisService();
        KnowledgeBaseRepository repository = repositoryStub(true);
        VectorizeStreamProducer producer = new VectorizeStreamProducer(redisService, repository);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> producer.sendVectorizeTask(0L)
        );

        assertEquals(ErrorCode.BAD_REQUEST.getCode(), exception.getCode());
        assertFalse(redisService.invoked);
    }

    /**
     * @param exists 是否模拟知识库存在
     * @return KnowledgeBaseRepository 代理对象
     */
    private KnowledgeBaseRepository repositoryStub(boolean exists) {
        return (KnowledgeBaseRepository) Proxy.newProxyInstance(
                KnowledgeBaseRepository.class.getClassLoader(),
                new Class<?>[]{KnowledgeBaseRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("existsById".equals(name)) {
                        return exists;
                    }
                    if ("findById".equals(name)) {
                        if (!exists) {
                            return Optional.empty();
                        }
                        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
                        entity.setId((Long) args[0]);
                        entity.setName("knowledge-base");
                        entity.setOriginalFilename("demo.pdf");
                        entity.setStorageKey("storage-key");
                        entity.setContentType("application/pdf");
                        entity.setVectorStatus(VectorStatus.COMPLETED);
                        return Optional.of(entity);
                    }
                    if ("toString".equals(name)) {
                        return "KnowledgeBaseRepositoryStub";
                    }
                    throw new UnsupportedOperationException("Unsupported method: " + name);
                }
        );
    }

    private static final class StubRedisService extends RedisService {
        private boolean invoked;
        private String lastStreamKey;
        private Map<String, Object> lastPayload = new HashMap<>();

        private StubRedisService() {
            super(null);
        }

        /**
         * @param streamKey Stream Key
         * @param message   消息载荷
         * @return 模拟的消息 ID
         */
        @Override
        public StreamMessageId addStreamMessage(String streamKey, Map<String, Object> message) {
            invoked = true;
            lastStreamKey = streamKey;
            lastPayload = new HashMap<>(message);
            return StreamMessageId.NEWEST;
        }
    }
}
