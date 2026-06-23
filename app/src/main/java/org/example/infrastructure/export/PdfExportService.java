package org.example.infrastructure.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.source.ByteArrayOutputStream;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.example.modules.resume.model.ResumeEntity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF导出服务
 * 用于简历分析和面试报告的 PDF 导出服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExportService {
    private static final String FONT_PATH = "fonts/ZhuqueFangsong-Regular.ttf";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DeviceRgb HEADER_COLOR = new DeviceRgb(41, 128, 185);
    private static final DeviceRgb SECTION_COLOR = new DeviceRgb(52, 73, 94);
    private static final DeviceRgb HIGH_SCORE_COLOR = new DeviceRgb(39, 174, 96);

    private final ObjectMapper objectMapper;

    /**
     * 导出简历分析报告为PDF
     *
     * @param resume 要导出的简历
     * @param analysis 要导出的简历分析结果
     * @return PDF文件字节数组
     */
    public byte[] exportResumeAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        validateExportParams(resume, analysis);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             PdfWriter writer = new PdfWriter(outputStream);
             PdfDocument pdfDocument = new PdfDocument(writer);
             Document document = new Document(pdfDocument)) {
            document.setFont(loadPdfFont());
            addTitle(document, resume);
            addBasicInfo(document, resume);
            addScoreSection(document, analysis);
            addSummarySection(document, analysis);
            addStrengthSection(document, analysis);
            addSuggestionSection(document, analysis);
            addOriginalTextSection(document, analysis);
            return outputStream.toByteArray();
        } catch (IOException e) {
            log.error(
                    "导出简历分析PDF失败: method=exportResumeAnalysis, resumeId={}, filename={}",
                    resume.getId(),
                    resume.getOriginalFilename(),
                    e
            );
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出简历分析PDF失败", e);
        }
    }

    /**
     * 导出面试报告为PDF
     */
    public byte[] exportInterviewReport(InterviewSessionEntity session) {
        validateInterviewSession(session);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             PdfWriter writer = new PdfWriter(outputStream);
             PdfDocument pdfDocument = new PdfDocument(writer);
             Document document = new Document(pdfDocument)) {
            document.setFont(loadPdfFont());
            addInterviewTitle(document);
            addInterviewMeta(document, session);
            addInterviewScore(document, session);
            addInterviewFeedback(document, session);
            addInterviewStrengths(document, session);
            addInterviewImprovements(document, session);
            addInterviewAnswers(document, session);
            return outputStream.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("导出面试报告PDF失败: sessionId={}", session.getSessionId(), e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出面试报告PDF失败", e);
        }
    }

    private void validateExportParams(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        if (resume == null || resume.getId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "导出的简历不能为空");
        }
        if (analysis == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "导出的简历分析结果不能为空");
        }
    }

    private void validateInterviewSession(InterviewSessionEntity session) {
        if (session == null || !StringUtils.hasText(session.getSessionId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "导出的面试会话不能为空");
        }
    }

    private PdfFont loadPdfFont() throws IOException {
        ClassPathResource resource = new ClassPathResource(FONT_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return PdfFontFactory.createFont(
                    inputStream.readAllBytes(),
                    PdfEncodings.IDENTITY_H,
                    EmbeddingStrategy.PREFER_EMBEDDED
            );
        }
    }

    private void addTitle(Document document, ResumeEntity resume) {
        document.add(
                new Paragraph("简历分析报告")
                        .setBold()
                        .setFontSize(18)
                        .setTextAlignment(TextAlignment.CENTER)
        );
        document.add(
                new Paragraph("文件名：" + defaultText(resume.getOriginalFilename()))
                        .setFontSize(11)
                        .setMarginBottom(12)
        );
    }

    private void addBasicInfo(Document document, ResumeEntity resume) {
        Map<String, String> infoMap = new LinkedHashMap<>();
        infoMap.put("简历ID", String.valueOf(resume.getId()));
        infoMap.put("文件类型", defaultText(resume.getContentType()));
        infoMap.put("文件大小", resume.getFileSize() == null ? "-" : resume.getFileSize() + " bytes");
        infoMap.put("存储Key", defaultText(resume.getStorageKey()));
        infoMap.put("访问地址", defaultText(resume.getStorageUrl()));
        addKeyValueSection(document, "简历基础信息", infoMap);
    }

    private void addScoreSection(Document document, ResumeAnalysisResponse analysis) {
        Map<String, String> scoreMap = new LinkedHashMap<>();
        scoreMap.put("综合评分", String.valueOf(analysis.overallScore()));
        scoreMap.putAll(buildScoreDetailMap(analysis));
        addKeyValueSection(document, "评分结果", scoreMap);
    }

    private Map<String, String> buildScoreDetailMap(ResumeAnalysisResponse analysis) {
        if (analysis.scoreDetail() == null) {
            return buildDefaultScoreDetailMap();
        }
        try {
            String detailJson = objectMapper.writeValueAsString(analysis.scoreDetail());
            Map<String, Object> detailMap = objectMapper.readValue(
                    detailJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {}
            );
            Map<String, String> result = new LinkedHashMap<>();
            result.put("内容完整性", String.valueOf(detailMap.getOrDefault("contentScore", 0)));
            result.put("结构清晰度", String.valueOf(detailMap.getOrDefault("structureScore", 0)));
            result.put("技能匹配度", String.valueOf(detailMap.getOrDefault("skillMatchScore", 0)));
            result.put("表达专业性", String.valueOf(detailMap.getOrDefault("expressionScore", 0)));
            result.put("项目经验", String.valueOf(detailMap.getOrDefault("projectScore", 0)));
            return result;
        } catch (JsonProcessingException e) {
            log.error("构建PDF评分明细失败: method=buildScoreDetailMap", e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "构建PDF评分明细失败", e);
        }
    }

    private Map<String, String> buildDefaultScoreDetailMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("内容完整性", "0");
        result.put("结构清晰度", "0");
        result.put("技能匹配度", "0");
        result.put("表达专业性", "0");
        result.put("项目经验", "0");
        return result;
    }

    private void addSummarySection(Document document, ResumeAnalysisResponse analysis) {
        addTextSection(document, "分析总结", analysis.summary());
    }

    private void addStrengthSection(Document document, ResumeAnalysisResponse analysis) {
        document.add(new Paragraph("亮点总结").setBold().setFontSize(14).setMarginTop(10));
        if (analysis.strengths() == null || analysis.strengths().isEmpty()) {
            document.add(new Paragraph("暂无亮点总结").setFontSize(11));
            return;
        }
        com.itextpdf.layout.element.List list = new com.itextpdf.layout.element.List().setFontSize(11);
        analysis.strengths().forEach(item -> list.add(defaultText(item)));
        document.add(list);
    }

    private void addSuggestionSection(Document document, ResumeAnalysisResponse analysis) {
        document.add(new Paragraph("改进建议").setBold().setFontSize(14).setMarginTop(10));
        if (analysis.suggestions() == null || analysis.suggestions().isEmpty()) {
            document.add(new Paragraph("暂无改进建议").setFontSize(11));
            return;
        }
        for (ResumeAnalysisResponse.Suggestion suggestion : analysis.suggestions()) {
            document.add(new Paragraph(buildSuggestionTitle(suggestion)).setBold().setFontSize(11));
            document.add(new Paragraph("问题：" + defaultText(suggestion.issue())).setFontSize(11));
            document.add(
                    new Paragraph("建议：" + defaultText(suggestion.recommendation()))
                            .setFontSize(11)
                            .setMarginBottom(6)
            );
        }
    }

    private String buildSuggestionTitle(ResumeAnalysisResponse.Suggestion suggestion) {
        return String.format(
                "%s（优先级：%s）",
                defaultText(suggestion.category()),
                defaultText(suggestion.priority())
        );
    }

    private void addOriginalTextSection(Document document, ResumeAnalysisResponse analysis) {
        addTextSection(document, "简历原文", analysis.originalText());
    }

    private void addKeyValueSection(Document document, String title, Map<String, String> values) {
        document.add(new Paragraph(title).setBold().setFontSize(14).setMarginTop(10));
        values.forEach((key, value) ->
                document.add(new Paragraph(key + "：" + defaultText(value)).setFontSize(11))
        );
    }

    private void addTextSection(Document document, String title, String content) {
        document.add(new Paragraph(title).setBold().setFontSize(14).setMarginTop(10));
        document.add(new Paragraph(defaultText(content)).setFontSize(11));
    }

    private void addInterviewTitle(Document document) {
        document.add(
                new Paragraph("模拟面试报告")
                        .setFontSize(24)
                        .setBold()
                        .setTextAlignment(TextAlignment.CENTER)
                        .setFontColor(HEADER_COLOR)
        );
    }

    private void addInterviewMeta(Document document, InterviewSessionEntity session) {
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("面试信息"));
        document.add(new Paragraph("会话ID：" + session.getSessionId()));
        document.add(new Paragraph("题目数量：" + session.getTotalQuestions()));
        document.add(new Paragraph("面试状态：" + getStatusText(session.getStatus())));
        document.add(new Paragraph("开始时间：" + formatDateTime(session.getCreatedAt())));
        if (session.getCompletedAt() != null) {
            document.add(new Paragraph("完成时间：" + formatDateTime(session.getCompletedAt())));
        }
    }

    private void addInterviewScore(Document document, InterviewSessionEntity session) {
        if (session.getOverallScore() == null) {
            return;
        }
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("综合评分"));
        document.add(
                new Paragraph("总分：" + session.getOverallScore() + " / 100")
                        .setFontSize(18)
                        .setBold()
                        .setFontColor(getScoreColor(session.getOverallScore()))
        );
    }

    private void addInterviewFeedback(Document document, InterviewSessionEntity session) {
        if (!StringUtils.hasText(session.getOverallFeedback())) {
            return;
        }
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("总体评价"));
        document.add(new Paragraph(sanitizeText(session.getOverallFeedback())));
    }

    private void addInterviewStrengths(Document document, InterviewSessionEntity session) {
        List<String> strengths = parseStringList(session.getStrengthsJson(), session.getSessionId(), "strengthsJson");
        if (strengths.isEmpty()) {
            return;
        }
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("表现优势"));
        strengths.forEach(item -> document.add(new Paragraph("• " + sanitizeText(item))));
    }

    private void addInterviewImprovements(Document document, InterviewSessionEntity session) {
        List<String> improvements = parseStringList(
                session.getImprovementsJson(),
                session.getSessionId(),
                "improvementsJson"
        );
        if (improvements.isEmpty()) {
            return;
        }
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("改进建议"));
        improvements.forEach(item -> document.add(new Paragraph("• " + sanitizeText(item))));
    }

    private void addInterviewAnswers(Document document, InterviewSessionEntity session) {
        List<InterviewAnswerEntity> answers = session.getAnswers();
        if (answers == null || answers.isEmpty()) {
            return;
        }
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("问答详情"));
        for (InterviewAnswerEntity answer : answers) {
            document.add(new Paragraph("\n"));
            document.add(
                    new Paragraph(buildQuestionTitle(answer))
                            .setBold()
                            .setFontSize(12)
            );
            document.add(new Paragraph("Q: " + sanitizeText(answer.getQuestion())));
            document.add(new Paragraph("A: " + sanitizeText(defaultAnswerText(answer.getUserAnswer()))));
            if (answer.getScore() != null) {
                document.add(
                        new Paragraph("得分: " + answer.getScore() + "/100")
                                .setFontColor(getScoreColor(answer.getScore()))
                );
            }
            if (StringUtils.hasText(answer.getFeedback())) {
                document.add(new Paragraph("评价: " + sanitizeText(answer.getFeedback())).setItalic());
            }
            if (StringUtils.hasText(answer.getReferenceAnswer())) {
                document.add(
                        new Paragraph("参考答案: " + sanitizeText(answer.getReferenceAnswer()))
                                .setFontColor(HIGH_SCORE_COLOR)
                );
            }
        }
    }

    private List<String> parseStringList(String json, String sessionId, String fieldName) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("解析面试报告JSON失败: sessionId={}, fieldName={}", sessionId, fieldName, e);
            return List.of();
        }
    }

    private Paragraph createSectionTitle(String title) {
        return new Paragraph(title)
                .setFontSize(14)
                .setBold()
                .setFontColor(SECTION_COLOR)
                .setMarginTop(10);
    }

    private DeviceRgb getScoreColor(int score) {
        if (score >= 80) {
            return HIGH_SCORE_COLOR;
        }
        if (score >= 60) {
            return new DeviceRgb(241, 196, 15);
        }
        return new DeviceRgb(231, 76, 60);
    }

    private String getStatusText(InterviewSessionEntity.SessionStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case CREATED -> "已创建";
            case IN_PROGRESS -> "进行中";
            case COMPLETED -> "已完成";
            case EVALUATED -> "已评估";
        };
    }

    private String sanitizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("[\\p{So}\\p{Cs}]", "").trim();
    }

    private String defaultText(String text) {
        return StringUtils.hasText(text) ? text : "-";
    }

    private String defaultAnswerText(String answer) {
        return StringUtils.hasText(answer) ? answer : "未回答";
    }

    private String formatDateTime(java.time.LocalDateTime time) {
        return time == null ? "未知" : DATE_FORMAT.format(time);
    }

    private String buildQuestionTitle(InterviewAnswerEntity answer) {
        int questionIndex = answer.getQuestionIndex() == null ? 0 : answer.getQuestionIndex() + 1;
        String category = StringUtils.hasText(answer.getCategory()) ? answer.getCategory() : "综合";
        return "问题 " + questionIndex + " [" + category + "]";
    }
}
