package org.example.infrastructure.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文件内容类型检测服务
 * 使用 Apache Tika 进行精确的 MIME 类型检测
 */
@Service
@Slf4j
public class ContentTypeDetectionService {
    private static final Tika TIKA = new Tika();

    /**
     * 检测 MultipartFile 的 MIME 类型。
     *
     * @param file 上传文件
     * @return 检测出的 MIME 类型
     */
    public String detect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }

        try (InputStream inputStream = file.getInputStream()) {
            return detect(inputStream, file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            log.error("Content type detection failed: fileName={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容类型检测失败", e);
        }
    }

    /**
     * 检测字节数组的 MIME 类型。
     *
     * @param content 文件字节内容
     * @param fileName 文件名
     * @return 检测出的 MIME 类型
     */
    public String detect(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容不能为空");
        }

        try (InputStream inputStream = new ByteArrayInputStream(content)) {
            return detect(inputStream, fileName, null);
        } catch (IOException e) {
            log.error("Content type detection failed: fileName={}", fileName, e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容类型检测失败", e);
        }
    }

    /**
     * 检测输入流的 MIME 类型。
     *
     * @param inputStream 文件输入流
     * @param fileName 文件名
     * @return 检测出的 MIME 类型
     */
    public String detect(InputStream inputStream, String fileName) {
        return detect(inputStream, fileName, null);
    }

    /**
     * 检测输入流的 MIME 类型，并使用内容类型提示增强识别。
     *
     * @param inputStream 文件输入流
     * @param fileName 文件名
     * @param contentType 内容类型提示
     * @return 检测出的 MIME 类型
     */
    public String detect(InputStream inputStream, String fileName, String contentType) {
        if (inputStream == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件输入流不能为空");
        }

        Metadata metadata = buildMetadata(fileName, contentType);
        try {
            InputStream detectionStream = prepareDetectionStream(inputStream);
            MediaType mediaType = TIKA.getDetector().detect(detectionStream, metadata);
            return mediaType.toString();
        } catch (IOException e) {
            log.error("Content type detection failed: fileName={}, contentType={}", fileName, contentType, e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容类型检测失败", e);
        }
    }

    /**
     * 构建 MIME 检测所需元数据。
     *
     * @param fileName 文件名
     * @param contentType 内容类型提示
     * @return 文档元数据
     */
    private Metadata buildMetadata(String fileName, String contentType) {
        Metadata metadata = new Metadata();
        if (StringUtils.hasText(fileName)) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        if (StringUtils.hasText(contentType)) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        return metadata;
    }

    private InputStream prepareDetectionStream(InputStream inputStream) {
        InputStream detectionStream = inputStream.markSupported()
                ? inputStream
                : new BufferedInputStream(inputStream);
        detectionStream.mark(8192);
        return detectionStream;
    }
}
