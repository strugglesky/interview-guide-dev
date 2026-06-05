package org.example.modules.resume.service;

import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
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
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历解析服务测试")
class ResumeParseServiceTest {

    @Mock
    private DocumentParseService documentParseService;

    @Mock
    private ContentTypeDetectionService contentTypeDetectionService;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private MultipartFile multipartFile;

    @InjectMocks
    private ResumeParseService resumeParseService;

    @Nested
    @DisplayName("解析上传文件")
    class ParseMultipartFile {

        @Test
        @DisplayName("应委托文档解析服务解析简历文件")
        void shouldDelegateToDocumentParseService() {
            when(documentParseService.parse(multipartFile)).thenReturn("resume content");

            String result = resumeParseService.parseResume(multipartFile);

            assertThat(result).isEqualTo("resume content");
            verify(documentParseService).parse(multipartFile);
        }
    }

    @Nested
    @DisplayName("解析字节数组")
    class ParseBytes {

        @Test
        @DisplayName("应委托文档解析服务解析字节数组简历")
        void shouldDelegateToDocumentParseServiceForBytes() {
            byte[] fileBytes = "resume".getBytes();
            when(documentParseService.parse(fileBytes, "resume.pdf")).thenReturn("parsed text");

            String result = resumeParseService.parseResume(fileBytes, "resume.pdf");

            assertThat(result).isEqualTo("parsed text");
            verify(documentParseService).parse(fileBytes, "resume.pdf");
        }
    }

    @Nested
    @DisplayName("下载并解析")
    class DownloadAndParse {

        @Test
        @DisplayName("应下载存储文件并解析内容")
        void shouldDownloadAndParseContent() {
            byte[] content = "resume".getBytes();
            when(fileStorageService.download("resume/1.pdf")).thenReturn(content);
            when(documentParseService.parse(content, "resume.pdf")).thenReturn("parsed text");

            String result = resumeParseService.downloadAndParseContent(
                    "resume/1.pdf",
                    "resume.pdf"
            );

            assertThat(result).isEqualTo("parsed text");
            verify(fileStorageService).download("resume/1.pdf");
            verify(documentParseService).parse(content, "resume.pdf");
        }

        @Test
        @DisplayName("存储路径为空时应抛出业务异常")
        void shouldThrowWhenStorageKeyBlank() {
            assertThatThrownBy(() -> resumeParseService.downloadAndParseContent(" ", "resume.pdf"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> {
                        BusinessException businessException = (BusinessException) exception;
                        assertThat(businessException.getCode()).isEqualTo(
                                ErrorCode.BAD_REQUEST.getCode()
                        );
                    });

            verify(fileStorageService, never()).download(" ");
        }
    }

    @Nested
    @DisplayName("检测内容类型")
    class DetectContentType {

        @Test
        @DisplayName("应委托内容类型检测服务检测简历文件类型")
        void shouldDelegateToContentTypeDetectionService() {
            when(contentTypeDetectionService.detect(multipartFile)).thenReturn("application/pdf");

            String result = resumeParseService.detectContentType(multipartFile);

            assertThat(result).isEqualTo("application/pdf");
            verify(contentTypeDetectionService).detect(multipartFile);
        }
    }
}
