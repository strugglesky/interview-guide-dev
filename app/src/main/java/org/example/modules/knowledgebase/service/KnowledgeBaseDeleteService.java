package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.FileStorageService;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.RagChatSessionEntity;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.example.modules.knowledgebase.repository.RagChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 知识库删除服务
 * 负责知识库的删除操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteService {
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatSessionRepository ragChatSessionRepository;
    private final KnowledgeBaseVectorService knowledgeBaseVectorService;
    private final FileStorageService fileStorageService;

    /**
     * 删除知识库
     * 包括：RAG会话关联、向量数据、RustFS文件、数据库记录
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(Long id) {
        //1.获取知识库信息
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不合法");
        }
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "知识库不存在"
                ));
        String storageKey = knowledgeBase.getStorageKey();

        //2.删除RAG会话关联
        List<RagChatSessionEntity> sessions = ragChatSessionRepository.findByKnowledgeBaseIds(List.of(id));
        sessions.forEach(session -> session.getKnowledgeBases()
                .removeIf(item -> id.equals(item.getId())));
        if (!sessions.isEmpty()) {
            ragChatSessionRepository.saveAll(sessions);
        }

        //3.删除向量数据
        knowledgeBaseVectorService.deleteByKnowledgeBaseId(id);

        //4.删除RustFS中的文件
        if (StringUtils.hasText(storageKey)) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileStorageService.delete(storageKey);
                    log.info("知识库文件删除完成: kbId={}, storageKey={}", id, storageKey);
                }
            });
        }

        //5.删除数据库记录
        knowledgeBaseRepository.delete(knowledgeBase);
        log.info("知识库删除完成: kbId={}", id);
    }
}
