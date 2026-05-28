package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.infrastructure.file.FileHashService;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.file.FileValidationService;
import org.example.modules.knowledgebase.listener.VectorizeStreamProducer;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseQueryService {
//    private final ChatClient chatClient;
    private final KnowledgeBaseVectorService knowledgeBaseVectorService;
    private final KnowledgeBaseListService knowledgeBaseListService;

}
