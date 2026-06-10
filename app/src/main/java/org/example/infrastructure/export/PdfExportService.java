package org.example.infrastructure.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.source.ByteArrayOutputStream;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.List;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.example.modules.resume.model.ResumeEntity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
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

    private final ObjectMapper objectMapper;

    /**
     * 导出简历分析报告为PDF
     *
     * @param resume   要导出的简历
     * @param analysis 要导出的简历分析结果
     * @return PDF文件字节数组
     */
    public byte[] exportResumeAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        validateExportParams(resume, analysis);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfFont font = loadPdfFont();
            try (PdfWriter writer = new PdfWriter(outputStream);
                 PdfDocument pdfDocument = new PdfDocument(writer);
                 Document document = new Document(pdfDocument)) {
                document.setFont(font);
                addTitle(document, resume);
                addBasicInfo(document, resume);
                addScoreSection(document, analysis);
                addSummarySection(document, analysis);
                addStrengthSection(document, analysis);
                addSuggestionSection(document, analysis);
                addOriginalTextSection(document, analysis);
            }
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

    private void validateExportParams(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        if (resume == null || resume.getId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "导出的简历不能为空");
        }
        if (analysis == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "导出的简历分析结果不能为空");
        }
    }

    private PdfFont loadPdfFont() throws IOException {
        ClassPathResource resource = new ClassPathResource(FONT_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            byte[] fontBytes = inputStream.readAllBytes();
            return PdfFontFactory.createFont(
                    fontBytes,
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
            Map<String, Object> detailMap = objectMapper.readValue(detailJson, LinkedHashMap.class);
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
        // 使用列表输出亮点，保证 PDF 阅读结构清晰。
        List list = new List().setFontSize(11);
        analysis.strengths().forEach(item -> list.add(defaultText(item)));
        document.add(list);
    }

    private void addSuggestionSection(Document document, ResumeAnalysisResponse analysis) {
        document.add(new Paragraph("改进建议").setBold().setFontSize(14).setMarginTop(10));
        if (analysis.suggestions() == null || analysis.suggestions().isEmpty()) {
            document.add(new Paragraph("暂无改进建议").setFontSize(11));
            return;
        }
        // 每条建议拆成独立段落，便于导出后直接打印或分享。
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

    private String defaultText(String text) {
        return StringUtils.hasText(text) ? text : "-";
    }
}
