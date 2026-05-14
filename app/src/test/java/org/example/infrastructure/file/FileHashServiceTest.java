package org.example.infrastructure.file;

import org.example.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("文件哈希服务测试")
class FileHashServiceTest {
    private static final String TEST_CONTENT = "hello";
    private static final String TEST_CONTENT_HASH =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    private final FileHashService fileHashService = new FileHashService();

    @Nested
    @DisplayName("哈希计算")
    class CalculateHash {

        @Test
        @DisplayName("应计算字节数组哈希")
        void shouldCalculateHashFromBytes() {
            String hash = fileHashService.calculateHash(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));

            assertThat(hash).isEqualTo(TEST_CONTENT_HASH);
        }

        @Test
        @DisplayName("应计算输入流哈希")
        void shouldCalculateHashFromInputStream() {
            ByteArrayInputStream inputStream =
                    new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));

            String hash = fileHashService.calculateHash(inputStream);

            assertThat(hash).isEqualTo(TEST_CONTENT_HASH);
        }

        @Test
        @DisplayName("应计算上传文件哈希")
        void shouldCalculateHashFromMultipartFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "test.txt",
                    "text/plain",
                    TEST_CONTENT.getBytes(StandardCharsets.UTF_8)
            );

            String hash = fileHashService.calculateHash(file);

            assertThat(hash).isEqualTo(TEST_CONTENT_HASH);
        }
    }

    @Nested
    @DisplayName("参数校验")
    class Validation {

        @Test
        @DisplayName("空字节数组应抛出业务异常")
        void shouldThrowBusinessExceptionWhenBytesEmpty() {
            assertThatThrownBy(() -> fileHashService.calculateHash(new byte[0]))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件内容不能为空");
        }

        @Test
        @DisplayName("空输入流应抛出业务异常")
        void shouldThrowBusinessExceptionWhenInputStreamNull() {
            assertThatThrownBy(() -> fileHashService.calculateHash((ByteArrayInputStream) null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件输入流不能为空");
        }

        @Test
        @DisplayName("空上传文件应抛出业务异常")
        void shouldThrowBusinessExceptionWhenMultipartFileEmpty() {
            MockMultipartFile file = new MockMultipartFile("file", new byte[0]);

            assertThatThrownBy(() -> fileHashService.calculateHash(file))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件不能为空");
        }
    }

    @Nested
    @DisplayName("哈希比较")
    class MatchHash {

        @Test
        @DisplayName("大小写不同但内容一致时应匹配")
        void shouldMatchHashIgnoringCase() {
            assertThat(fileHashService.matches(TEST_CONTENT_HASH, TEST_CONTENT_HASH.toUpperCase()))
                    .isTrue();
        }

        @Test
        @DisplayName("任一哈希为空时不应匹配")
        void shouldNotMatchWhenHashBlank() {
            assertThat(fileHashService.matches(TEST_CONTENT_HASH, " ")).isFalse();
        }
    }
}
