package org.example.modules.resume.controller;

import org.example.common.constant.CommonConstants;
import org.example.common.result.Result;
import org.example.modules.resume.model.ResumeDetailDTO;
import org.example.modules.resume.model.ResumeListItemDTO;
import org.example.modules.resume.service.ResumeDeleteService;
import org.example.modules.resume.service.ResumeHistoryService;
import org.example.modules.resume.service.ResumeUploadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历控制器测试")
class ResumeControllerTest {

    @Mock
    private ResumeUploadService resumeUploadService;

    @Mock
    private ResumeHistoryService resumeHistoryService;

    @Mock
    private ResumeDeleteService resumeDeleteService;

    @InjectMocks
    private ResumeController resumeController;

    @Nested
    @DisplayName("上传与查询")
    class UploadAndQueryTests {

        /**
         * 验证上传接口会直接委托上传服务并返回统一结果包装。
         */
        @Test
        @DisplayName("上传简历时应返回上传服务结果")
        void shouldUploadAndAnalyzeResume() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "resume.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    "pdf-content".getBytes()
            );
            Map<String, Object> expected = Map.of(
                    "duplicate", false,
                    "resume", Map.of("id", 1L, "filename", "resume.pdf")
            );
            when(resumeUploadService.uploadAndAnalyze(file)).thenReturn(expected);

            // 控制器层只负责参数接收和结果包装，不做额外业务处理。
            Result<Map<String, Object>> result = resumeController.uploadAndAnalyze(file);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(resumeUploadService).uploadAndAnalyze(file);
        }

        /**
         * 验证列表接口会直接返回历史服务提供的简历列表。
         */
        @Test
        @DisplayName("获取简历列表时应返回历史服务结果")
        void shouldGetAllResumes() {
            List<ResumeListItemDTO> expected = List.of(buildListItem(1L, "resume.pdf"));
            when(resumeHistoryService.getAllResumes()).thenReturn(expected);

            // 列表接口不做任何转换，只包装统一 Result。
            Result<List<ResumeListItemDTO>> result = resumeController.getAllResumes();

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(resumeHistoryService).getAllResumes();
        }

        /**
         * 验证详情接口会直接委托历史服务。
         */
        @Test
        @DisplayName("获取简历详情时应返回详情DTO")
        void shouldGetResumeDetail() {
            ResumeDetailDTO expected = buildDetailDto(2L, "resume.pdf");
            when(resumeHistoryService.getResumeDetail(2L)).thenReturn(expected);

            // 详情接口的关键是确保路径参数被原样透传。
            Result<ResumeDetailDTO> result = resumeController.getResumeDetail(2L);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(resumeHistoryService).getResumeDetail(2L);
        }
    }

    @Nested
    @DisplayName("导出与删除")
    class ExportAndDeleteTests {

        /**
         * 验证导出接口会返回带附件头的 PDF 响应。
         */
        @Test
        @DisplayName("导出简历分析报告时应返回 PDF 响应")
        void shouldExportAnalysisPdf() {
            byte[] pdfBytes = new byte[]{1, 2, 3};
            ResumeHistoryService.ExportResult exportResult =
                    new ResumeHistoryService.ExportResult(pdfBytes, "resume-analysis-report.pdf");
            when(resumeHistoryService.exportResumeAnalysisReport(3L)).thenReturn(exportResult);

            // 下载响应需要同时校验文件名、Content-Type 和响应体。
            ResponseEntity<byte[]> response = resumeController.exportAnalysisPdf(3L);

            assertThat(response.getBody()).containsExactly(1, 2, 3);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .contains("attachment")
                    .contains("resume-analysis-report.pdf");
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
            verify(resumeHistoryService).exportResumeAnalysisReport(3L);
        }

        /**
         * 验证删除接口会委托删除服务并返回统一成功响应。
         */
        @Test
        @DisplayName("删除简历时应调用删除服务")
        void shouldDeleteResume() {
            // 删除接口本身没有业务返回值，只需要确认 service 被调用。
            Result<Void> result = resumeController.deleteResume(4L);

            assertSuccess(result);
            assertThat(result.getData()).isNull();
            verify(resumeDeleteService).deleteResume(4L);
        }

        /**
         * 验证重新分析接口会把请求转发给上传服务。
         */
        @Test
        @DisplayName("重新分析简历时应调用重试服务")
        void shouldReanalyzeResume() {
            // 重试接口只负责触发服务层的重新投递逻辑。
            Result<Void> result = resumeController.reanalyze(5L);

            assertSuccess(result);
            assertThat(result.getData()).isNull();
            verify(resumeUploadService).reanalyze(5L);
        }
    }

    private void assertSuccess(Result<?> result) {
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(CommonConstants.StatusCode.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
    }

    private ResumeListItemDTO buildListItem(Long id, String filename) {
        return new ResumeListItemDTO(
                id,
                filename,
                2048L,
                LocalDateTime.of(2026, 6, 12, 10, 0),
                3,
                90,
                LocalDateTime.of(2026, 6, 12, 11, 0),
                2,
                null,
                null
        );
    }

    private ResumeDetailDTO buildDetailDto(Long id, String filename) {
        return new ResumeDetailDTO(
                id,
                filename,
                2048L,
                MediaType.APPLICATION_PDF_VALUE,
                "http://example.com/" + filename,
                LocalDateTime.of(2026, 6, 12, 10, 0),
                3,
                "resume text",
                null,
                null,
                List.of(),
                List.of()
        );
    }
}
