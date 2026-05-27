package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.config.KnowledgeBaseQueryProperties;
import org.example.infrastructure.mapper.KnowledgeBaseMapper;
import org.example.infrastructure.mapper.RagChatMapper;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.example.modules.knowledgebase.repository.RagChatMessageRepository;
import org.example.modules.knowledgebase.repository.RagChatSessionRepository;
import org.springframework.stereotype.Service;

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
    private final KnowledgeBaseQueryProperties knowledgeBaseQueryProperties;

}
