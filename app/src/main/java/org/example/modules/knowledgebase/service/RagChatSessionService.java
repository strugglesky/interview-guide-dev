package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.config.KnowledgeBaseQueryProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 聊天会话服务
 * 提供RAG聊天会话的创建、获取、更新、删除等操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {
    private final RagChatSessionRepository ragChatSessionRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService knowledgeBaseQueryService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQueryProperties queryProperties;

    /**
     * 创建新会话
     */
    @Transactional
    public RagChatDTO.SessionDTO createSession(RagChatDTO.CreateSessionRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "创建会话请求不能为空");
        }
        List<Long> knowledgeBaseIds = normalizeKnowledgeBaseIds(request.knowledgeBaseIds());
        List<KnowledgeBaseEntity> knowledgeBases = loadKnowledgeBases(knowledgeBaseIds);
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setTitle(resolveSessionTitle(request.title(), knowledgeBases));
        session.setKnowledgeBases(new LinkedHashSet<>(knowledgeBases));
        RagChatSessionEntity savedSession = ragChatSessionRepository.save(session);
        return new RagChatDTO.SessionDTO(
                savedSession.getId(),
                generateTitle(knowledgeBases),
                knowledgeBaseIds,
                savedSession.getCreatedAt()
        );
    }

    /**
     * 获取会话列表
     */
    public List<RagChatDTO.SessionListItemDTO> getSessionList() {
        return ragChatSessionRepository.findAllOrderByPinnedAndUpdatedAtDesc().stream()
                .map(session -> toSessionListItem(getSessionWithKnowledgeBases(session.getId())))
                .toList();
    }

    /**
     * 获取会话详情（包含消息）
     * 分两次查询避免笛卡尔积问题
     */
    public RagChatDTO.SessionDetailDTO getSessionDetail(Long sessionId) {
        RagChatSessionEntity session = getSessionWithKnowledgeBases(sessionId);
        List<RagChatMessageEntity> messages = ragChatMessageRepository
                .findBySessionIdOrderByMessageOrderAsc(sessionId);
        return new RagChatDTO.SessionDetailDTO(
                session.getId(),
                session.getTitle(),
                mapKnowledgeBases(session.getKnowledgeBases()),
                mapMessages(messages),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    /**
     * 准备流式消息（保存用户消息，创建 AI 消息占位）
     *
     * @return AI 消息的 ID
     */
    public long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = getSessionWithKnowledgeBases(sessionId);
        String normalizedQuestion = normalizeQuestion(question);
        int nextOrder = getNextMessageOrder(sessionId);
        ragChatMessageRepository.save(createUserMessage(session, normalizedQuestion, nextOrder));
        RagChatMessageEntity assistantMessage = ragChatMessageRepository.save(
                createAssistantPlaceholder(session, nextOrder + 1)
        );
        updateSessionAfterNewMessages(session, nextOrder + 1);
        ragChatSessionRepository.save(session);
        return assistantMessage.getId();
    }

    /**
     * 流式响应完成后更新消息
     */
    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        RagChatMessageEntity message = getMessage(messageId);
        message.setContent(normalizeAssistantContent(content));
        message.setCompleted(true);
        ragChatMessageRepository.save(message);
    }

    /**
     * 获取流式回答（带多轮上下文）
     */
    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        RagChatSessionEntity session = getSessionWithKnowledgeBases(sessionId);
        String normalizedQuestion = normalizeQuestion(question);
        List<Message> history = buildHistory(sessionId, normalizedQuestion);
        return knowledgeBaseQueryService.answerQuestionStream(
                session.getKnowledgeBaseIds(),
                normalizedQuestion,
                history
        );
    }

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = getSession(sessionId);
        session.setTitle(normalizeTitle(title));
        ragChatSessionRepository.save(session);
    }

    /**
     * 切换会话置顶状态
     */
    @Transactional
    public void toggleSessionPinned(Long sessionId) {
        RagChatSessionEntity session = getSession(sessionId);
        session.setIsPinned(!Boolean.TRUE.equals(session.getIsPinned()));
        ragChatSessionRepository.save(session);
    }

    /**
     * 更新会话的知识库关联
     */
    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        RagChatSessionEntity session = getSessionWithKnowledgeBases(sessionId);
        List<Long> validIds = normalizeKnowledgeBaseIds(knowledgeBaseIds);
        session.setKnowledgeBases(new LinkedHashSet<>(loadKnowledgeBases(validIds)));
        ragChatSessionRepository.save(session);
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        ragChatSessionRepository.delete(getSession(sessionId));
    }

    private RagChatDTO.SessionListItemDTO toSessionListItem(RagChatSessionEntity session) {
        return new RagChatDTO.SessionListItemDTO(
                session.getId(),
                session.getTitle(),
                session.getMessageCount(),
                session.getKnowledgeBases().stream()
                        .map(KnowledgeBaseEntity::getName)
                        .sorted()
                        .toList(),
                session.getUpdatedAt(),
                Boolean.TRUE.equals(session.getIsPinned())
        );
    }

    private RagChatDTO.MessageDTO toMessageDTO(RagChatMessageEntity message) {
        return new RagChatDTO.MessageDTO(
                message.getId(),
                message.getTypeString(),
                message.getContent(),
                message.getCreatedAt()
        );
    }

    private RagChatSessionEntity getSession(Long sessionId) {
        validateSessionId(sessionId);
        return ragChatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "RAG session not found"
                ));
    }

    private RagChatSessionEntity getSessionWithKnowledgeBases(Long sessionId) {
        validateSessionId(sessionId);
        return ragChatSessionRepository.findByIdWithKnowledgeBases(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "RAG session not found"
                ));
    }

    private RagChatMessageEntity getMessage(Long messageId) {
        if (messageId == null || messageId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Message ID is invalid");
        }
        return ragChatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Message not found"));
    }

    private List<Long> normalizeKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个知识库");
        }
        List<Long> validIds = knowledgeBaseIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "At least one valid knowledge base is required"
            );
        }
        return validIds;
    }

    private List<KnowledgeBaseEntity> loadKnowledgeBases(List<Long> knowledgeBaseIds) {
        Map<Long, KnowledgeBaseEntity> knowledgeBaseMap = new LinkedHashMap<>();
        knowledgeBaseRepository.findAllById(knowledgeBaseIds)
                .forEach(entity -> knowledgeBaseMap.put(entity.getId(), entity));
        if (knowledgeBaseMap.size() != knowledgeBaseIds.size()) {
            List<Long> missingIds = knowledgeBaseIds.stream()
                    .filter(id -> !knowledgeBaseMap.containsKey(id))
                    .toList();
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                    "部分知识库不存在，缺失 ID: " + missingIds
            );
        }
        return knowledgeBaseIds.stream().map(knowledgeBaseMap::get).toList();
    }

    private String resolveSessionTitle(String title, List<KnowledgeBaseEntity> knowledgeBases) {
        if (StringUtils.hasText(title)) {
            return title.strip();
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName() + " 对话";
        }
        return knowledgeBases.getFirst().getName() + " 等 " + knowledgeBases.size() + " 个知识库对话";
    }

    private List<RagChatDTO.MessageDTO> mapMessages(List<RagChatMessageEntity> messages) {
        return messages.stream().map(this::toMessageDTO).toList();
    }

    private List<KnowledgeBaseListItemDTO> mapKnowledgeBases(Set<KnowledgeBaseEntity> knowledgeBases) {
        List<KnowledgeBaseEntity> knowledgeBaseList = knowledgeBases.stream()
                .sorted(Comparator.comparing(KnowledgeBaseEntity::getId))
                .toList();
        return knowledgeBaseMapper.toListItemList(knowledgeBaseList);
    }

    private String normalizeQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        return question.strip();
    }

    private String normalizeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话标题不能为空");
        }
        return title.strip();
    }

    private String normalizeAssistantContent(String content) {
        return StringUtils.hasText(content) ? content.strip() : "";
    }

    private int getNextMessageOrder(Long sessionId) {
        return ragChatMessageRepository.findTopBySessionIdOrderByMessageOrderDesc(sessionId)
                .map(RagChatMessageEntity::getMessageOrder)
                .orElse(0) + 1;
    }

    private RagChatMessageEntity createUserMessage(
            RagChatSessionEntity session,
            String question,
            int messageOrder
    ) {
        RagChatMessageEntity message = new RagChatMessageEntity();
        message.setSession(session);
        message.setType(RagChatMessageEntity.MessageType.USER);
        message.setContent(question);
        message.setMessageOrder(messageOrder);
        message.setCompleted(true);
        return message;
    }

    private RagChatMessageEntity createAssistantPlaceholder(
            RagChatSessionEntity session,
            int messageOrder
    ) {
        RagChatMessageEntity message = new RagChatMessageEntity();
        message.setSession(session);
        message.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        message.setContent("");
        message.setMessageOrder(messageOrder);
        message.setCompleted(false);
        return message;
    }

    private void updateSessionAfterNewMessages(RagChatSessionEntity session, int latestMessageOrder) {
        session.setMessageCount(latestMessageOrder);
        session.setUpdatedAt(LocalDateTime.now());
    }

    private List<Message> buildHistory(Long sessionId, String question) {
        if (!queryProperties.getHistory().isEnabled()) {
            return List.of();
        }
        return loadHistoryMessages(sessionId);
    }

    private List<RagChatMessageEntity> toAscendingMessages(List<RagChatMessageEntity> messages) {
        List<RagChatMessageEntity> orderedMessages = new ArrayList<>(messages);
        orderedMessages.sort(Comparator.comparing(RagChatMessageEntity::getMessageOrder));
        return orderedMessages;
    }

    private List<RagChatMessageEntity> removeDuplicatedQuestion(
            List<RagChatMessageEntity> messages,
            String question
    ) {
        if (messages.isEmpty()) {
            return messages;
        }
        RagChatMessageEntity lastMessage = messages.getLast();
        boolean duplicated = lastMessage.getType() == RagChatMessageEntity.MessageType.USER
                && question.equals(lastMessage.getContent());
        return duplicated ? messages.subList(0, messages.size() - 1) : messages;
    }

    private Message toAiMessage(RagChatMessageEntity message) {
        return switch (message.getType()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
        };
    }

    private void validateSessionId(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Session ID is invalid");
        }
    }

    /**
     * 加载会话中最近的历史消息作为多轮上下文。
     * 排除当前轮的 user 消息（prepareStreamMessage 中 completed=true 但尚未回答）。
     */
    private List<Message> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() + 1;
        List<RagChatMessageEntity> recent = ragChatMessageRepository
                .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

        if (recent.isEmpty()) {
            return List.of();
        }

        // 查询结果按 messageOrder DESC 排列，最后一条（DESC 首条）是当前轮的 user 消息，排除
        List<RagChatMessageEntity> historyMessages = recent.size() <= 1
                ? List.of()
                : recent.subList(1, recent.size());

        // 反转为正序（时间从早到晚）
        return historyMessages.reversed().stream()
                .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
                        ? (Message) new UserMessage(m.getContent())
                        : (Message) new AssistantMessage(m.getContent()))
                .toList();
    }

    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "新对话";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库对话";
    }

}
