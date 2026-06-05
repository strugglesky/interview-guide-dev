package org.example.modules.resume.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.ContentTypeDetectionService;
import org.example.infrastructure.file.DocumentParseService;
import org.example.infrastructure.file.FileStorageService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历解析服务
 * 委托给通用的 DocumentParseService 处理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeParseService {
    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService fileStorageService;

    /**
     * 解析上传的简历文件，提取文本内容
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 提取的文本内容
     */
    public String parseResume(MultipartFile file) {
        return documentParseService.parse(file);
    }

    /**
     * 解析字节数组形式的简历文件
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容
     */
    public String parseResume(byte[] fileBytes, String fileName) {
        return documentParseService.parse(fileBytes, fileName);
    }

    /**
     * 从存储下载文件并解析内容
     *
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 提取的文本内容
     */
    public String downloadAndParseContent(String storageKey, String originalFilename) {
        if (!StringUtils.hasText(storageKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文件存储路径不能为空");
        }
        byte[] content = fileStorageService.download(storageKey);
        return documentParseService.parse(content, originalFilename);
    }

    /**
     * 检测文件的MIME类型
     */
    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detect(file);
    }
}
