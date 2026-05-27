package org.example.modules.knowledgebase.service;

import jakarta.transaction.TransactionScoped;
import kotlin.jvm.internal.SerializedIr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库计数服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseCountService {
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 批量更新知识库提问计数（使用单条 SQL 批量更新）
     * 每个知识库的 questionCount +1，表示该知识库参与回答的次数
     *
     * @param knowledgeBaseIds 知识库ID列表
     */
    @Transactional
    public void updateQuestionCounts(List<Long> knowledgeBaseIds) {
        // 空列表直接跳过，避免无意义的数据库更新。
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            log.debug("知识库提问计数更新跳过：knowledgeBaseIds 为空");
            return;
        }

        // 过滤空值、非法ID和重复ID，确保批量更新参数有效。
        List<Long> validIds = knowledgeBaseIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            log.warn("知识库提问计数更新跳过：没有合法的知识库ID，originalIds={}", knowledgeBaseIds);
            return;
        }

        // 批量更新前先校验所有知识库都存在，避免部分更新导致计数不一致。
        List<KnowledgeBaseEntity> existingKnowledgeBases = knowledgeBaseRepository.findAllById(validIds);
        if (existingKnowledgeBases.size() != validIds.size()) {
            Set<Long> existingIds = existingKnowledgeBases.stream()
                    .map(KnowledgeBaseEntity::getId)
                    .collect(Collectors.toSet());
            List<Long> missingIds = validIds.stream()
                    .filter(id -> !existingIds.contains(id))
                    .toList();
            log.warn(
                    "知识库提问计数更新失败：存在不存在的知识库ID，knowledgeBaseIds={}, missingIds={}",
                    validIds,
                    missingIds
            );
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                    "部分知识库不存在，缺失ID: " + missingIds
            );
        }

        // 使用仓库层单条 SQL 批量递增提问次数，提高更新效率。
        int updatedRows = knowledgeBaseRepository.incrementQuestionCountBatch(validIds);
        log.info(
                "知识库提问计数更新完成：requestSize={}, validSize={}, updatedRows={}, knowledgeBaseIds={}",
                knowledgeBaseIds.size(),
                validIds.size(),
                updatedRows,
                validIds
        );
    }
}
