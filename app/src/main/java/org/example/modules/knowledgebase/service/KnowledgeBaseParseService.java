package org.example.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.ContentTypeDetectionService;
import org.example.infrastructure.file.DocumentParseService;
import org.example.infrastructure.file.FileStorageService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库解析服务
 * 委托给通用的 DocumentParseService 处理
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseParseService {
    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService fileStorageService;

    /**
     * 解析上传的知识库文件内容。
     *
     * @param file 上传文件
     * @return 解析并清洗后的文本内容
     */
    public String parse(MultipartFile file) {
        return documentParseService.parse(file);
    }

    /**
     * 解析对象存储中的知识库文件内容。
     *
     * @param storageKey 存储对象 Key
     * @param originalFilename 原始文件名
     * @return 解析并清洗后的文本内容
     */
    public String parse(String storageKey, String originalFilename) {
        validateStorageKey(storageKey);
        byte[] content = fileStorageService.download(storageKey);
        return documentParseService.parse(content, originalFilename);
    }

    /**
     * 检测上传知识库文件的内容类型。
     *
     * @param file 上传文件
     * @return 检测出的 MIME 类型
     */
    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detect(file);
    }

    /**
     * 检测对象存储中知识库文件的内容类型。
     *
     * @param storageKey 存储对象 Key
     * @param originalFilename 原始文件名
     * @return 检测出的 MIME 类型
     */
    public String detectContentType(String storageKey, String originalFilename) {
        validateStorageKey(storageKey);
        byte[] content = fileStorageService.download(storageKey);
        return contentTypeDetectionService.detect(content, originalFilename);
    }

    /**
     * 校验知识库文件存储 Key。
     *
     * @param storageKey 存储对象 Key
     * @return 校验通过时返回 true
     */
    private boolean validateStorageKey(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库文件存储路径不能为空");
        }
        return true;
    }
}
