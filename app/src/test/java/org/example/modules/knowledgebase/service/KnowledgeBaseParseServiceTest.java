package org.example.modules.knowledgebase.service;

import org.example.common.exception.BusinessException;
import org.example.infrastructure.file.ContentTypeDetectionService;
import org.example.infrastructure.file.DocumentParseService;
import org.example.infrastructure.file.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库解析服务测试")
class KnowledgeBaseParseServiceTest {
    private static final String STORAGE_KEY = "knowledge/2026/05/15/test.txt";
    private static final String ORIGINAL_FILENAME = "test.txt";
    private static final byte[] FILE_CONTENT = "knowledge base".getBytes();

    @Mock
    private DocumentParseService documentParseService;

    @Mock
    private ContentTypeDetectionService contentTypeDetectionService;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private KnowledgeBaseParseService knowledgeBaseParseService;

    @Nested
    @DisplayName("内容解析")
    class ParseDocument {

        /**
         * 验证上传文件解析时会委托通用文档解析服务。
         */
        @Test
        @DisplayName("应解析上传知识库文件")
        void shouldParseMultipartFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    ORIGINAL_FILENAME,
                    "text/plain",
                    FILE_CONTENT
            );
            when(documentParseService.parse(file)).thenReturn("parsed text");

            String text = knowledgeBaseParseService.parse(file);

            assertThat(text).isEqualTo("parsed text");
            verify(documentParseService).parse(file);
        }

        /**
         * 验证存储文件解析时会先下载文件再委托通用文档解析服务。
         */
        @Test
        @DisplayName("应解析对象存储中的知识库文件")
        void shouldParseStoredFile() {
            when(fileStorageService.download(STORAGE_KEY)).thenReturn(FILE_CONTENT);
            when(documentParseService.parse(FILE_CONTENT, ORIGINAL_FILENAME)).thenReturn("parsed text");

            String text = knowledgeBaseParseService.parse(STORAGE_KEY, ORIGINAL_FILENAME);

            assertThat(text).isEqualTo("parsed text");
            verify(fileStorageService).download(STORAGE_KEY);
            verify(documentParseService).parse(FILE_CONTENT, ORIGINAL_FILENAME);
        }
    }

    @Nested
    @DisplayName("内容类型检测")
    class DetectContentType {

        /**
         * 验证上传文件类型检测时会委托内容类型检测服务。
         */
        @Test
        @DisplayName("应检测上传知识库文件的内容类型")
        void shouldDetectMultipartFileContentType() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    ORIGINAL_FILENAME,
                    "text/plain",
                    FILE_CONTENT
            );
            when(contentTypeDetectionService.detect(file)).thenReturn("text/plain");

            String contentType = knowledgeBaseParseService.detectContentType(file);

            assertThat(contentType).isEqualTo("text/plain");
            verify(contentTypeDetectionService).detect(file);
        }

        /**
         * 验证存储文件类型检测时会先下载文件再委托内容类型检测服务。
         */
        @Test
        @DisplayName("应检测对象存储中文件的内容类型")
        void shouldDetectStoredFileContentType() {
            when(fileStorageService.download(STORAGE_KEY)).thenReturn(FILE_CONTENT);
            when(contentTypeDetectionService.detect(FILE_CONTENT, ORIGINAL_FILENAME))
                    .thenReturn("text/plain");

            String contentType = knowledgeBaseParseService.detectContentType(
                    STORAGE_KEY,
                    ORIGINAL_FILENAME
            );

            assertThat(contentType).isEqualTo("text/plain");
            verify(fileStorageService).download(STORAGE_KEY);
            verify(contentTypeDetectionService).detect(FILE_CONTENT, ORIGINAL_FILENAME);
        }
    }

    @Nested
    @DisplayName("参数校验")
    class Validation {

        /**
         * 验证解析存储文件时空存储路径会抛出业务异常。
         */
        @Test
        @DisplayName("解析时空存储路径应抛出业务异常")
        void shouldThrowBusinessExceptionWhenParseStorageKeyBlank() {
            assertThatThrownBy(() -> knowledgeBaseParseService.parse(" ", ORIGINAL_FILENAME))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库文件存储路径不能为空");
        }

        /**
         * 验证检测类型时空存储路径会抛出业务异常。
         */
        @Test
        @DisplayName("检测类型时空存储路径应抛出业务异常")
        void shouldThrowBusinessExceptionWhenDetectStorageKeyBlank() {
            assertThatThrownBy(() -> knowledgeBaseParseService.detectContentType(" ", ORIGINAL_FILENAME))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库文件存储路径不能为空");
        }
    }
}
