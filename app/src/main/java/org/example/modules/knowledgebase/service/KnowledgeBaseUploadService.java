package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.FileHashService;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.file.FileValidationService;
import org.example.modules.knowledgebase.listener.VectorizeStreamProducer;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库上传服务。
 * 处理知识库上传、解析与异步向量化任务投递。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {
    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;
    /**
     * 上传知识库文件
     *
     * @param file 知识库文件
     * @param name 知识库名称（可选，如果为空则从文件名提取）
     * @param category 分类（可选）
     * @return 上传结果和存储信息（包含duplicate字段，表示是否为重复上传）
     */
    public Map<String,Object> upload(MultipartFile file, String name, String category) {
        // 1. 先做基础文件校验，避免无效文件进入后续流程。
        fileValidationService.validateKnowledgeBaseFile(file);

        // 2. 检测并校验文件内容类型，防止扩展名伪造。
        String detectedContentType = parseService.detectContentType(file);
        validateContentType(detectedContentType, file.getOriginalFilename());

        // 3. 计算文件哈希用于去重判断。
        String fileHash = fileHashService.calculateHash(file);
        if (knowledgeBaseRepository.existsByFileHash(fileHash)) {
            KnowledgeBaseEntity duplicateKnowledgeBase =
                    persistenceService.handleDuplicateKnowledgeBase(fileHash);
            return Map.of(
                    "duplicate", true,
                    "knowledgeBaseId", duplicateKnowledgeBase.getId(),
                    "name", duplicateKnowledgeBase.getName(),
                    "category", duplicateKnowledgeBase.getCategory(),
                    "storageKey", duplicateKnowledgeBase.getStorageKey(),
                    "storageUrl", duplicateKnowledgeBase.getStorageUrl(),
                    "vectorStatus", duplicateKnowledgeBase.getVectorStatus(),
                    "fileHash", duplicateKnowledgeBase.getFileHash()
            );
        }

        // 4. 生成存储信息并上传原始文件。
        String finalName = StringUtils.hasText(name) ? name.strip() : file.getOriginalFilename();
        String storageKey = storageService.generateObjectKey("knowledgebase", file.getOriginalFilename());
        String storageUrl = storageService.upload(file, storageKey);

        // 5. 保存知识库元数据，向量化状态初始化为待处理。
        KnowledgeBaseEntity knowledgeBase = persistenceService.save(
                file,
                finalName,
                category,
                storageKey,
                storageUrl,
                fileHash
        );

        // 6. 解析文本内容并发送异步向量化任务。
        String content = parseService.parse(file);
        StreamMessageId messageId = vectorizeStreamProducer.sendVectorizeTask(
                knowledgeBase.getId(),
                content
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("duplicate", false);
        result.put("knowledgeBaseId", knowledgeBase.getId());
        result.put("name", knowledgeBase.getName());
        result.put("category", knowledgeBase.getCategory());
        result.put("storageKey", knowledgeBase.getStorageKey());
        result.put("storageUrl", knowledgeBase.getStorageUrl());
        result.put("vectorStatus", knowledgeBase.getVectorStatus());
        result.put("fileHash", knowledgeBase.getFileHash());
        result.put("messageId", messageId == null ? null : messageId.toString());
        return result;
    }
    /**
     * 验证文件类型
     *
     * @param contentType 检测出的内容类型
     * @param fileName 文件名
     * @return 无返回值
     */
    private void validateContentType(String contentType, String fileName) {
        if (!StringUtils.hasText(contentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库文件类型不能为空");
        }
        if (!StringUtils.hasText(fileName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库文件名不能为空");
        }
    }

    /**
     * 重新向量化知识库（手动重试）
     * 从 RustFS 重新下载文件并发送向量化任务
     *
     * @param kbId 知识库ID
     * @return Redis Stream 消息ID
     */
    public StreamMessageId retryVectorization(Long kbId) {
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不合法");
        }

        // 1. 先加载知识库元数据，确认目标知识库存在。
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "知识库不存在"
                ));

        // 2. 从对象存储重新解析文本内容，确保重试使用最新可下载文件。
        String content = parseService.parse(
                knowledgeBase.getStorageKey(),
                knowledgeBase.getOriginalFilename()
        );

        // 3. 将解析结果重新投递到 Redis Stream，交给异步消费者处理。
        return vectorizeStreamProducer.sendVectorizeTask(kbId, content);
    }
}
