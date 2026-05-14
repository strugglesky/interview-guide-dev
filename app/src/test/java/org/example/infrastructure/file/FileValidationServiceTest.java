package org.example.infrastructure.file;

import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("文件验证服务测试")
class FileValidationServiceTest {
    private final FileValidationService fileValidationService = new FileValidationService();

    @Nested
    @DisplayName("业务文件校验")
    class BusinessValidation {

        @Test
        @DisplayName("应通过简历文件校验")
        void shouldValidateResumeFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "resume.pdf",
                    "application/pdf",
                    new byte[]{1, 2, 3}
            );

            boolean valid = fileValidationService.validateResumeFile(file);

            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("应通过知识库文件校验")
        void shouldValidateKnowledgeBaseFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "guide.md",
                    "text/markdown",
                    new byte[]{1, 2, 3}
            );

            boolean valid = fileValidationService.validateKnowledgeBaseFile(file);

            assertThat(valid).isTrue();
        }
    }

    @Nested
    @DisplayName("基础校验")
    class BasicValidation {

        @Test
        @DisplayName("空文件应抛出业务异常")
        void shouldThrowBusinessExceptionWhenFileEmpty() {
            MockMultipartFile file = new MockMultipartFile("file", new byte[0]);

            assertThatThrownBy(() -> fileValidationService.validateNotEmpty(file))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件不能为空");
        }

        @Test
        @DisplayName("超出大小限制应抛出业务异常")
        void shouldThrowBusinessExceptionWhenFileTooLarge() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "resume.pdf",
                    "application/pdf",
                    new byte[]{1, 2, 3}
            );

            assertThatThrownBy(() -> fileValidationService.validateMaxSize(file, 2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件大小超过限制");
        }

        @Test
        @DisplayName("不支持的扩展名应抛出业务异常")
        void shouldThrowBusinessExceptionWhenExtensionNotAllowed() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "archive.zip",
                    "application/zip",
                    new byte[]{1, 2, 3}
            );

            assertThatThrownBy(() -> fileValidationService.validateExtension(
                    file,
                    Set.of("pdf", "docx"),
                    ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED
            ))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("不支持的文件类型");
        }

        @Test
        @DisplayName("不支持的 MIME 类型应抛出业务异常")
        void shouldThrowBusinessExceptionWhenContentTypeNotAllowed() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "resume.pdf",
                    "application/zip",
                    new byte[]{1, 2, 3}
            );

            assertThatThrownBy(() -> fileValidationService.validateContentType(
                    file,
                    Set.of("application/pdf"),
                    ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED
            ))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("不支持的文件类型");
        }

        @Test
        @DisplayName("应正确提取文件扩展名")
        void shouldGetExtension() {
            String extension = fileValidationService.getExtension("folder/resume.DOCX");

            assertThat(extension).isEqualTo("docx");
        }
    }
}
