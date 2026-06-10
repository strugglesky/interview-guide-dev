package org.example.infrastructure.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.example.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PDF导出服务测试")
class PdfExportServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private final PdfExportService pdfExportService = new PdfExportService(objectMapper);

    @Nested
    @DisplayName("导出简历分析PDF")
    class ExportResumeAnalysis {

        @Test
        @DisplayName("应导出包含分析内容的PDF")
        void shouldExportResumeAnalysisPdf() throws Exception {
            ResumeEntity resume = buildResume();
            ResumeAnalysisResponse analysis = buildAnalysis();

            byte[] pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysis);

            assertThat(pdfBytes).isNotEmpty();
            assertThat(new String(pdfBytes, 0, 4)).isEqualTo("%PDF");
            String pdfText = extractPdfText(pdfBytes);
            assertThat(pdfText).contains("简历分析报告");
            assertThat(pdfText).contains("文件名：resume.pdf");
            assertThat(pdfText).contains("综合评分：88");
            assertThat(pdfText).contains("内容完整性：20");
            assertThat(pdfText).contains("亮点总结");
            assertThat(pdfText).contains("技术栈覆盖完整");
            assertThat(pdfText).contains("改进建议");
            assertThat(pdfText).contains("项目（优先级：高）");
            assertThat(pdfText).contains("补充性能优化前后对比数据");
            assertThat(pdfText).contains("简历原文");
            assertThat(pdfText).contains("Java resume content");
        }

        @Test
        @DisplayName("分项得分为空时应使用默认值导出PDF")
        void shouldExportPdfWithDefaultScoreDetailsWhenScoreDetailNull() throws Exception {
            ResumeEntity resume = buildResume();
            ResumeAnalysisResponse analysis = new ResumeAnalysisResponse(
                    60,
                    null,
                    "总体表现稳定",
                    List.of(),
                    List.of(),
                    "resume text"
            );

            byte[] pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysis);

            assertThat(pdfBytes).isNotEmpty();
            String pdfText = extractPdfText(pdfBytes);
            assertThat(pdfText).contains("综合评分：60");
            assertThat(pdfText).contains("内容完整性：0");
            assertThat(pdfText).contains("结构清晰度：0");
            assertThat(pdfText).contains("技能匹配度：0");
            assertThat(pdfText).contains("暂无亮点总结");
            assertThat(pdfText).contains("暂无改进建议");
        }
    }

    @Nested
    @DisplayName("参数校验")
    class Validation {

        @Test
        @DisplayName("简历为空时应抛出业务异常")
        void shouldThrowWhenResumeNull() {
            ResumeAnalysisResponse analysis = buildAnalysis();

            assertThatThrownBy(() -> pdfExportService.exportResumeAnalysis(null, analysis))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
        }

        @Test
        @DisplayName("简历ID为空时应抛出业务异常")
        void shouldThrowWhenResumeIdNull() {
            ResumeEntity resume = buildResume();
            resume.setId(null);
            ResumeAnalysisResponse analysis = buildAnalysis();

            assertThatThrownBy(() -> pdfExportService.exportResumeAnalysis(resume, analysis))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
        }

        @Test
        @DisplayName("分析结果为空时应抛出业务异常")
        void shouldThrowWhenAnalysisNull() {
            ResumeEntity resume = buildResume();

            assertThatThrownBy(() -> pdfExportService.exportResumeAnalysis(resume, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
        }
    }

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandling {

        @Test
        @DisplayName("构建评分明细失败时应抛出导出异常")
        void shouldThrowWhenBuildScoreDetailFailed() throws Exception {
            ObjectMapper mockObjectMapper = mock(ObjectMapper.class);
            when(mockObjectMapper.writeValueAsString(any()))
                    .thenThrow(new JsonProcessingException("serialize failed") {});
            PdfExportService service = new PdfExportService(mockObjectMapper);

            assertThatThrownBy(() -> service.exportResumeAnalysis(buildResume(), buildAnalysis()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.EXPORT_PDF_FAILED.getCode()));
        }
    }

    private ResumeEntity buildResume() {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(1L);
        resume.setOriginalFilename("resume.pdf");
        resume.setContentType("application/pdf");
        resume.setFileSize(2048L);
        resume.setStorageKey("resume/resume.pdf");
        resume.setStorageUrl("http://rustfs/resume/resume.pdf");
        resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
        return resume;
    }

    private ResumeAnalysisResponse buildAnalysis() {
        return new ResumeAnalysisResponse(
                88,
                new ResumeAnalysisResponse.ScoreDetail(20, 18, 22, 12, 14),
                "简历整体较强，项目经历有一定亮点",
                List.of("技术栈覆盖完整", "项目经历较丰富"),
                List.of(
                        new ResumeAnalysisResponse.Suggestion(
                                "项目",
                                "高",
                                "量化指标不足",
                                "补充性能优化前后对比数据"
                        ),
                        new ResumeAnalysisResponse.Suggestion(
                                "表达",
                                "中",
                                "措辞略平",
                                "改用结果导向的项目描述"
                        )
                ),
                "Java resume content"
        );
    }

    private String extractPdfText(byte[] pdfBytes) throws IOException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(pdfBytes);
             PdfReader reader = new PdfReader(inputStream);
             PdfDocument pdfDocument = new PdfDocument(reader)) {
            StringBuilder builder = new StringBuilder();
            for (int page = 1; page <= pdfDocument.getNumberOfPages(); page++) {
                builder.append(PdfTextExtractor.getTextFromPage(pdfDocument.getPage(page)));
            }
            return builder.toString();
        }
    }
}
