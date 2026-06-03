package org.example.infrastructure.mapper;

import org.example.modules.knowledgebase.model.*;
import org.mapstruct.*;

import java.util.Collection;
import java.util.List;

/**
 * RAG聊天相关实体到DTO的映射器
 * 使用MapStruct自动生成转换代码
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = KnowledgeBaseMapper.class
)
public interface RagChatMapper {
    /**
     * 将会话实体转换为会话DTO
     */
    @Mapping(target = "knowledgeBaseIds", source = "session", qualifiedByName = "extractKnowledgeBaseIds")
    RagChatDTO.SessionDTO toSessionDTO(RagChatSessionEntity session);

    /**
     * 从会话实体中提取知识库ID列表
     */
    @Named("extractKnowledgeBaseIds")
    default List<Long> extractKnowledgeBaseIds(RagChatSessionEntity session) {
        return session.getKnowledgeBaseIds();
    }

    /**
     * 将消息实体转换为消息DTO
     */
    @Mapping(target = "type", source = "message", qualifiedByName = "getTypeString")
    RagChatDTO.MessageDTO toMessageDTO(RagChatMessageEntity message);

    /**
     * 从消息实体中提取消息类型
     */
    @Named("getTypeString")
    default String extractMessageType(RagChatMessageEntity message) {
        return message.getTypeString();
    }

    /**
     * 将消息实体列表转换为消息DTO列表
     */
    List<RagChatDTO.MessageDTO> toMessageDTOList(List<RagChatMessageEntity> messages);

    /**
     * 将会话实体转换为会话列表项DTO
     * 需要特殊处理：提取知识库名称列表和处理isPinned的null值
     */
    /**
     * 将会话实体转换为会话列表项DTO
     * 需要特殊处理：提取知识库名称列表和处理isPinned的null值
     */
    @Mapping(target = "knowledgeBaseNames", source = "session.knowledgeBases", qualifiedByName = "extractKnowledgeBaseNames")
    @Mapping(target = "isPinned", source = "session", qualifiedByName = "getIsPinnedWithDefault")
    RagChatDTO.SessionListItemDTO toSessionListItemDTO(RagChatSessionEntity session);

    /**
     * 处理isPinned的null值，默认为false
     */
    @Named("getIsPinnedWithDefault")
    default Boolean getIsPinnedWithDefault(RagChatSessionEntity session) {
        return session.getIsPinned() != null ? session.getIsPinned() : false;
    }

    /**
     * 将知识库实体集合转换为知识库名称列表
     * 支持 Set 和 List 类型
     */
    @Named("extractKnowledgeBaseNames")
    default List<String> extractKnowledgeBaseNames(Collection<KnowledgeBaseEntity> knowledgeBases) {
        return knowledgeBases.stream()
                .map(KnowledgeBaseEntity::getName)
                .toList();
    }

    /**
     * 将会话实体和消息列表转换为会话详情DTO
     * 注意：这个方法需要手动实现，因为需要组合多个数据源
     * 知识库列表的转换在Service层使用KnowledgeBaseMapper完成
     */
    default RagChatDTO.SessionDetailDTO toSessionDetailDTO(
            RagChatSessionEntity session,
            List<RagChatMessageEntity> messages,
            List<KnowledgeBaseListItemDTO> knowledgeBases) {
        List<RagChatDTO.MessageDTO> messageDTOs = toMessageDTOList(messages);

        return new RagChatDTO.SessionDetailDTO(
                session.getId(),
                session.getTitle(),
                knowledgeBases,
                messageDTOs,
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

}
