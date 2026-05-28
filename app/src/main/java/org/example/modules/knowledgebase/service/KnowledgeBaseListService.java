package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.mapper.KnowledgeBaseMapper;
import org.example.modules.knowledgebase.model.*;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.example.modules.knowledgebase.repository.RagChatMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;

    /**
     * 获取知识库列表（支持状态过滤和排序）
     *
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段，null 或 "time" 表示按时间排序
     * @return 知识库列表
     */
    public List<KnowledgeBaseListItemDTO> list(VectorStatus vectorStatus, String sortBy) {
        List<KnowledgeBaseEntity> entities = vectorStatus == null
                ? findEntitiesBySort(sortBy)
                : sortEntities(
                        knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus),
                        sortBy
                );
        return knowledgeBaseMapper.toListItemList(entities);
    }

    /**
     * 获取所有知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return list(null, null);
    }

    /**
     * 按向量化状态获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return list(vectorStatus, null);
    }

    /**
     * 根据ID获取知识库详情
     */
    public KnowledgeBaseListItemDTO getKnowledgeBase(Long id) {
        return knowledgeBaseMapper.toListItemDTO(getKnowledgeBaseEntity(id));
    }

    /**
     * 根据ID获取知识库实体（用于删除等操作）
     */
    public KnowledgeBaseEntity getKnowledgeBaseEntity(Long id) {
        validateId(id);
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "知识库不存在"
                ));
    }

    /**
     * 根据ID列表获取知识库名称列表
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseRepository.findAllById(ids).stream()
                .map(KnowledgeBaseEntity::getName)
                .toList();
    }

    // ========== 分类管理 ==========

    /**
     * 获取所有分类
     */
    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategories();
    }

    /**
     * 根据分类获取知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByCategory(String category) {
        List<KnowledgeBaseEntity> entities = StringUtils.hasText(category)
                ? knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc(category.strip())
                : knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc();
        return knowledgeBaseMapper.toListItemList(entities);
    }

    /**
     * 更新知识库分类
     */
    @Transactional
    public KnowledgeBaseEntity updateCategory(Long id, String category) {
        KnowledgeBaseEntity knowledgeBase = getKnowledgeBaseEntity(id);
        knowledgeBase.setCategory(normalizeCategory(category));
        return knowledgeBaseRepository.save(knowledgeBase);
    }

    /**
     * 按关键词搜索知识库
     */
    public List<KnowledgeBaseListItemDTO> searchKnowledgeBases(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemList(
                knowledgeBaseRepository.searchByKeyword(keyword.strip())
        );
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesBySort(String sortBy) {
        return list(null, sortBy);
    }

    // ========== 统计功能 ==========

    /**
     * 获取知识库统计信息
     * 总提问次数从用户消息数统计，确保多知识库提问只算一次
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        long totalCount = knowledgeBaseRepository.count();
        long totalQuestionCount = ragChatMessageRepository.countByType(
                RagChatMessageEntity.MessageType.USER
        );
        long totalAccessCount = knowledgeBaseRepository.sumAccessCount();
        long completedCount = knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED);
        long processingCount = knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING);
        return new KnowledgeBaseStatsDTO(
                totalCount,
                totalQuestionCount,
                totalAccessCount,
                completedCount,
                processingCount
        );
    }

    // ========== 下载功能 ==========

    /**
     * 下载知识库文件
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity knowledgeBase = getEntityForDownload(id);
        return fileStorageService.download(knowledgeBase.getStorageKey());
    }

    /**
     * 获取知识库文件信息（用于下载）
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        KnowledgeBaseEntity knowledgeBase = getKnowledgeBaseEntity(id);
        if (!StringUtils.hasText(knowledgeBase.getStorageKey())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库文件不存在");
        }
        return knowledgeBase;
    }

    private List<KnowledgeBaseEntity> findEntitiesBySort(String sortBy) {
        return switch (normalizeSortBy(sortBy)) {
            case "size" -> knowledgeBaseRepository.findAllByOrderByFileSizeDesc();
            case "access" -> knowledgeBaseRepository.findAllByOrderByAccessCountDesc();
            case "question" -> knowledgeBaseRepository.findAllByOrderByQuestionCountDesc();
            default -> knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();
        };
    }

    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        Comparator<KnowledgeBaseEntity> comparator = switch (normalizeSortBy(sortBy)) {
            case "size" -> Comparator.comparing(
                    KnowledgeBaseEntity::getFileSize,
                    Comparator.nullsLast(Long::compareTo)
            );
            case "access" -> Comparator.comparing(
                    KnowledgeBaseEntity::getAccessCount,
                    Comparator.nullsLast(Integer::compareTo)
            );
            case "question" -> Comparator.comparing(
                    KnowledgeBaseEntity::getQuestionCount,
                    Comparator.nullsLast(Integer::compareTo)
            );
            default -> Comparator.comparing(
                    KnowledgeBaseEntity::getUploadedAt,
                    Comparator.nullsLast(java.time.LocalDateTime::compareTo)
            );
        };
        return entities.stream().sorted(comparator.reversed()).toList();
    }

    private String normalizeCategory(String category) {
        return StringUtils.hasText(category) ? category.strip() : null;
    }

    private String normalizeSortBy(String sortBy) {
        return StringUtils.hasText(sortBy) ? sortBy.strip().toLowerCase() : "time";
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不合法");
        }
    }

    private int toInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
