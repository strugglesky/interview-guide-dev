package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库持久化服务
 * 处理所有需要事务的数据库操作
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBasePersistenceService {
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 根据文件元数据创建并保存知识库实体。
     *
     * @param file       上传文件
     * @param name       知识库名称
     * @param category   知识库分类
     * @param storageKey 文件存储 Key
     * @param storageUrl 文件访问地址
     * @param fileHash   文件哈希
     * @return 保存后的知识库实体
     */
    @Transactional
    public KnowledgeBaseEntity save(MultipartFile file, String name, String category, String storageKey, String storageUrl, String fileHash) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库名称不能为空");
        }
        if (!StringUtils.hasText(storageKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库文件存储路径不能为空");
        }
        if (!StringUtils.hasText(storageUrl)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库文件访问地址不能为空");
        }
        validateFileHash(fileHash);

        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setName(name.strip());
        knowledgeBase.setCategory(StringUtils.hasText(category) ? category.strip() : null);
        knowledgeBase.setOriginalFilename(file.getOriginalFilename());
        knowledgeBase.setFileSize(file.getSize());
        knowledgeBase.setContentType(file.getContentType());
        knowledgeBase.setStorageKey(storageKey);
        knowledgeBase.setStorageUrl(storageUrl);
        knowledgeBase.setFileHash(fileHash);
        knowledgeBase.setVectorStatus(VectorStatus.PENDING);
        knowledgeBase.setVectorError(null);
        knowledgeBase.setChunkCount(0);
        return knowledgeBaseRepository.save(knowledgeBase);
    }

    /**
     * 根据 ID 获取知识库实体。
     *
     * @param id 知识库 ID
     * @return 知识库实体
     */
    @Transactional(readOnly = true)
    public KnowledgeBaseEntity getById(Long id) {
        validateId(id);
        return knowledgeBaseRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }

    /**
     * 根据文件哈希获取知识库实体。
     *
     * @param fileHash 文件哈希
     * @return 知识库实体
     */
    @Transactional(readOnly = true)
    public KnowledgeBaseEntity getByFileHash(String fileHash) {
        validateFileHash(fileHash);
        return knowledgeBaseRepository.findByFileHash(fileHash).orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }

    /**
     * 检查文件哈希是否已存在。
     *
     * @param fileHash 文件哈希
     * @return 已存在时返回 true
     */
    @Transactional(readOnly = true)
    public boolean existsByFileHash(String fileHash) {
        validateFileHash(fileHash);
        return knowledgeBaseRepository.existsByFileHash(fileHash);
    }

    /**
     * 删除指定知识库。
     *
     * @param id 知识库 ID
     * @return 删除成功时返回 true
     */
    @Transactional
    public boolean deleteById(Long id) {
        KnowledgeBaseEntity knowledgeBase = getById(id);
        knowledgeBaseRepository.delete(knowledgeBase);
        return true;
    }

    /**
     * 增加知识库访问次数。
     *
     * @param id 知识库 ID
     * @return 更新后的知识库实体
     */
    @Transactional
    public KnowledgeBaseEntity incrementAccessCount(Long id) {
        KnowledgeBaseEntity knowledgeBase = getById(id);
        knowledgeBase.incrementAccessCount();
        return knowledgeBaseRepository.save(knowledgeBase);
    }

    /**
     * 处理重复知识库文件。
     *
     * @param fileHash 文件哈希
     * @return 已存在的知识库实体
     */
    @Transactional
    public KnowledgeBaseEntity handleDuplicateKnowledgeBase(String fileHash) {
        KnowledgeBaseEntity knowledgeBase = getByFileHash(fileHash);
        knowledgeBase.incrementAccessCount();
        return knowledgeBaseRepository.save(knowledgeBase);
    }

    /**
     * 批量增加知识库提问次数。
     *
     * @param ids 知识库 ID 列表
     * @return 更新的记录数
     */
    @Transactional
    public int incrementQuestionCountBatch(List<Long> ids) {
        validateIds(ids);
        return knowledgeBaseRepository.incrementQuestionCountBatch(ids);
    }

    /**
     * 更新知识库向量化状态。
     *
     * @param id           知识库 ID
     * @param vectorStatus 向量化状态
     * @param vectorError  向量化错误信息
     * @return 更新后的知识库实体
     */
    @Transactional
    public KnowledgeBaseEntity updateVectorStatus(Long id, VectorStatus vectorStatus, String vectorError) {
        KnowledgeBaseEntity knowledgeBase = getById(id);
        knowledgeBase.setVectorStatus(vectorStatus);
        knowledgeBase.setVectorError(vectorError);
        return knowledgeBaseRepository.save(knowledgeBase);
    }

    /**
     * 更新知识库分块数量。
     *
     * @param id         知识库 ID
     * @param chunkCount 分块数量
     * @return 更新后的知识库实体
     */
    @Transactional
    public KnowledgeBaseEntity updateChunkCount(Long id, Integer chunkCount) {
        KnowledgeBaseEntity knowledgeBase = getById(id);
        knowledgeBase.setChunkCount(chunkCount == null ? 0 : chunkCount);
        return knowledgeBaseRepository.save(knowledgeBase);
    }

    /**
     * 校验知识库实体不能为空。
     *
     * @param knowledgeBase 知识库实体
     * @return 校验通过时返回 true
     */
    private boolean validateKnowledgeBase(KnowledgeBaseEntity knowledgeBase) {
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库实体不能为空");
        }
        return true;
    }

    /**
     * 校验知识库 ID。
     *
     * @param id 知识库 ID
     * @return 校验通过时返回 true
     */
    private boolean validateId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库 ID 不合法");
        }
        return true;
    }

    /**
     * 校验文件哈希。
     *
     * @param fileHash 文件哈希
     * @return 校验通过时返回 true
     */
    private boolean validateFileHash(String fileHash) {
        if (!StringUtils.hasText(fileHash)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件哈希不能为空");
        }
        return true;
    }

    /**
     * 校验知识库 ID 列表。
     *
     * @param ids 知识库 ID 列表
     * @return 校验通过时返回 true
     */
    private boolean validateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库 ID 列表不能为空");
        }
        return true;
    }
}
