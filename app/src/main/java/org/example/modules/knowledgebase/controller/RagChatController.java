package org.example.modules.knowledgebase.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.modules.knowledgebase.service.KnowledgeBaseQueryService;
import org.example.modules.knowledgebase.service.RagChatSessionService;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 聊天控制器
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
public class RagChatController {
    private final KnowledgeBaseQueryService queryService;
    private final RagChatSessionService sessionService;
}
