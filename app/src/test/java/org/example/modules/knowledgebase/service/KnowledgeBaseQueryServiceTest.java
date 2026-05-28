package org.example.modules.knowledgebase.service;

import org.example.common.config.KnowledgeBaseQueryProperties;
import org.example.common.exception.BusinessException;
import org.example.modules.knowledgebase.model.QueryRequest;
import org.example.modules.knowledgebase.model.QueryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库查询服务测试")
class KnowledgeBaseQueryServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private KnowledgeBaseVectorService vectorService;

    @Mock
    private KnowledgeBaseListService listService;

    @Mock
    private KnowledgeBaseCountService countService;

    @Nested
    @DisplayName("同步问答")
    class AnswerQuestion {

        @Test
        @DisplayName("单知识库问答应执行检索并返回模型回答")
        void shouldAnswerQuestionForSingleKnowledgeBase() throws IOException {
            KnowledgeBaseQueryService service = createService(false, true, 10);
            ChatClient.ChatClientRequestSpec requestSpec = mockAnswerRequest("这是答案");
            List<Document> documents = List.of(new Document("SpringBoot 介绍"));
            when(vectorService.similaritySearch("SpringBoot介绍", List.of(1L), 12, 0.28))
                    .thenReturn(documents);

            String result = service.answerQuestion(1L, "  SpringBoot介绍  ");

            assertThat(result).isEqualTo("这是答案");
            verify(countService).updateQuestionCounts(List.of(1L));
            verify(vectorService).similaritySearch("SpringBoot介绍", List.of(1L), 12, 0.28);
            verify(requestSpec).system(anyString());
            verify(requestSpec).user(anyString());
        }

        @Test
        @DisplayName("多知识库问答应过滤非法和重复ID后查询")
        void shouldFilterInvalidKnowledgeBaseIdsBeforeSearch() throws IOException {
            KnowledgeBaseQueryService service = createService(false, true, 10);
            mockAnswerRequest("聚合答案");
            List<Long> ids = Arrays.asList(1L, 2L, 2L, null, -1L, 0L);
            when(vectorService.similaritySearch("Java 面试题", List.of(1L, 2L), 12, 0.28))
                    .thenReturn(List.of(new Document("Java 面试题资料")));

            String result = service.answerQuestion(ids, "Java 面试题");

            assertThat(result).isEqualTo("聚合答案");
            verify(countService).updateQuestionCounts(List.of(1L, 2L));
            verify(vectorService).similaritySearch("Java 面试题", List.of(1L, 2L), 12, 0.28);
        }

        @Test
        @DisplayName("无检索结果时应返回统一兜底文案")
        void shouldReturnFallbackWhenNoDocumentFound() throws IOException {
            KnowledgeBaseQueryService service = createService(false, true, 10);
            when(vectorService.similaritySearch("Redis 是什么", List.of(3L), 12, 0.28))
                    .thenReturn(List.of());

            String result = service.answerQuestion(3L, "Redis 是什么");

            assertThat(result).contains("未检索到相关信息");
            verify(countService).updateQuestionCounts(List.of(3L));
            verify(vectorService).similaritySearch("Redis 是什么", List.of(3L), 12, 0.28);
            verify(chatClient, never()).prompt();
        }

        @Test
        @DisplayName("知识库列表为空时应抛出业务异常")
        void shouldThrowWhenKnowledgeBaseIdsEmpty() throws IOException {
            KnowledgeBaseQueryService service = createService(false, true, 10);

            assertThatThrownBy(() -> service.answerQuestion(List.of(), "问题"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("至少选择一个知识库");

            verify(countService, never()).updateQuestionCounts(anyList());
        }
    }

    @Nested
    @DisplayName("完整查询响应")
    class QueryKnowledgeBase {

        @Test
        @DisplayName("查询响应应返回答案和单知识库元信息")
        void shouldReturnQueryResponseForSingleKnowledgeBase() throws IOException {
            KnowledgeBaseQueryService service = createService(false, true, 10);
            mockAnswerRequest("完整回答");
            when(vectorService.similaritySearch("Docker 是什么", List.of(8L), 12, 0.28))
                    .thenReturn(List.of(new Document("Docker 是一种容器技术")));
            when(listService.getKnowledgeBaseNames(List.of(8L))).thenReturn(List.of("Docker 指南"));

            QueryResponse result = service.queryKnowledgeBase(new QueryRequest(8L, "Docker 是什么"));

            assertThat(result.answer()).isEqualTo("完整回答");
            assertThat(result.knowledgeBaseId()).isEqualTo(8L);
            assertThat(result.knowledgeBaseName()).isEqualTo("Docker 指南");
        }

        @Test
        @DisplayName("多知识库查询响应应返回拼接后的知识库名称")
        void shouldJoinKnowledgeBaseNamesForMultiKnowledgeBaseQuery() throws IOException {
            KnowledgeBaseQueryService service = createService(false, true, 10);
            mockAnswerRequest("多库回答");
            when(vectorService.similaritySearch("分布式缓存", List.of(1L, 2L), 12, 0.28))
                    .thenReturn(List.of(new Document("Redis 与缓存")));
            when(listService.getKnowledgeBaseNames(List.of(1L, 2L)))
                    .thenReturn(List.of("Redis 手册", "缓存设计"));

            QueryResponse result = service.queryKnowledgeBase(
                    new QueryRequest(List.of(1L, 2L), "分布式缓存")
            );

            assertThat(result.answer()).isEqualTo("多库回答");
            assertThat(result.knowledgeBaseId()).isNull();
            assertThat(result.knowledgeBaseName()).isEqualTo("Redis 手册, 缓存设计");
        }

        @Test
        @DisplayName("查询请求为空时应抛出业务异常")
        void shouldThrowWhenQueryRequestNull() throws IOException {
            KnowledgeBaseQueryService service = createService(false, true, 10);

            assertThatThrownBy(() -> service.queryKnowledgeBase(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("查询请求不能为空");
        }
    }

    @Nested
    @DisplayName("流式问答")
    class StreamAnswerQuestion {

        @Test
        @DisplayName("带历史消息的流式问答应执行问题重写并只携带最近历史")
        void shouldRewriteAndStreamWithRecentHistoryOnly() throws IOException {
            KnowledgeBaseQueryService service = createService(true, true, 2);

            ChatClient.ChatClientRequestSpec rewriteSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.ChatClientRequestSpec answerSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec rewriteCallSpec = mock(ChatClient.CallResponseSpec.class);
            ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
            when(chatClient.prompt()).thenReturn(rewriteSpec, answerSpec);
            when(rewriteSpec.user(anyString())).thenReturn(rewriteSpec);
            when(rewriteSpec.call()).thenReturn(rewriteCallSpec);
            when(rewriteCallSpec.content()).thenReturn("重写后的问题");
            when(answerSpec.system(anyString())).thenReturn(answerSpec);
            when(answerSpec.messages(anyList())).thenReturn(answerSpec);
            when(answerSpec.user(anyString())).thenReturn(answerSpec);
            when(answerSpec.stream()).thenReturn(streamResponseSpec);
            when(streamResponseSpec.content()).thenReturn(Flux.just("第一段", "第二段"));

            when(vectorService.similaritySearch("重写后的问题", List.of(5L), 12, 0.28))
                    .thenReturn(List.of(new Document("RAG 检索内容")));

            List<Message> history = List.of(
                    buildMessage("第一轮", MessageType.USER),
                    buildMessage("第二轮", MessageType.ASSISTANT),
                    buildMessage("第三轮", MessageType.USER)
            );

            List<String> result = service.answerQuestionStream(List.of(5L), "它的实现原理呢", history)
                    .collectList()
                    .block();

            assertThat(result).containsExactly("第一段", "第二段");
            verify(vectorService).similaritySearch("重写后的问题", List.of(5L), 12, 0.28);

            ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(answerSpec).messages(historyCaptor.capture());
            assertThat(historyCaptor.getValue()).containsExactly(history.get(1), history.get(2));
        }

        @Test
        @DisplayName("流式问答无检索结果时应直接返回兜底文案")
        void shouldReturnFallbackFluxWhenNoResultFound() throws IOException {
            KnowledgeBaseQueryService service = createService(false, true, 10);
            when(vectorService.similaritySearch("什么是向量数据库", List.of(9L), 12, 0.28))
                    .thenReturn(List.of());

            List<String> result = service.answerQuestionStream(List.of(9L), "什么是向量数据库")
                    .collectList()
                    .block();

            assertThat(result).singleElement().asString().contains("未检索到相关信息");
            verify(chatClient, never()).prompt();
        }
    }

    private KnowledgeBaseQueryService createService(
            boolean rewriteEnabled,
            boolean historyEnabled,
            int maxHistoryMessages
    ) throws IOException {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        KnowledgeBaseQueryProperties properties = buildProperties(
                rewriteEnabled,
                historyEnabled,
                maxHistoryMessages
        );
        return new KnowledgeBaseQueryService(
                chatClientBuilder,
                vectorService,
                listService,
                countService,
                properties,
                new DefaultResourceLoader()
        );
    }

    private KnowledgeBaseQueryProperties buildProperties(
            boolean rewriteEnabled,
            boolean historyEnabled,
            int maxHistoryMessages
    ) {
        KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
        properties.getRewrite().setEnabled(rewriteEnabled);
        properties.getHistory().setEnabled(historyEnabled);
        properties.getHistory().setMaxMessages(maxHistoryMessages);
        return properties;
    }

    private ChatClient.ChatClientRequestSpec mockAnswerRequest(String answer) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(answer);
        return requestSpec;
    }

    private Message buildMessage(String text, MessageType messageType) {
        Message message = mock(Message.class);
        when(message.getText()).thenReturn(text);
        when(message.getMessageType()).thenReturn(messageType);
        return message;
    }
}
