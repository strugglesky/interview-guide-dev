package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.infrastructure.file.FileHashService;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.file.FileValidationService;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseQueryService {
    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;


}
