package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import org.example.infrastructure.file.FileHashService;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.file.FileValidationService;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 * 向量化改为异步处理，通过 Redis Stream 实现
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {
    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;
}
