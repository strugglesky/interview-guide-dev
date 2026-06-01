package org.example.modules.knowledgebase.service;

import org.example.common.config.KnowledgeBaseQueryProperties;
import org.example.common.exception.BusinessException;
import org.example.infrastructure.mapper.KnowledgeBaseMapper;
import org.example.infrastructure.mapper.RagChatMapper;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import org.example.modules.knowledgebase.model.RagChatDTO;
import org.example.modules.knowledgebase.model.RagChatMessageEntity;
import org.example.modules.knowledgebase.model.RagChatSessionEntity;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.example.modules.knowledgebase.repository.RagChatMessageRepository;
import org.example.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RAG 聊天会话服务测试")
class RagChatSessionServiceTest {

    @Mock
    private RagChatSessionRepository ragChatSessionRepository;

    @Mock
    private RagChatMessageRepository ragChatMessageRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KnowledgeBaseQueryService knowledgeBaseQueryService;

    @Mock
    private RagChatMapper ragChatMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    private KnowledgeBaseQueryProperties queryProperties;
    private RagChatSessionService ragChatSessionService;

    @BeforeEach
    void setUp() {
        queryProperties = new KnowledgeBaseQueryProperties();
        queryProperties.getHistory().setEnabled(true);
        queryProperties.getHistory().setMaxMessages(3);
        ragChatSessionService = new RagChatSessionService(
                ragChatSessionRepository,
                ragChatMessageRepository,
                knowledgeBaseRepository,
                knowledgeBaseQueryService,
                ragChatMapper,
                knowledgeBaseMapper,
                queryProperties
        );
    }

    @Nested
    @DisplayName("创建与查询")
    class CreateAndQuery {

        @Test
        @DisplayName("创建会话时应加载知识库并返回生成后的会话信息")
        void shouldCreateSessionWithGeneratedTitle() {
            List<KnowledgeBaseEntity> knowledgeBases = List.of(buildKnowledgeBase(1L, "Java Guide"));
            when(knowledgeBaseRepository.findAllById(List.of(1L))).thenReturn(knowledgeBases);
            when(ragChatSessionRepository.save(any(RagChatSessionEntity.class)))
                    .thenAnswer(invocation -> savedSession(invocation.getArgument(0), 100L));

            RagChatDTO.SessionDTO result = ragChatSessionService.createSession(
                    new RagChatDTO.CreateSessionRequest(List.of(1L), "   ")
            );

            // 断言返回值，确认服务对外暴露的是会话基础信息。
            assertThat(result.id()).isEqualTo(100L);
            assertThat(result.title()).isEqualTo("Java Guide");
            assertThat(result.knowledgeBaseIds()).containsExactly(1L);

            // 捕获保存实体，确认会话内部确实已关联知识库。
            ArgumentCaptor<RagChatSessionEntity> captor =
                    ArgumentCaptor.forClass(RagChatSessionEntity.class);
            verify(ragChatSessionRepository).save(captor.capture());
            assertThat(captor.getValue().getKnowledgeBaseIds()).containsExactly(1L);
        }

        @Test
        @DisplayName("获取会话详情时应同时返回知识库和消息列表")
        void shouldGetSessionDetailWithKnowledgeBasesAndMessages() {
            RagChatSessionEntity session = buildSession(10L, "Java 会话", false, List.of(1L, 2L));
            List<RagChatMessageEntity> messages = List.of(
                    buildMessage(101L, session, RagChatMessageEntity.MessageType.USER, "你好", 1, true),
                    buildMessage(102L, session, RagChatMessageEntity.MessageType.ASSISTANT, "你好，我来回答", 2, true)
            );
            List<KnowledgeBaseListItemDTO> knowledgeBaseDtos = List.of(
                    buildKnowledgeBaseDto(1L, "Java"),
                    buildKnowledgeBaseDto(2L, "Spring")
            );
            when(ragChatSessionRepository.findByIdWithKnowledgeBases(10L))
                    .thenReturn(Optional.of(session));
            when(ragChatMessageRepository.findBySessionIdOrderByMessageOrderAsc(10L))
                    .thenReturn(messages);
            when(knowledgeBaseMapper.toListItemList(any())).thenReturn(knowledgeBaseDtos);

            RagChatDTO.SessionDetailDTO result = ragChatSessionService.getSessionDetail(10L);

            assertThat(result.id()).isEqualTo(10L);
            assertThat(result.knowledgeBases()).extracting(KnowledgeBaseListItemDTO::name)
                    .containsExactly("Java", "Spring");
            assertThat(result.messages()).extracting(RagChatDTO.MessageDTO::type)
                    .containsExactly("user", "assistant");
            assertThat(result.messages()).extracting(RagChatDTO.MessageDTO::content)
                    .containsExactly("你好", "你好，我来回答");
        }

