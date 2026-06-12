package org.example.modules.resume.service;

import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.export.PdfExportService;
import org.example.infrastructure.mapper.InterviewMapper;
import org.example.infrastructure.mapper.ResumeMapper;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.example.modules.resume.model.ResumeAnalysisEntity;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.example.modules.resume.model.ResumeDetailDTO;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.model.ResumeListItemDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历历史服务测试")
class ResumeHistoryServiceTest {

    @Mock
    private ResumePersistenceService resumePersistenceService;

    @Mock
    private InterviewPersistenceService interviewPersistenceService;

    @Mock
    private PdfExportService pdfExportService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ResumeMapper resumeMapper;

    @Mock
    private InterviewMapper interviewMapper;

    @InjectMocks
    private ResumeHistoryService resumeHistoryService;

    @Nested
    @DisplayName("查询简历列表")
    class GetAllResumesTest {

        /**
         * 验证会聚合每份简历的最新分析结果和面试次数。
         */
        @Test
        @DisplayName("应返回包含分析摘要和面试次数的简历列表")
        void shouldReturnResumeListWithLatestAnalysisAndInterviewCount() {
            ResumeEntity firstResume = buildResumeEntity(1L, "resume-a.pdf");
            ResumeEntity secondResume = buildResumeEntity(2L, "resume-b.pdf");
            ResumeAnalysisEntity latestAnalysis = buildAnalysisEntity(11L, 88);
            InterviewSessionEntity interviewOne = buildInterviewSession(101L, "session-1");
            InterviewSessionEntity interviewTwo = buildInterviewSession(102L, "session-2");
            when(resumePersistenceService.getAllResumes()).thenReturn(List.of(firstResume, secondResume));
            when(resumePersistenceService.findAnalysesByResumeId(1L)).thenReturn(List.of(latestAnalysis));
            when(resumePersistenceService.findAnalysesByResumeId(2L)).thenReturn(List.of());
            when(interviewPersistenceService.findByResumeId(1L)).thenReturn(List.of(interviewOne, interviewTwo));
            when(interviewPersistenceService.findByResumeId(2L)).thenReturn(List.of());

            // 触发列表查询，验证每条记录都按当前服务逻辑补齐摘要字段。
            List<ResumeListItemDTO> result = resumeHistoryService.getAllResumes();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo(1L);
            assertThat(result.get(0).latestScore()).isEqualTo(88);
            assertThat(result.get(0).interviewCount()).isEqualTo(2);
            assertThat(result.get(0).analyzeStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
            assertThat(result.get(1).id()).isEqualTo(2L);
            assertThat(result.get(1).latestScore()).isNull();
            assertThat(result.get(1).interviewCount()).isZero();
        }
    }

    @Nested
    @DisplayName("查询简历详情")
    class GetResumeDetailTest {

        /**
         * 验证会组装基础详情、分析历史和面试历史。
         */
        @Test
        @DisplayName("应返回包含分析历史和面试历史的简历详情")
        void shouldReturnResumeDetailWithAnalysesAndInterviews() {
            ResumeEntity resume = buildResumeEntity(1L, "resume.pdf");
            ResumeAnalysisEntity analysis = buildAnalysisEntity(21L, 90);
            ResumeAnalysisResponse response = new ResumeAnalysisResponse(
                    90,
                    new ResumeAnalysisResponse.ScoreDetail(20, 18, 19, 16, 17),
                    "分析总结",
                    List.of("亮点一"),
                    List.of(new ResumeAnalysisResponse.Suggestion("内容", "高", "问题", "建议")),
                    "resume text"
            );
            ResumeDetailDTO.AnalysisHistoryDTO historyDTO = new ResumeDetailDTO.AnalysisHistoryDTO(
                    21L,
                    90,
                    20,
                    18,
                    19,
                    16,
                    17,
                    "分析总结",
                    LocalDateTime.of(2026, 6, 12, 10, 0),
                    List.of("亮点一"),
                    List.of(Map.of("category", "内容"))
            );
            ResumeDetailDTO baseDetail = new ResumeDetailDTO(
                    1L,
                    "resume.pdf",
                    2048L,
                    "application/pdf",
                    "http://example.com/resume.pdf",
                    LocalDateTime.of(2026, 6, 10, 9, 0),
                    3,
                    "resume text",
                    AsyncTaskStatus.COMPLETED,
                    null,
                    List.of(),
                    List.of()
            );
            InterviewSessionEntity interview = buildInterviewSession(201L, "session-201");
            List<Object> interviewHistory = List.of(Map.of("sessionId", "session-201", "overallScore", 85));
            when(resumePersistenceService.findById(1L)).thenReturn(Optional.of(resume));
            when(resumePersistenceService.findAnalysesByResumeId(1L)).thenReturn(List.of(analysis));
            when(resumePersistenceService.entityToDTO(analysis)).thenReturn(response);
            when(resumeMapper.toAnalysisHistoryDTO(
                    eq(analysis),
                    eq(List.of("亮点一")),
                    any(List.class)
            )).thenReturn(historyDTO);
            when(interviewPersistenceService.findByResumeId(1L)).thenReturn(List.of(interview));
            when(interviewMapper.toInterviewHistoryList(List.of(interview))).thenReturn(interviewHistory);
            when(resumeMapper.toDetailDTOBasic(resume)).thenReturn(baseDetail);

            // 详情页会依次拼接基础简历信息、分析历史和面试历史。
            ResumeDetailDTO result = resumeHistoryService.getResumeDetail(1L);

            assertThat(result.id()).isEqualTo(1L);
            assertThat(result.filename()).isEqualTo("resume.pdf");
            assertThat(result.resumeText()).isEqualTo("resume text");
            assertThat(result.analyses()).hasSize(1);
            assertThat(result.analyses().getFirst().overallScore()).isEqualTo(90);
            assertThat(result.interviews()).containsExactlyElementsOf(interviewHistory);
        }

