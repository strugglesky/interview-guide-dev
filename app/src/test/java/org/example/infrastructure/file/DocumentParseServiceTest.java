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

@DisplayName("文档解析服务测试")
class DocumentParseServiceTest {
    private static final String RAW_TEXT = "第一行  \n\n\nimage123.png\n第二行";
    private static final String CLEANED_TEXT = "第一行\n\n第二行";

    private final DocumentParseService documentParseService =
            new DocumentParseService(new TextCleaningService());

    @Nested
    @DisplayName("文档解析")
    class ParseDocument {

        /**
         * 验证 MultipartFile 文本解析后会返回清洗后的正文内容。
         */
        @Test
        @DisplayName("应解析 MultipartFile 文档")
        void shouldParseMultipartFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "resume.txt",
                    "text/plain",
                    RAW_TEXT.getBytes(StandardCharsets.UTF_8)
            );

            String text = documentParseService.parse(file);

            assertThat(text).isEqualTo(CLEANED_TEXT);
        }

        /**
         * 验证字节数组文档解析后会返回清洗后的正文内容。
         */
        @Test
        @DisplayName("应解析字节数组文档")
        void shouldParseBytes() {
            String text = documentParseService.parse(
                    RAW_TEXT.getBytes(StandardCharsets.UTF_8),
                    "knowledge.txt"
            );

            assertThat(text).isEqualTo(CLEANED_TEXT);
        }

        /**
         * 验证输入流文档解析后会返回清洗后的正文内容。
         */
        @Test
        @DisplayName("应解析输入流文档")
        void shouldParseInputStream() {
            ByteArrayInputStream inputStream =
                    new ByteArrayInputStream(RAW_TEXT.getBytes(StandardCharsets.UTF_8));

            String text = documentParseService.parse(inputStream, "question.txt");

            assertThat(text).isEqualTo(CLEANED_TEXT);
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

            assertThatThrownBy(() -> documentParseService.parse(file))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件不能为空");
        }

        /**
         * 验证空字节数组会抛出业务异常。
         */
        @Test
        @DisplayName("空字节数组应抛出业务异常")
        void shouldThrowBusinessExceptionWhenBytesEmpty() {
            assertThatThrownBy(() -> documentParseService.parse(new byte[0], "empty.txt"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件内容不能为空");
        }

        /**
         * 验证空输入流会抛出业务异常。
         */
        @Test
        @DisplayName("空输入流应抛出业务异常")
        void shouldThrowBusinessExceptionWhenInputStreamNull() {
            assertThatThrownBy(() -> documentParseService.parse((InputStream) null, "empty.txt"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件输入流不能为空");
        }
    }
}
