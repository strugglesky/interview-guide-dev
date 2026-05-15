package org.example.infrastructure.file;

import org.example.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("内容类型检测服务测试")
class ContentTypeDetectionServiceTest {
    private static final byte[] TEXT_CONTENT = "hello tika".getBytes(StandardCharsets.UTF_8);

    private final ContentTypeDetectionService contentTypeDetectionService =
            new ContentTypeDetectionService();

    @Nested
    @DisplayName("内容类型检测")
    class DetectContentType {

        /**
         * 验证 MultipartFile 能检测出文本文件的 MIME 类型。
         */
        @Test
        @DisplayName("应检测 MultipartFile 的 MIME 类型")
        void shouldDetectMultipartFileContentType() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "note.txt",
                    "text/plain",
                    TEXT_CONTENT
            );

            String contentType = contentTypeDetectionService.detect(file);

            assertThat(contentType).isEqualTo("text/plain");
        }

        /**
         * 验证字节数组结合文件名能检测出文本文件的 MIME 类型。
         */
        @Test
        @DisplayName("应检测字节数组的 MIME 类型")
        void shouldDetectBytesContentType() {
            String contentType = contentTypeDetectionService.detect(TEXT_CONTENT, "note.txt");

            assertThat(contentType).isEqualTo("text/plain");
        }

        /**
         * 验证输入流结合文件名能检测出文本文件的 MIME 类型。
         */
        @Test
        @DisplayName("应检测输入流的 MIME 类型")
        void shouldDetectInputStreamContentType() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(TEXT_CONTENT);

            String contentType = contentTypeDetectionService.detect(inputStream, "note.txt");

            assertThat(contentType).isEqualTo("text/plain");
        }

        /**
         * 验证输入流结合内容类型提示时仍能返回正确的 MIME 类型。
         */
        @Test
        @DisplayName("应结合内容类型提示检测 MIME 类型")
        void shouldDetectContentTypeWithHint() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(TEXT_CONTENT);

            String contentType = contentTypeDetectionService.detect(
                    inputStream,
                    "note.txt",
                    "text/plain"
            );

            assertThat(contentType).isEqualTo("text/plain");
        }
    }

    @Nested
    @DisplayName("参数校验")
    class Validation {

        /**
         * 验证空 MultipartFile 会抛出业务异常。
         */
        @Test
        @DisplayName("空上传文件应抛出业务异常")
        void shouldThrowBusinessExceptionWhenMultipartFileEmpty() {
            MockMultipartFile file = new MockMultipartFile("file", new byte[0]);

            assertThatThrownBy(() -> contentTypeDetectionService.detect(file))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件不能为空");
        }

        /**
         * 验证空字节数组会抛出业务异常。
         */
        @Test
        @DisplayName("空字节数组应抛出业务异常")
        void shouldThrowBusinessExceptionWhenBytesEmpty() {
            assertThatThrownBy(() -> contentTypeDetectionService.detect(new byte[0], "empty.txt"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件内容不能为空");
        }

        /**
         * 验证空输入流会抛出业务异常。
         */
        @Test
        @DisplayName("空输入流应抛出业务异常")
        void shouldThrowBusinessExceptionWhenInputStreamNull() {
            assertThatThrownBy(() -> contentTypeDetectionService.detect((InputStream) null, "empty.txt"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件输入流不能为空");
        }
    }
}