        /**
         * 验证简历不存在时会抛出业务异常。
         */
        @Test
        @DisplayName("简历不存在时应抛出业务异常")
        void shouldThrowWhenResumeNotFound() {
            when(resumePersistenceService.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> resumeHistoryService.getResumeDetail(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
        }
    }

    @Nested
    @DisplayName("导出分析报告")
    class ExportResumeAnalysisReportTest {

        /**
         * 验证会调用 PDF 导出服务并生成带后缀的下载文件名。
         */
        @Test
        @DisplayName("应导出简历分析报告PDF")
        void shouldExportResumeAnalysisReport() {
            ResumeEntity resume = buildResumeEntity(1L, "resume.pdf");
            ResumeAnalysisResponse response = new ResumeAnalysisResponse(
                    85,
                    new ResumeAnalysisResponse.ScoreDetail(18, 17, 18, 16, 16),
                    "总结",
                    List.of("亮点"),
                    List.of(),
                    "resume text"
            );
            byte[] pdfBytes = new byte[]{1, 2, 3};
            when(resumePersistenceService.findById(1L)).thenReturn(Optional.of(resume));
            when(resumePersistenceService.getLatestAnalysisDTO(1L)).thenReturn(Optional.of(response));
            when(pdfExportService.exportResumeAnalysis(resume, response)).thenReturn(pdfBytes);

            // 导出结果应包含 PDF 二进制和基于原文件名生成的下载名。
            ResumeHistoryService.ExportResult result =
                    resumeHistoryService.exportResumeAnalysisReport(1L);

            assertThat(result.pdfBytes()).isEqualTo(pdfBytes);
            assertThat(result.filename()).isEqualTo("resume-analysis-report.pdf");
            verify(pdfExportService).exportResumeAnalysis(resume, response);
        }

        /**
         * 验证没有分析结果时会抛出业务异常。
         */
        @Test
        @DisplayName("分析结果不存在时应抛出业务异常")
        void shouldThrowWhenAnalysisResultNotFound() {
            ResumeEntity resume = buildResumeEntity(1L, "resume.pdf");
            when(resumePersistenceService.findById(1L)).thenReturn(Optional.of(resume));
            when(resumePersistenceService.getLatestAnalysisDTO(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> resumeHistoryService.exportResumeAnalysisReport(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_ANALYSIS_NOT_FOUND.getCode()));
        }
    }

    private ResumeEntity buildResumeEntity(Long id, String filename) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(id);
        resume.setOriginalFilename(filename);
        resume.setFileSize(2048L);
        resume.setContentType("application/pdf");
        resume.setStorageUrl("http://example.com/" + filename);
        resume.setResumeText("resume text");
        resume.setUploadedAt(LocalDateTime.of(2026, 6, 10, 9, 0));
        resume.setAccessCount(3);
        resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
        resume.setAnalyzeError(null);
        return resume;
    }

    private ResumeAnalysisEntity buildAnalysisEntity(Long id, int overallScore) {
        ResumeAnalysisEntity entity = new ResumeAnalysisEntity();
        entity.setId(id);
        entity.setOverallScore(overallScore);
        entity.setAnalyzedAt(LocalDateTime.of(2026, 6, 12, 10, 0));
        return entity;
    }

    private InterviewSessionEntity buildInterviewSession(Long id, String sessionId) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(id);
        session.setSessionId(sessionId);
        session.setOverallScore(85);
        session.setCreatedAt(LocalDateTime.of(2026, 6, 11, 14, 0));
        return session;
    }
}
