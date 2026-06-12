package org.example.modules.resume.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.export.PdfExportService;
import org.example.infrastructure.mapper.InterviewMapper;
import org.example.infrastructure.mapper.ResumeMapper;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.example.modules.resume.model.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;


/**
 * 简历历史服务
 * 简历历史和导出简历分析报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeHistoryService {
    private final ResumePersistenceService resumePersistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final InterviewMapper interviewMapper;

    /**
     * 获取所有简历列表
     */
    public List<ResumeListItemDTO> getAllResumes() {
        List<ResumeEntity> resumes = resumePersistenceService.getAllResumes();
        List<ResumeListItemDTO> result = new ArrayList<>(resumes.size());
        for (ResumeEntity resume : resumes) {
            // 这里复用已有持久化服务获取最新分析与面试次数，避免在 Service 层直接拼底层查询。
            List<ResumeAnalysisEntity> analyses =
                    resumePersistenceService.findAnalysesByResumeId(resume.getId());
            List<InterviewSessionEntity> interviews =
                    interviewPersistenceService.findByResumeId(resume.getId());
            result.add(buildResumeListItem(resume, analyses, interviews.size()));
        }
        log.info("获取简历历史列表完成: count={}", result.size());
        return result;
    }

    /**
     * 获取简历详情（包含分析历史）
     */
    public ResumeDetailDTO getResumeDetail(Long resumeId) {
        ResumeEntity resume = resumePersistenceService.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        List<ResumeAnalysisEntity> analyses = resumePersistenceService.findAnalysesByResumeId(resumeId);
        // 先复用已有反序列化逻辑还原 strengths / suggestions，再映射为详情页历史记录。
        List<ResumeDetailDTO.AnalysisHistoryDTO> analysisHistory = analyses.stream()
                .map(analysis -> {
                    ResumeAnalysisResponse response = resumePersistenceService.entityToDTO(analysis);
                    return resumeMapper.toAnalysisHistoryDTO(
                            analysis,
                            response.strengths(),
                            response.suggestions().stream().map(suggestion -> (Object) suggestion).toList()
                    );
                })
                .toList();
        // 面试历史直接复用 InterviewMapper 提供的简要历史结构，避免重复组装字段。
        List<Object> interviews = interviewMapper.toInterviewHistoryList(
                interviewPersistenceService.findByResumeId(resumeId)
        );
        ResumeDetailDTO detail = resumeMapper.toDetailDTOBasic(resume);
        log.info(
                "获取简历详情完成: resumeId={}, analysisCount={}, interviewCount={}",
                resumeId,
                analysisHistory.size(),
                interviews.size()
        );
        return new ResumeDetailDTO(
                detail.id(),
                detail.filename(),
                detail.fileSize(),
                detail.contentType(),
                detail.storageUrl(),
                detail.uploadedAt(),
                detail.accessCount(),
                detail.resumeText(),
                detail.analyzeStatus(),
                detail.analyzeError(),
                analysisHistory,
                interviews
        );
    }

    /**
     * 导出简历分析报告为PDF
     */
    public ExportResult exportResumeAnalysisReport(Long resumeId) {
        ResumeEntity resume = resumePersistenceService.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        ResumeAnalysisResponse analysis = resumePersistenceService.getLatestAnalysisDTO(resumeId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESUME_ANALYSIS_NOT_FOUND,
                        "简历分析结果不存在"
                ));
        // 直接复用现有 PDF 导出服务，导出文件名按“原文件名 + 报告后缀”生成。
        byte[] pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysis);
        String originalFilename = resume.getOriginalFilename() == null
                ? "resume"
                : resume.getOriginalFilename();
        int extensionIndex = originalFilename.lastIndexOf('.');
        String baseFilename = extensionIndex > 0
                ? originalFilename.substring(0, extensionIndex)
                : originalFilename;
        String filename = baseFilename + "-analysis-report.pdf";
        log.info("导出简历分析报告完成: resumeId={}, filename={}", resumeId, filename);
        return new ExportResult(pdfBytes, filename);
    }

    /**
     * PDF导出结果
     */
    public record ExportResult(byte[] pdfBytes, String filename) {}

    private ResumeListItemDTO buildResumeListItem(
            ResumeEntity resume,
            List<ResumeAnalysisEntity> analyses,
            int interviewCount
    ) {
        ResumeAnalysisEntity latestAnalysis = analyses.isEmpty() ? null : analyses.getFirst();
        return new ResumeListItemDTO(
                resume.getId(),
                resume.getOriginalFilename(),
                resume.getFileSize(),
                resume.getUploadedAt(),
                resume.getAccessCount(),
                latestAnalysis != null ? latestAnalysis.getOverallScore() : null,
                latestAnalysis != null ? latestAnalysis.getAnalyzedAt() : null,
                interviewCount,
                resume.getAnalyzeStatus(),
                resume.getAnalyzeError()
        );
    }


}
