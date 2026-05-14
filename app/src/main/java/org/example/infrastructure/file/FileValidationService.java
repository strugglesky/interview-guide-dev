package org.example.infrastructure.file;

import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * 文件验证服务。
 * 提供通用的文件验证功能。
 */
@Service
@Slf4j
public class FileValidationService {
    private static final long MB = 1024L * 1024L;
    private static final long RESUME_MAX_SIZE = 10L * MB;
    private static final long KNOWLEDGE_BASE_MAX_SIZE = 50L * MB;

    private static final Set<String> RESUME_EXTENSIONS = Set.of("pdf", "doc", "docx");
    private static final Set<String> KNOWLEDGE_BASE_EXTENSIONS = Set.of(
            "pdf",
            "doc",
            "docx",
            "txt",
            "md"
    );
    private static final Set<String> DOCUMENT_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "application/octet-stream"
    );

    /**
     * 校验简历上传文件。
     *
     * @param file 上传文件
     * @return 校验通过时返回 true
     */
    public boolean validateResumeFile(MultipartFile file) {
        validateNotEmpty(file);
        validateMaxSize(file, RESUME_MAX_SIZE);
        validateExtension(file, RESUME_EXTENSIONS, ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED);
        validateContentType(file, DOCUMENT_CONTENT_TYPES, ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED);
        return true;
    }

    /**
     * 校验知识库上传文件。
     *
     * @param file 上传文件
     * @return 校验通过时返回 true
     */
    public boolean validateKnowledgeBaseFile(MultipartFile file) {
        validateNotEmpty(file);
        validateMaxSize(file, KNOWLEDGE_BASE_MAX_SIZE);
        validateExtension(file, KNOWLEDGE_BASE_EXTENSIONS, ErrorCode.BAD_REQUEST);
        validateContentType(file, DOCUMENT_CONTENT_TYPES, ErrorCode.BAD_REQUEST);
        return true;
    }

    /**
     * 校验上传文件不为空。
     *
     * @param file 上传文件
     * @return 校验通过时返回 true
     */
    public boolean validateNotEmpty(MultipartFile file) {
        if (file == null) {
            log.warn("File validation failed: reason=file_null");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (file.isEmpty()) {
            log.warn(
                    "File validation failed: filename={}, reason=file_empty",
                    file.getOriginalFilename()
            );
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        return true;
    }

    /**
     * 校验文件大小不超过限制。
     *
     * @param file    上传文件
     * @param maxSize 最大允许字节数
     * @return 校验通过时返回 true
     */
    public boolean validateMaxSize(MultipartFile file, long maxSize) {
        validateNotEmpty(file);
        if (maxSize <= 0) {
            log.warn("File validation failed: filename={}, maxSize={}, reason=max_size_invalid",
                    file.getOriginalFilename(), maxSize);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小限制必须大于0");
        }
        if (file.getSize() > maxSize) {
            log.warn("File validation failed: filename={}, size={}, maxSize={}, reason=file_too_large",
                    file.getOriginalFilename(), file.getSize(), maxSize);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
        }
        return true;
    }

    /**
     * 校验文件扩展名。
     *
     * @param file              上传文件
     * @param allowedExtensions 允许的扩展名集合
     * @param errorCode         校验失败时使用的错误码
     * @return 校验通过时返回 true
     */
    public boolean validateExtension(
            MultipartFile file,
            Set<String> allowedExtensions,
            ErrorCode errorCode
    ) {
        validateNotEmpty(file);
        validateAllowedValues(allowedExtensions, "允许的文件扩展名不能为空");

        String extension = getExtension(file.getOriginalFilename());
        if (!allowedExtensions.contains(extension)) {
            log.warn(
                    "File validation failed: filename={}, extension={}, allowed={}, reason=extension_denied",
                    file.getOriginalFilename(), extension, allowedExtensions
            );
            throw new BusinessException(errorCode, "不支持的文件类型");
        }
        return true;
    }

    /**
     * 校验文件 MIME 类型。
     *
     * @param file                上传文件
     * @param allowedContentTypes 允许的 MIME 类型集合
     * @param errorCode           校验失败时使用的错误码
     * @return 校验通过时返回 true
     */
    public boolean validateContentType(
            MultipartFile file,
            Set<String> allowedContentTypes,
            ErrorCode errorCode
    ) {
        validateNotEmpty(file);
        validateAllowedValues(allowedContentTypes, "允许的文件 MIME 类型不能为空");

        String contentType = normalizeContentType(file.getContentType());
        if (!allowedContentTypes.contains(contentType)) {
            log.warn(
                    "File validation failed: filename={}, contentType={}, allowed={}, "
                            + "reason=content_type_denied",
                    file.getOriginalFilename(), contentType, allowedContentTypes
            );
            throw new BusinessException(errorCode, "不支持的文件类型");
        }
        return true;
    }

    /**
     * 获取文件扩展名。
     *
     * @param filename 原始文件名
     * @return 小写扩展名，不包含点号
     */
    public String getExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            log.warn("File validation failed: reason=filename_blank");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }

        String normalizedFilename = filename.replace("\\", "/");
        int slashIndex = normalizedFilename.lastIndexOf('/');
        String plainFilename = slashIndex >= 0
                ? normalizedFilename.substring(slashIndex + 1)
                : normalizedFilename;
        int dotIndex = plainFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == plainFilename.length() - 1) {
            log.warn("File validation failed: filename={}, reason=extension_missing", filename);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件扩展名不能为空");
        }
        return plainFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 标准化 MIME 类型。
     *
     * @param contentType 原始 MIME 类型
     * @return 小写 MIME 类型
     */
    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            log.warn("File validation failed: reason=content_type_blank");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件 MIME 类型不能为空");
        }
        return contentType.toLowerCase(Locale.ROOT);
    }

    /**
     * 校验允许值集合不为空。
     *
     * @param allowedValues 允许值集合
     * @param message       异常消息
     * @return 校验通过时返回 true
     */
    private boolean validateAllowedValues(Set<String> allowedValues, String message) {
        if (allowedValues == null || allowedValues.isEmpty()) {
            log.warn("File validation failed: reason=allowed_values_empty");
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return true;
    }
}
