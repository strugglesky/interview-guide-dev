package org.example.infrastructure.file;

import org.example.common.config.StorageConfigProperties;
import org.example.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文件存储服务测试")
class FileStorageServiceTest {
    @Mock
    private S3Client s3Client;

    @Mock
    private StorageConfigProperties storageConfig;

    @InjectMocks
    private FileStorageService fileStorageService;

    @Nested
    @DisplayName("文件上传")
    class Upload {

        @Test
        @DisplayName("应上传 MultipartFile 并返回访问地址")
        void shouldUploadMultipartFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "resume.pdf",
                    "application/pdf",
                    "content".getBytes(StandardCharsets.UTF_8)
            );
            when(storageConfig.getBucket()).thenReturn("bucket");
            when(storageConfig.getEndpoint()).thenReturn("http://localhost:9000");

            String fileUrl = fileStorageService.upload(file, "resume/test.pdf");

            assertThat(fileUrl).isEqualTo("http://localhost:9000/bucket/resume/test.pdf");
            verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("空文件上传应抛出业务异常")
        void shouldThrowBusinessExceptionWhenFileEmpty() {
            MockMultipartFile file = new MockMultipartFile("file", new byte[0]);

            assertThatThrownBy(() -> fileStorageService.upload(file, "resume/test.pdf"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件不能为空");
        }
    }

    @Nested
    @DisplayName("文件下载与删除")
    class StorageOperation {

        @Test
        @DisplayName("应下载文件内容")
        void shouldDownloadFile() {
            when(storageConfig.getBucket()).thenReturn("bucket");
            ResponseBytes<GetObjectResponse> responseBytes =
                    ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "abc".getBytes());
            when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

            byte[] content = fileStorageService.download("resume/test.pdf");

            assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("abc");
        }

        @Test
        @DisplayName("应删除文件")
        void shouldDeleteFile() {
            when(storageConfig.getBucket()).thenReturn("bucket");
            doNothing().when(s3Client).deleteObject(any(DeleteObjectRequest.class));

            boolean deleted = fileStorageService.delete("resume/test.pdf");

            assertThat(deleted).isTrue();
        }

        @Test
        @DisplayName("文件不存在时 exists 应返回 false")
        void shouldReturnFalseWhenFileNotExists() {
            when(storageConfig.getBucket()).thenReturn("bucket");
            S3Exception exception = (S3Exception) S3Exception.builder().statusCode(404).build();
            doThrow(exception).when(s3Client).headObject(any(HeadObjectRequest.class));

            boolean exists = fileStorageService.exists("resume/test.pdf");

            assertThat(exists).isFalse();
        }
    }

    @Nested
    @DisplayName("路径生成")
    class ObjectKey {

        @Test
        @DisplayName("应生成带日期目录和扩展名的对象 Key")
        void shouldGenerateObjectKey() {
            String objectKey = fileStorageService.generateObjectKey("resume", "test.PDF");

            assertThat(objectKey).startsWith("resume/");
            assertThat(objectKey).endsWith(".pdf");
        }

        @Test
        @DisplayName("应拼接文件访问地址")
        void shouldBuildFileUrl() {
            when(storageConfig.getBucket()).thenReturn("bucket");
            when(storageConfig.getEndpoint()).thenReturn("http://localhost:9000/");

            String fileUrl = fileStorageService.getFileUrl("/resume/test.pdf");

            assertThat(fileUrl).isEqualTo("http://localhost:9000/bucket/resume/test.pdf");
        }

        @Test
        @DisplayName("上传时应标准化对象 Key")
        void shouldNormalizeObjectKeyWhenUpload() {
            when(storageConfig.getBucket()).thenReturn("bucket");
            when(storageConfig.getEndpoint()).thenReturn("http://localhost:9000");
            ByteArrayInputStream inputStream =
                    new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8));
            ArgumentCaptor<PutObjectRequest> requestCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);

            fileStorageService.upload(inputStream, "/resume/test.pdf/", "application/pdf", 3);

            verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
            assertThat(requestCaptor.getValue().key()).isEqualTo("resume/test.pdf");
        }
    }
}