        @Test
        @DisplayName("获取会话列表时应按会话维度组装列表项")
        void shouldGetSessionListItems() {
            RagChatSessionEntity session = buildSession(11L, "列表会话", true, List.of(2L, 1L));
            when(ragChatSessionRepository.findAllOrderByPinnedAndUpdatedAtDesc())
                    .thenReturn(List.of(session));
            when(ragChatSessionRepository.findByIdWithKnowledgeBases(11L))
                    .thenReturn(Optional.of(session));

            List<RagChatDTO.SessionListItemDTO> result = ragChatSessionService.getSessionList();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().title()).isEqualTo("列表会话");
            assertThat(result.getFirst().messageCount()).isEqualTo(2);
            assertThat(result.getFirst().knowledgeBaseNames()).containsExactly("知识库-1", "知识库-2");
            assertThat(result.getFirst().isPinned()).isTrue();
        }
    }

    @Nested
    @DisplayName("流式消息")
    class StreamMessageLifecycle {

        @Test
        @DisplayName("准备流式消息时应先保存用户消息，再保存助手占位消息")
        void shouldPrepareStreamMessageWithUserAndAssistantPlaceholder() {
            RagChatSessionEntity session = buildSession(20L, "流式会话", false, List.of(1L));
            when(ragChatSessionRepository.findByIdWithKnowledgeBases(20L))
                    .thenReturn(Optional.of(session));
            when(ragChatMessageRepository.findTopBySessionIdOrderByMessageOrderDesc(20L))
                    .thenReturn(Optional.of(buildMessage(200L, session,
                            RagChatMessageEntity.MessageType.ASSISTANT, "旧回答", 4, true)));
            when(ragChatMessageRepository.save(any(RagChatMessageEntity.class)))
                    .thenAnswer(invocation -> savedMessage(invocation.getArgument(0)));

            long messageId = ragChatSessionService.prepareStreamMessage(20L, "  新问题  ");

            // 两次保存分别对应：用户问题 + AI 占位消息。
            ArgumentCaptor<RagChatMessageEntity> captor =
                    ArgumentCaptor.forClass(RagChatMessageEntity.class);
            verify(ragChatMessageRepository, org.mockito.Mockito.times(2)).save(captor.capture());
            List<RagChatMessageEntity> savedMessages = captor.getAllValues();

            assertThat(savedMessages.get(0).getType()).isEqualTo(RagChatMessageEntity.MessageType.USER);
            assertThat(savedMessages.get(0).getContent()).isEqualTo("新问题");
            assertThat(savedMessages.get(0).getMessageOrder()).isEqualTo(5);

            assertThat(savedMessages.get(1).getType()).isEqualTo(
                    RagChatMessageEntity.MessageType.ASSISTANT
            );
            assertThat(savedMessages.get(1).getContent()).isEmpty();
            assertThat(savedMessages.get(1).getCompleted()).isFalse();
            assertThat(savedMessages.get(1).getMessageOrder()).isEqualTo(6);
            assertThat(messageId).isEqualTo(1002L);

            assertThat(session.getMessageCount()).isEqualTo(6);
            verify(ragChatSessionRepository).save(session);
        }

        @Test
        @DisplayName("流式完成后应回填回答内容并标记为已完成")
        void shouldCompleteStreamMessage() {
            RagChatMessageEntity message = buildMessage(
                    301L,
                    buildSession(30L, "完成会话", false, List.of(1L)),
                    RagChatMessageEntity.MessageType.ASSISTANT,
                    "",
                    2,
                    false
            );
            when(ragChatMessageRepository.findById(301L)).thenReturn(Optional.of(message));

            ragChatSessionService.completeStreamMessage(301L, "  最终答案  ");

            assertThat(message.getContent()).isEqualTo("最终答案");
            assertThat(message.getCompleted()).isTrue();
            verify(ragChatMessageRepository).save(message);
        }
    }

    @Nested
    @DisplayName("流式问答")
    class StreamAnswer {

        @Test
        @DisplayName("获取流式回答时应加载历史上下文并过滤当前轮用户问题")
        void shouldPassFilteredHistoryToQueryService() {
            RagChatSessionEntity session = buildSession(40L, "上下文会话", false, List.of(1L, 2L));
            List<RagChatMessageEntity> recentMessages = List.of(
                    buildMessage(403L, session, RagChatMessageEntity.MessageType.USER, "当前问题", 5, true),
                    buildMessage(402L, session, RagChatMessageEntity.MessageType.ASSISTANT, "旧回答", 4, true),
                    buildMessage(401L, session, RagChatMessageEntity.MessageType.USER, "旧问题", 3, true)
            );
            when(ragChatSessionRepository.findByIdWithKnowledgeBases(40L))
                    .thenReturn(Optional.of(session));
            when(ragChatMessageRepository.findRecentCompletedBySessionId(eq(40L), any()))
                    .thenReturn(recentMessages);
            when(knowledgeBaseQueryService.answerQuestionStream(eq(List.of(1L, 2L)),
                    eq("当前问题"), any())).thenReturn(Flux.just("片段A", "片段B"));

            List<String> result = ragChatSessionService.getStreamAnswer(40L, "  当前问题  ")
                    .collectList()
                    .block();

            assertThat(result).containsExactly("片段A", "片段B");

            // 历史消息应只保留前两条旧消息，并按时间正序传给查询服务。
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(knowledgeBaseQueryService).answerQuestionStream(
                    eq(List.of(1L, 2L)),
                    eq("当前问题"),
                    historyCaptor.capture()
            );
            assertThat(historyCaptor.getValue()).hasSize(2);
            assertThat(historyCaptor.getValue().get(0)).isInstanceOf(UserMessage.class);
            assertThat(historyCaptor.getValue().get(0).getText()).isEqualTo("旧问题");
            assertThat(historyCaptor.getValue().get(1)).isInstanceOf(AssistantMessage.class);
            assertThat(historyCaptor.getValue().get(1).getText()).isEqualTo("旧回答");
        }

        @Test
        @DisplayName("历史上下文关闭时应向查询服务传空历史")
        void shouldPassEmptyHistoryWhenHistoryDisabled() {
            queryProperties.getHistory().setEnabled(false);
            RagChatSessionEntity session = buildSession(41L, "无历史", false, List.of(9L));
            when(ragChatSessionRepository.findByIdWithKnowledgeBases(41L))
                    .thenReturn(Optional.of(session));
            when(knowledgeBaseQueryService.answerQuestionStream(eq(List.of(9L)),
                    eq("直接提问"), any())).thenReturn(Flux.just("ok"));

            ragChatSessionService.getStreamAnswer(41L, "直接提问").collectList().block();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(knowledgeBaseQueryService).answerQuestionStream(
                    eq(List.of(9L)),
                    eq("直接提问"),
                    historyCaptor.capture()
            );
            assertThat(historyCaptor.getValue()).isEmpty();
            verify(ragChatMessageRepository, never()).findRecentCompletedBySessionId(eq(41L), any());
        }
    }

    @Nested
    @DisplayName("更新操作")
    class UpdateOperations {

        @Test
        @DisplayName("更新标题时应保存去除首尾空白后的标题")
        void shouldUpdateSessionTitleWithTrimmedValue() {
            RagChatSessionEntity session = buildSession(50L, "旧标题", false, List.of(1L));
            when(ragChatSessionRepository.findById(50L)).thenReturn(Optional.of(session));

            ragChatSessionService.updateSessionTitle(50L, "  新标题  ");

            assertThat(session.getTitle()).isEqualTo("新标题");
            verify(ragChatSessionRepository).save(session);
        }

        @Test
        @DisplayName("切换置顶状态时应将false改为true")
        void shouldToggleSessionPinnedFlag() {
            RagChatSessionEntity session = buildSession(51L, "置顶测试", false, List.of(1L));
            when(ragChatSessionRepository.findById(51L)).thenReturn(Optional.of(session));

            ragChatSessionService.toggleSessionPinned(51L);

            assertThat(session.getIsPinned()).isTrue();
            verify(ragChatSessionRepository).save(session);
        }

        @Test
        @DisplayName("更新知识库关联时应替换为新的知识库集合")
        void shouldReplaceKnowledgeBaseAssociations() {
            RagChatSessionEntity session = buildSession(52L, "关联更新", false, List.of(1L));
            List<KnowledgeBaseEntity> knowledgeBases = List.of(
                    buildKnowledgeBase(2L, "Spring"),
                    buildKnowledgeBase(3L, "Redis")
            );
            when(ragChatSessionRepository.findByIdWithKnowledgeBases(52L))
                    .thenReturn(Optional.of(session));
            when(knowledgeBaseRepository.findAllById(List.of(2L, 3L))).thenReturn(knowledgeBases);

            ragChatSessionService.updateSessionKnowledgeBases(52L, List.of(2L, 3L));

            assertThat(session.getKnowledgeBaseIds()).containsExactly(2L, 3L);
            verify(ragChatSessionRepository).save(session);
        }
    }

    @Nested
    @DisplayName("删除与异常")
    class DeleteAndValidation {

        @Test
        @DisplayName("删除会话时应先加载会话再执行删除")
        void shouldDeleteLoadedSession() {
            RagChatSessionEntity session = buildSession(60L, "删除测试", false, List.of(1L));
            when(ragChatSessionRepository.findById(60L)).thenReturn(Optional.of(session));

            ragChatSessionService.deleteSession(60L);

            verify(ragChatSessionRepository).delete(session);
        }

        @Test
        @DisplayName("知识库缺失时创建会话应抛出业务异常")
        void shouldThrowWhenKnowledgeBaseMissingDuringCreate() {
            when(knowledgeBaseRepository.findAllById(List.of(1L, 2L)))
                    .thenReturn(List.of(buildKnowledgeBase(1L, "Only One")));

            assertThatThrownBy(() -> ragChatSessionService.createSession(
                    new RagChatDTO.CreateSessionRequest(List.of(1L, 2L), null)
            )).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("缺失 ID");

            verify(ragChatSessionRepository, never()).save(any(RagChatSessionEntity.class));
        }
    }

    private RagChatSessionEntity savedSession(RagChatSessionEntity session, Long id) {
        session.setId(id);
        session.setCreatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 10, 5));
        return session;
    }

    private RagChatMessageEntity savedMessage(RagChatMessageEntity message) {
        long id = message.getType() == RagChatMessageEntity.MessageType.USER ? 1001L : 1002L;
        message.setId(id);
        return message;
    }

    private RagChatSessionEntity buildSession(
            Long id,
            String title,
            boolean pinned,
            List<Long> knowledgeBaseIds
    ) {
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setId(id);
        session.setTitle(title);
        session.setIsPinned(pinned);
        session.setCreatedAt(LocalDateTime.of(2026, 6, 1, 9, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 9, 30));
        session.setMessageCount(2);
        session.setKnowledgeBases(toKnowledgeBaseSet(knowledgeBaseIds));
        return session;
    }

    private RagChatMessageEntity buildMessage(
            Long id,
            RagChatSessionEntity session,
            RagChatMessageEntity.MessageType type,
            String content,
            int order,
            boolean completed
    ) {
        RagChatMessageEntity message = new RagChatMessageEntity();
        message.setId(id);
        message.setSession(session);
        message.setType(type);
        message.setContent(content);
        message.setMessageOrder(order);
        message.setCompleted(completed);
        message.setCreatedAt(LocalDateTime.of(2026, 6, 1, 8, 0).plusMinutes(order));
        return message;
    }

    private Set<KnowledgeBaseEntity> toKnowledgeBaseSet(List<Long> knowledgeBaseIds) {
        LinkedHashSet<KnowledgeBaseEntity> knowledgeBases = new LinkedHashSet<>();
        for (Long id : knowledgeBaseIds) {
            knowledgeBases.add(buildKnowledgeBase(id, "知识库-" + id));
        }
        return knowledgeBases;
    }

    private KnowledgeBaseEntity buildKnowledgeBase(Long id, String name) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setCategory("分类-" + name);
        entity.setOriginalFilename(name + ".pdf");
        entity.setFileSize(128L);
        entity.setContentType("application/pdf");
        entity.setUploadedAt(LocalDateTime.of(2026, 6, 1, 7, 0));
        entity.setLastAccessedAt(LocalDateTime.of(2026, 6, 1, 7, 30));
        entity.setAccessCount(3);
        entity.setQuestionCount(5);
        return entity;
    }

    private KnowledgeBaseListItemDTO buildKnowledgeBaseDto(Long id, String name) {
        return new KnowledgeBaseListItemDTO(
                id,
                name,
                "分类-" + name,
                name + ".pdf",
                128L,
                "application/pdf",
                LocalDateTime.of(2026, 6, 1, 7, 0),
                LocalDateTime.of(2026, 6, 1, 7, 30),
                3,
                5,
                null,
                null,
                2
        );
    }
}
