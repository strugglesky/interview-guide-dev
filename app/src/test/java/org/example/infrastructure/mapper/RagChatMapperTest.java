package org.example.infrastructure.mapper;

import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import org.example.modules.knowledgebase.model.RagChatDTO;
import org.example.modules.knowledgebase.model.RagChatMessageEntity;
import org.example.modules.knowledgebase.model.RagChatSessionEntity;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG聊天映射器测试")
class RagChatMapperTest {

    private final RagChatMapper ragChatMapper = Mappers.getMapper(RagChatMapper.class);

    @Nested
    @DisplayName("会话映射")
    class SessionMappingTests {

        @Test
        @DisplayName("应将会话实体转换为会话DTO并提取知识库ID列表")
        void shouldMapSessionEntityToSessionDto() {
            RagChatSessionEntity session = buildSession(1L, "Java会话", false, 2);
            session.setKnowledgeBases(new LinkedHashSet<>(List.of(
                    buildKnowledgeBase(11L, "Java基础"),
                    buildKnowledgeBase(12L, "Spring实战")
            )));

            // 这里重点验证默认字段映射和 getKnowledgeBaseIds() 提取逻辑是否生效。
            RagChatDTO.SessionDTO result = ragChatMapper.toSessionDTO(session);

            assertThat(result.id()).isEqualTo(1L);
            assertThat(result.title()).isEqualTo("Java会话");
            assertThat(result.knowledgeBaseIds()).containsExactly(11L, 12L);
            assertThat(result.createdAt()).isEqualTo(session.getCreatedAt());
        }

        @Test
        @DisplayName("应将会话实体转换为会话列表项DTO并提取知识库名称")
        void shouldMapSessionEntityToSessionListItemDto() {
            RagChatSessionEntity session = buildSession(2L, "后端面试", true, 3);
            session.setKnowledgeBases(new LinkedHashSet<>(List.of(
                    buildKnowledgeBase(21L, "JVM"),
                    buildKnowledgeBase(22L, "MySQL")
            )));

            // 会话列表项除了普通字段，还依赖知识库名称提取和置顶状态映射。
            RagChatDTO.SessionListItemDTO result = ragChatMapper.toSessionListItemDTO(session);

            assertThat(result.id()).isEqualTo(2L);
            assertThat(result.title()).isEqualTo("后端面试");
            assertThat(result.messageCount()).isEqualTo(3);
            assertThat(result.knowledgeBaseNames()).containsExactly("JVM", "MySQL");
            assertThat(result.updatedAt()).isEqualTo(session.getUpdatedAt());
            assertThat(result.isPinned()).isTrue();
        }

        @Test
        @DisplayName("会话列表项映射时isPinned为null应返回false")
        void shouldDefaultIsPinnedToFalseWhenNull() {
            RagChatSessionEntity session = buildSession(3L, "默认置顶", null, 0);
            session.setKnowledgeBases(new LinkedHashSet<>(List.of(buildKnowledgeBase(31L, "Redis"))));

            // 这里验证 mapper 中的默认值处理，而不是实体 @PostLoad 行为。
            RagChatDTO.SessionListItemDTO result = ragChatMapper.toSessionListItemDTO(session);

            assertThat(result.isPinned()).isFalse();
        }
    }

    @Nested
    @DisplayName("消息映射")
    class MessageMappingTests {

        @Test
        @DisplayName("应将消息实体转换为消息DTO并输出小写类型")
        void shouldMapMessageEntityToMessageDto() {
            RagChatMessageEntity message = buildMessage(
                    101L,
                    RagChatMessageEntity.MessageType.ASSISTANT,
                    "这是回答",
                    1
            );

            // type 字段不是直接取 enum，而是依赖实体的 getTypeString()。
            RagChatDTO.MessageDTO result = ragChatMapper.toMessageDTO(message);

            assertThat(result.id()).isEqualTo(101L);
            assertThat(result.type()).isEqualTo("assistant");
            assertThat(result.content()).isEqualTo("这是回答");
            assertThat(result.createdAt()).isEqualTo(message.getCreatedAt());
        }

