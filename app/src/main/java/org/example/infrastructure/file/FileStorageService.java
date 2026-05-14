package org.example.infrastructure.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.config.StorageConfigProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储服务。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileStorageService {
    private static final DateTimeFormatter DATE_PATH_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final S3Client s3Client;
    private final StorageConfigProperties storageConfig;

    /**
     * 上传 MultipartFile 文件到对象存储。
     *
     * @param file      上传文件
     * @param objectKey 对象存储 Key
     * @return 文件访问地址
     */
    public String upload(MultipartFile file, String objectKey) {
        if (file == null || file.isEmpty()) {
            log.warn("文件不能为空");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }

        try (InputStream inputStream = file.getInputStream()) {
            return upload(inputStream, objectKey, file.getContentType(), file.getSize());
        } catch (IOException e) {
            log.error("文件读取失败:{}", e.getMessage());
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件读取失败", e);
        }
    }

    /**
     * 上传输入流到对象存储。
     *
     * @param inputStream   文件输入流，由调用方负责关闭
     * @param objectKey     对象存储 Key
     * @param contentType   文件 MIME 类型
     * @param contentLength 文件字节长度
     * @return 文件访问地址
     */
    public String upload(
            InputStream inputStream,
            String objectKey,
            String contentType,
            long contentLength
    ) {
        validateInputStream(inputStream);
        validateContentLength(contentLength);
        String normalizedKey = normalizeObjectKey(objectKey);

        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(getBucket())
                .key(normalizedKey);
        if (StringUtils.hasText(contentType)) {
            requestBuilder.contentType(contentType);
        }

        try {
            s3Client.putObject(
                    requestBuilder.build(),
                    RequestBody.fromInputStream(inputStream, contentLength)
            );
            log.info("File uploaded: bucket={}, objectKey={}", getBucket(), normalizedKey);
            return getFileUrl(normalizedKey);
        } catch (SdkException e) {
            log.error("File upload failed: bucket={}, objectKey={}", getBucket(), normalizedKey, e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件上传失败", e);
        }
    }

    /**
     * 从对象存储下载文件。
     *
     * @param objectKey 对象存储 Key
     * @return 文件字节内容
     */
    public byte[] download(String objectKey) {
        String normalizedKey = normalizeObjectKey(objectKey);

        try {
            ResponseBytes<GetObjectResponse> responseBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(getBucket())
                            .key(normalizedKey)
                            .build()
            );
            return responseBytes.asByteArray();
        } catch (SdkException e) {
            log.error("File download failed: bucket={}, objectKey={}", getBucket(), normalizedKey, e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败", e);
        }
    }

    /**
     * 删除对象存储中的文件。
     *
     * @param objectKey 对象存储 Key
     * @return 删除请求成功时返回 true
     */
    public boolean delete(String objectKey) {
        String normalizedKey = normalizeObjectKey(objectKey);

        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(getBucket())
                    .key(normalizedKey)
                    .build());
            log.info("File deleted: bucket={}, objectKey={}", getBucket(), normalizedKey);
            return true;
        } catch (SdkException e) {
            log.error("File delete failed: bucket={}, objectKey={}", getBucket(), normalizedKey, e);
            throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "文件删除失败", e);
        }
    }

    /**
     * 判断对象存储中的文件是否存在。
     *
     * @param objectKey 对象存储 Key
     * @return 文件存在时返回 true
     */
    public boolean exists(String objectKey) {
        String normalizedKey = normalizeObjectKey(objectKey);

        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(getBucket())
                    .key(normalizedKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            log.error("File exists check failed: bucket={}, objectKey={}", getBucket(), normalizedKey, e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件状态检查失败", e);
        } catch (SdkException e) {
            log.error("File exists check failed: bucket={}, objectKey={}", getBucket(), normalizedKey, e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件状态检查失败", e);
        }
    }

    /**
     * 生成对象存储 Key。
     *
     * @param directory        业务目录
     * @param originalFilename 原始文件名
     * @return 对象存储 Key
     */
    public String generateObjectKey(String directory, String originalFilename) {
        String normalizedDirectory = normalizeDirectory(directory);
        String extension = getFileExtension(originalFilename);
        String datePath = LocalDate.now().format(DATE_PATH_FORMATTER);
        return normalizedDirectory + "/" + datePath + "/" + UUID.randomUUID() + extension;
    }

    /**
     * 获取文件访问地址。
     *
     * @param objectKey 对象存储 Key
     * @return 文件访问地址
     */
    public String getFileUrl(String objectKey) {
        String endpoint = normalizeEndpoint(storageConfig.getEndpoint());
        String normalizedKey = normalizeObjectKey(objectKey);
        return endpoint + "/" + getBucket() + "/" + normalizedKey;
    }

    /**
     * 校验输入流。
     *
     * @param inputStream 文件输入流
     * @return 校验通过时返回 true
     */
    private boolean validateInputStream(InputStream inputStream) {
        if (inputStream == null) {
            log.warn("文件输入流不能为空");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件输入流不能为空");
        }
        return true;
    }

    /**
     * 校验文件字节长度。
     *
     * @param contentLength 文件字节长度
     * @return 校验通过时返回 true
     */
    private boolean validateContentLength(long contentLength) {
        if (contentLength <= 0) {
            log.warn("文件内容不能为空");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容不能为空");
        }
        return true;
    }

    /**
     * 获取存储桶名称。
     *
     * @return 存储桶名称
     */
    private String getBucket() {
        if (!StringUtils.hasText(storageConfig.getBucket())) {
            log.error("存储桶配置不能为空");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "存储桶配置不能为空");
        }
        return storageConfig.getBucket();
    }

    /**
     * 标准化对象存储 Key。
     *
     * @param objectKey 对象存储 Key
     * @return 去除首尾斜杠后的对象存储 Key
     */
    private String normalizeObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            log.warn("文件存储路径不能为空");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件存储路径不能为空");
        }
        return objectKey.replace("\\", "/").replaceAll("^/+", "").replaceAll("/+$", "");
    }

    /**
     * 标准化业务目录。
     *
     * @param directory 业务目录
     * @return 去除首尾斜杠后的业务目录
     */
    private String normalizeDirectory(String directory) {
        if (!StringUtils.hasText(directory)) {
            log.warn("文件目录不能为空");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件目录不能为空");
        }
        return normalizeObjectKey(directory);
    }

    /**
     * 提取原始文件名中的扩展名。
     *
     * @param originalFilename 原始文件名
     * @return 带点号的小写扩展名，没有扩展名时返回空字符串
     */
    private String getFileExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "";
        }

        String filename = originalFilename.replace("\\", "/");
        int slashIndex = filename.lastIndexOf('/');
        String plainFilename = slashIndex >= 0 ? filename.substring(slashIndex + 1) : filename;
        int dotIndex = plainFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == plainFilename.length() - 1) {
            return "";
        }
        return plainFilename.substring(dotIndex).toLowerCase();
    }

    /**
     * 标准化对象存储访问端点。
     *
     * @param endpoint 对象存储访问端点
     * @return 去除末尾斜杠后的对象存储访问端点
     */
    private String normalizeEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            log.error("存储端点配置不能为空");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "存储端点配置不能为空");
        }
        return endpoint.replaceAll("/+$", "");
    }
}
