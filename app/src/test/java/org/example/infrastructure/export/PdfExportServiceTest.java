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
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.example.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
                    "整体表现稳定",
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
    @DisplayName("导出面试报告PDF")
    class ExportInterviewReport {

        @Test
        @DisplayName("应导出包含面试信息和问答详情的PDF")
        void shouldExportInterviewReportPdf() throws Exception {
            InterviewSessionEntity session = buildInterviewSession();

            byte[] pdfBytes = pdfExportService.exportInterviewReport(session);

            assertThat(pdfBytes).isNotEmpty();
            assertThat(new String(pdfBytes, 0, 4)).isEqualTo("%PDF");
            String pdfText = extractPdfText(pdfBytes);
            assertThat(pdfText).contains("模拟面试报告");
            assertThat(pdfText).contains("会话ID：session-1");
            assertThat(pdfText).contains("题目数量：2");
            assertThat(pdfText).contains("面试状态：已评估");
            assertThat(pdfText).contains("综合评分");
            assertThat(pdfText).contains("总分：92 / 100");
            assertThat(pdfText).contains("总体评价");
            assertThat(pdfText).contains("表达清晰，项目经验扎实");
            assertThat(pdfText).contains("表现优势");
            assertThat(pdfText).contains("项目经历具体");
            assertThat(pdfText).contains("改进建议");
            assertThat(pdfText).contains("补充压测数据");
            assertThat(pdfText).contains("问答详情");
            assertThat(pdfText).contains("问题 1 [项目经验]");
            assertThat(pdfText).contains("请介绍一下你做过的核心项目");
            assertThat(pdfText).contains("Q: 请介绍一下你做过的核心项目");
            assertThat(pdfText).contains("A: 我负责订单系统的架构设计与性能优化。");
            assertThat(pdfText).contains("得分: 95/100");
            assertThat(pdfText).contains("参考答案: 可以从背景、职责、难点和结果四个方面展开");
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

        @Test
        @DisplayName("面试会话为空时应抛出业务异常")
        void shouldThrowWhenInterviewSessionNull() {
            assertThatThrownBy(() -> pdfExportService.exportInterviewReport(null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
        }

        @Test
        @DisplayName("面试会话ID为空时应抛出业务异常")
        void shouldThrowWhenInterviewSessionIdBlank() {
            InterviewSessionEntity session = buildInterviewSessionSilently();
            session.setSessionId(" ");

            assertThatThrownBy(() -> pdfExportService.exportInterviewReport(session))
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

    private InterviewSessionEntity buildInterviewSession() throws JsonProcessingException {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId("session-1");
        session.setTotalQuestions(2);
        session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
        session.setCreatedAt(LocalDateTime.of(2026, 6, 23, 10, 0, 0));
        session.setCompletedAt(LocalDateTime.of(2026, 6, 23, 10, 30, 0));
        session.setOverallScore(92);
        session.setOverallFeedback("表达清晰，项目经验扎实");
        session.setStrengthsJson(objectMapper.writeValueAsString(List.of("项目经历具体", "回答结构完整")));
        session.setImprovementsJson(objectMapper.writeValueAsString(List.of("补充压测数据")));
        session.setAnswers(new ArrayList<>(List.of(
                buildInterviewAnswer(
                        0,
                        "请介绍一下你做过的核心项目",
                        "项目经验",
                        "我负责订单系统的架构设计与性能优化。",
                        95,
                        "回答完整，重点明确",
                        "可以从背景、职责、难点和结果四个方面展开"
                ),
                buildInterviewAnswer(
                        1,
                        "MySQL 索引失效的常见场景有哪些",
                        "MySQL",
                        "对索引列做函数操作、隐式类型转换都可能导致索引失效。",
                        89,
                        "覆盖了主要场景",
                        null
                )
        )));
        return session;
    }

    private InterviewSessionEntity buildInterviewSessionSilently() {
        try {
            return buildInterviewSession();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private InterviewAnswerEntity buildInterviewAnswer(
            int questionIndex,
            String question,
            String category,
            String userAnswer,
            Integer score,
            String feedback,
            String referenceAnswer
    ) {
        InterviewAnswerEntity answer = new InterviewAnswerEntity();
        answer.setQuestionIndex(questionIndex);
        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);
        answer.setReferenceAnswer(referenceAnswer);
        return answer;
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