        @Test
        @DisplayName("应将消息实体列表转换为消息DTO列表")
        void shouldMapMessageEntityListToMessageDtoList() {
            List<RagChatMessageEntity> messages = List.of(
                    buildMessage(201L, RagChatMessageEntity.MessageType.USER, "问题1", 1),
                    buildMessage(202L, RagChatMessageEntity.MessageType.ASSISTANT, "回答1", 2)
            );

            RagChatDTO.MessageDTO first = ragChatMapper.toMessageDTOList(messages).getFirst();
            RagChatDTO.MessageDTO second = ragChatMapper.toMessageDTOList(messages).get(1);

            assertThat(first.type()).isEqualTo("user");
            assertThat(first.content()).isEqualTo("问题1");
            assertThat(second.type()).isEqualTo("assistant");
            assertThat(second.content()).isEqualTo("回答1");
        }
    }

    @Nested
    @DisplayName("详情组装")
    class SessionDetailTests {

        @Test
        @DisplayName("应将会话实体消息列表和知识库列表组装为详情DTO")
        void shouldAssembleSessionDetailDto() {
            RagChatSessionEntity session = buildSession(4L, "详情会话", false, 2);
            List<RagChatMessageEntity> messages = List.of(
                    buildMessage(301L, RagChatMessageEntity.MessageType.USER, "什么是JVM", 1),
                    buildMessage(302L, RagChatMessageEntity.MessageType.ASSISTANT, "JVM是虚拟机", 2)
            );
            List<KnowledgeBaseListItemDTO> knowledgeBases = List.of(
                    buildKnowledgeBaseDto(41L, "JVM手册"),
                    buildKnowledgeBaseDto(42L, "GC指南")
            );

            // 详情DTO由默认方法手动组装，需要同时验证消息映射和知识库列表透传。
            RagChatDTO.SessionDetailDTO result = ragChatMapper.toSessionDetailDTO(
                    session,
                    messages,
                    knowledgeBases
            );

            assertThat(result.id()).isEqualTo(4L);
            assertThat(result.title()).isEqualTo("详情会话");
            assertThat(result.knowledgeBases()).containsExactlyElementsOf(knowledgeBases);
            assertThat(result.messages()).hasSize(2);
            assertThat(result.messages().getFirst().type()).isEqualTo("user");
            assertThat(result.messages().get(1).type()).isEqualTo("assistant");
            assertThat(result.createdAt()).isEqualTo(session.getCreatedAt());
            assertThat(result.updatedAt()).isEqualTo(session.getUpdatedAt());
        }
    }

    private RagChatSessionEntity buildSession(
            Long id,
            String title,
            Boolean isPinned,
            Integer messageCount
    ) {
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setId(id);
        session.setTitle(title);
        session.setIsPinned(isPinned);
        session.setMessageCount(messageCount);
        session.setCreatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 11, 0));
        return session;
    }

    private RagChatMessageEntity buildMessage(
            Long id,
            RagChatMessageEntity.MessageType type,
            String content,
            int hourOffset
    ) {
        RagChatMessageEntity message = new RagChatMessageEntity();
        message.setId(id);
        message.setType(type);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 6, 1, 12, 0).plusHours(hourOffset));
        message.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 12, 30).plusHours(hourOffset));
        message.setMessageOrder(hourOffset);
        message.setCompleted(true);
        return message;
    }

    private KnowledgeBaseEntity buildKnowledgeBase(Long id, String name) {
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setId(id);
        knowledgeBase.setName(name);
        knowledgeBase.setCategory("后端");
        knowledgeBase.setOriginalFilename(name + ".pdf");
        knowledgeBase.setFileSize(1024L);
        knowledgeBase.setContentType("application/pdf");
        knowledgeBase.setUploadedAt(LocalDateTime.of(2026, 6, 1, 9, 0));
        knowledgeBase.setLastAccessedAt(LocalDateTime.of(2026, 6, 1, 9, 30));
        knowledgeBase.setAccessCount(2);
        knowledgeBase.setQuestionCount(3);
        knowledgeBase.setVectorStatus(VectorStatus.COMPLETED);
        knowledgeBase.setChunkCount(6);
        return knowledgeBase;
    }

    private KnowledgeBaseListItemDTO buildKnowledgeBaseDto(Long id, String name) {
        return new KnowledgeBaseListItemDTO(
                id,
                name,
                "后端",
                name + ".pdf",
                2048L,
                "application/pdf",
                LocalDateTime.of(2026, 6, 1, 8, 0),
                LocalDateTime.of(2026, 6, 1, 8, 30),
                4,
                5,
                VectorStatus.COMPLETED,
                null,
                10
        );
    }
}
