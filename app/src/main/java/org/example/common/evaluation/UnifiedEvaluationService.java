package org.example.common.evaluation;

import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.config.InterviewEvaluationProperties;
import org.example.common.model.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 统一面试评估服务
 * 复用文字面试和语音面试的评估流程：
 * 分批评估 + 结构化输出 + 总结汇总 + 失败兜底
 */
@Service
public class UnifiedEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;
    private static final int MAX_RESUME_TEXT_CHARS = 3000;
    private static final String DEFAULT_SESSION_ID = "未知会话";
    private static final String DEFAULT_CATEGORY = "未分类";
    private static final String DEFAULT_RESUME_TEXT = "暂无简历信息。";
    private static final String DEFAULT_REFERENCE_CONTEXT = "暂无参考答案基线。";
    private static final String DEFAULT_ANSWER = "未作答";
    private static final String DEFAULT_BATCH_FEEDBACK =
            "本批次使用兜底规则完成评估。";
    private static final String DEFAULT_STRENGTH =
            "回答中体现出一定的相关技术知识。";
    private static final String DEFAULT_IMPROVEMENT =
            "建议补充更具体的实现细节、边界条件和取舍分析。";
    private static final String DEFAULT_EMPTY_FEEDBACK =
            "本次面试暂无可评估的问答记录。";
    private static final String DEFAULT_EMPTY_IMPROVEMENT =
            "请至少提供一道有效作答后再进行评估。";
    private static final String INVALID_PREFIX_1 = "不知道";
    private static final String INVALID_PREFIX_2 = "不清楚";
    private static final String INVALID_PREFIX_3 = "不会";
    private static final String INVALID_PREFIX_4 = "忘了";
    private static final String INVALID_PREFIX_5 = "没学过";
    private static final String INVALID_PREFIX_6 = "跳过";

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;

    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties
    ) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
                loadPrompt(resourceLoader, evaluationProperties.getSystemPromptPath())
        );
        this.userPromptTemplate = new PromptTemplate(
                loadPrompt(resourceLoader, evaluationProperties.getUserPromptPath())
        );
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(
                loadPrompt(resourceLoader, evaluationProperties.getSummarySystemPromptPath())
        );
        this.summaryUserPromptTemplate = new PromptTemplate(
                loadPrompt(resourceLoader, evaluationProperties.getSummaryUserPromptPath())
        );
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
    }

    private record BatchReportDTO(
            int overallScore,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<QuestionEvalDTO> questionEvaluations
    ) {}

    private record QuestionEvalDTO(
            int questionIndex,
            int score,
            String feedback,
            String referenceAnswer,
            List<String> keyPoints
    ) {}

    private record SummaryDTO(
            String overallFeedback,
            List<String> strengths,
            List<String> improvements
    ) {}

    private record MergedEvaluation(
            int overallScore,
            List<EvaluationReport.CategoryScore> categoryScores,
            List<EvaluationReport.QuestionEvaluation> questionDetails,
            List<EvaluationReport.ReferenceAnswer> referenceAnswers,
            List<String> fallbackStrengths,
            List<String> fallbackImprovements
    ) {}

    private String loadPrompt(ResourceLoader resourceLoader, String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 评估面试问答记录
     */
    public EvaluationReport evaluate(
            ChatClient chatClient,
            String sessionId,
            List<QaRecord> qaRecords,
            String resumeText
    ) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    public EvaluationReport evaluate(
            ChatClient chatClient,
            String sessionId,
            List<QaRecord> qaRecords,
            String resumeText,
            String referenceContext
    ) {
        String safeSessionId = StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
        List<QaRecord> records = normalizeQaRecords(qaRecords);
        if (records.isEmpty()) {
            return buildEmptyReport(safeSessionId);
        }

        String normalizedResumeText = truncateText(resumeText, MAX_RESUME_TEXT_CHARS);
        String normalizedReferenceContext = truncateText(referenceContext, MAX_REFERENCE_CONTEXT_CHARS);
        List<BatchReportDTO> batchReports = evaluateBatches(
                chatClient,
                safeSessionId,
                splitIntoBatches(records),
                normalizedResumeText,
                normalizedReferenceContext
        );
        MergedEvaluation merged = mergeBatchReports(records, batchReports);
        SummaryDTO summary = summarizeEvaluation(
                chatClient,
                safeSessionId,
                normalizedResumeText,
                normalizedReferenceContext,
                merged
        );
        return buildReport(safeSessionId, records.size(), merged, summary);
    }

    private EvaluationReport buildReport(
            String sessionId,
            int totalQuestions,
            MergedEvaluation merged,
            SummaryDTO summary
    ) {
        String overallFeedback = summary == null
                ? buildFallbackOverallFeedback(merged.overallScore())
                : safeText(summary.overallFeedback(), buildFallbackOverallFeedback(merged.overallScore()));
        List<String> strengths = summary == null
                ? merged.fallbackStrengths()
                : ensureSummaryList(summary.strengths(), merged.fallbackStrengths(), DEFAULT_STRENGTH);
        List<String> improvements = summary == null
                ? merged.fallbackImprovements()
                : ensureSummaryList(summary.improvements(), merged.fallbackImprovements(), DEFAULT_IMPROVEMENT);
        return new EvaluationReport(
                sessionId,
                totalQuestions,
                merged.overallScore(),
                merged.categoryScores(),
                merged.questionDetails(),
                overallFeedback,
                strengths,
                improvements,
                merged.referenceAnswers()
        );
    }

    private List<QaRecord> normalizeQaRecords(List<QaRecord> qaRecords) {
        if (qaRecords == null || qaRecords.isEmpty()) {
            return List.of();
        }
        return qaRecords.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(QaRecord::questionIndex))
                .toList();
    }

    private String truncateText(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private List<List<QaRecord>> splitIntoBatches(List<QaRecord> qaRecords) {
        List<List<QaRecord>> batches = new ArrayList<>();
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            batches.add(qaRecords.subList(start, end));
        }
        return batches;
    }

    private List<BatchReportDTO> evaluateBatches(
            ChatClient chatClient,
            String sessionId,
            List<List<QaRecord>> batches,
            String resumeText,
            String referenceContext
    ) {
        List<BatchReportDTO> reports = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            reports.add(evaluateBatchSafely(
                    chatClient,
                    sessionId,
                    i + 1,
                    batches.get(i),
                    resumeText,
                    referenceContext
            ));
        }
        return reports;
    }

    private BatchReportDTO evaluateBatchSafely(
            ChatClient chatClient,
            String sessionId,
            int batchIndex,
            List<QaRecord> batch,
            String resumeText,
            String referenceContext
    ) {
        try {
            BatchReportDTO report = evaluateSingleBatch(
                    chatClient,
                    sessionId,
                    batchIndex,
                    batch,
                    resumeText,
                    referenceContext
            );
            return report == null ? buildFallbackBatchReport(batch) : report;
        } catch (Exception e) {
            log.error(
                    "面试批次评估失败: sessionId={}, batchIndex={}, batchSize={}",
                    sessionId,
                    batchIndex,
                    batch.size(),
                    e
            );
            return buildFallbackBatchReport(batch);
        }
    }

    private BatchReportDTO evaluateSingleBatch(
            ChatClient chatClient,
            String sessionId,
            int batchIndex,
            List<QaRecord> batch,
            String resumeText,
            String referenceContext
    ) {
        String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
        String userPrompt = userPromptTemplate.render(buildBatchPromptParams(batch, resumeText, referenceContext));
        log.info(
                "开始执行面试批次评估: sessionId={}, batchIndex={}, batchSize={}",
                sessionId,
                batchIndex,
                batch.size()
        );
        return structuredOutputInvoker.invoke(
                chatClient,
                systemPrompt,
                userPrompt,
                outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试批次评估失败: ",
                "interview_evaluation_batch_" + batchIndex,
                log
        );
    }

    private Map<String, Object> buildBatchPromptParams(
            List<QaRecord> batch,
            String resumeText,
            String referenceContext
    ) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("resumeText", StringUtils.hasText(resumeText) ? resumeText : DEFAULT_RESUME_TEXT);
        params.put("qaRecords", buildQaRecordText(batch));
        params.put(
                "referenceContext",
                StringUtils.hasText(referenceContext) ? referenceContext : DEFAULT_REFERENCE_CONTEXT
        );
        return params;
    }

    private String buildQaRecordText(List<QaRecord> batch) {
        StringBuilder builder = new StringBuilder();
        for (QaRecord record : batch) {
            builder.append("Q").append(record.questionIndex()).append('\n')
                    .append("分类: ").append(safeText(record.category(), DEFAULT_CATEGORY)).append('\n')
                    .append("问题: ").append(safeText(record.question(), "")).append('\n')
                    .append("回答: ").append(safeText(record.userAnswer(), DEFAULT_ANSWER)).append("\n\n");
        }
        return builder.toString().strip();
    }

    private BatchReportDTO buildFallbackBatchReport(List<QaRecord> batch) {
        List<QuestionEvalDTO> evaluations = batch.stream()
                .map(this::buildFallbackQuestionEval)
                .toList();
        int averageScore = evaluations.isEmpty()
                ? 0
                : evaluations.stream().mapToInt(QuestionEvalDTO::score).sum() / evaluations.size();
        return new BatchReportDTO(
                averageScore,
                DEFAULT_BATCH_FEEDBACK,
                List.of(DEFAULT_STRENGTH),
                List.of(DEFAULT_IMPROVEMENT),
                evaluations
        );
    }

    private QuestionEvalDTO buildFallbackQuestionEval(QaRecord record) {
        return new QuestionEvalDTO(
                record.questionIndex(),
                fallbackScore(record.userAnswer()),
                fallbackFeedback(record.userAnswer()),
                "",
                List.of()
        );
    }

    private int fallbackScore(String userAnswer) {
        if (!StringUtils.hasText(userAnswer)) {
            return 0;
        }
        String normalized = userAnswer.strip();
        if (isInvalidAnswer(normalized)) {
            return 0;
        }
        if (normalized.length() < 20) {
            return 35;
        }
        return normalized.length() < 80 ? 55 : 65;
    }

    private String fallbackFeedback(String userAnswer) {
        if (!StringUtils.hasText(userAnswer) || isInvalidAnswer(userAnswer.strip())) {
            return "回答未体现有效的技术内容，无法支撑该题评估。";
        }
        if (userAnswer.strip().length() < 20) {
            return "回答较短，缺少关键原理、边界条件或实现细节。";
        }
        return "回答包含一定内容，但兜底评估无法进一步确认其准确性和完整性。";
    }

    private boolean isInvalidAnswer(String userAnswer) {
        String normalized = userAnswer.toLowerCase();
        return normalized.contains(INVALID_PREFIX_1)
                || normalized.contains(INVALID_PREFIX_2)
                || normalized.contains(INVALID_PREFIX_3)
                || normalized.contains(INVALID_PREFIX_4)
                || normalized.contains(INVALID_PREFIX_5)
                || normalized.contains(INVALID_PREFIX_6);
    }

    private MergedEvaluation mergeBatchReports(List<QaRecord> qaRecords, List<BatchReportDTO> batchReports) {
        Map<Integer, QuestionEvalDTO> evaluationMap = new LinkedHashMap<>();
        List<String> fallbackStrengths = new ArrayList<>();
        List<String> fallbackImprovements = new ArrayList<>();
        for (BatchReportDTO report : batchReports) {
            collectBatchSignals(report, fallbackStrengths, fallbackImprovements, evaluationMap);
        }
        return buildMergedEvaluation(qaRecords, evaluationMap, fallbackStrengths, fallbackImprovements);
    }

    private void collectBatchSignals(
            BatchReportDTO report,
            List<String> fallbackStrengths,
            List<String> fallbackImprovements,
            Map<Integer, QuestionEvalDTO> evaluationMap
    ) {
        if (report == null) {
            return;
        }
        if (report.strengths() != null) {
            fallbackStrengths.addAll(report.strengths());
        }
        if (report.improvements() != null) {
            fallbackImprovements.addAll(report.improvements());
        }
        if (report.questionEvaluations() != null) {
            for (QuestionEvalDTO evaluation : report.questionEvaluations()) {
                evaluationMap.put(evaluation.questionIndex(), evaluation);
            }
        }
    }

    private MergedEvaluation buildMergedEvaluation(
            List<QaRecord> qaRecords,
            Map<Integer, QuestionEvalDTO> evaluationMap,
            List<String> fallbackStrengths,
            List<String> fallbackImprovements
    ) {
        List<EvaluationReport.QuestionEvaluation> questionDetails = new ArrayList<>();
        List<EvaluationReport.ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, int[]> categoryStats = new LinkedHashMap<>();
        for (QaRecord record : qaRecords) {
            QuestionEvalDTO evaluation = evaluationMap.getOrDefault(
                    record.questionIndex(),
                    buildFallbackQuestionEval(record)
            );
            int score = clampScore(evaluation.score());
            questionDetails.add(buildQuestionEvaluation(record, evaluation, score));
            referenceAnswers.add(buildReferenceAnswer(record, evaluation));
            accumulateCategoryScore(categoryStats, record.category(), score);
        }
        return new MergedEvaluation(
                computeOverallScore(questionDetails),
                buildCategoryScores(categoryStats),
                questionDetails,
                referenceAnswers,
                deduplicateTexts(fallbackStrengths, 6),
                deduplicateTexts(fallbackImprovements, 6)
        );
    }

    private EvaluationReport.QuestionEvaluation buildQuestionEvaluation(
            QaRecord record,
            QuestionEvalDTO evaluation,
            int score
    ) {
        return new EvaluationReport.QuestionEvaluation(
                record.questionIndex(),
                safeText(record.question(), ""),
                safeText(record.category(), DEFAULT_CATEGORY),
                safeText(record.userAnswer(), ""),
                score,
                safeText(evaluation.feedback(), "")
        );
    }

    private EvaluationReport.ReferenceAnswer buildReferenceAnswer(
            QaRecord record,
            QuestionEvalDTO evaluation
    ) {
        List<String> keyPoints = evaluation.keyPoints() == null ? List.of() : evaluation.keyPoints();
        return new EvaluationReport.ReferenceAnswer(
                record.questionIndex(),
                safeText(record.question(), ""),
                safeText(evaluation.referenceAnswer(), ""),
                keyPoints
        );
    }

    private void accumulateCategoryScore(
            Map<String, int[]> categoryStats,
            String category,
            int score
    ) {
        int[] stats = categoryStats.computeIfAbsent(safeText(category, DEFAULT_CATEGORY), key -> new int[]{0, 0});
        stats[0] += score;
        stats[1] += 1;
    }

    private int computeOverallScore(List<EvaluationReport.QuestionEvaluation> questionDetails) {
        int totalScore = questionDetails.stream().mapToInt(EvaluationReport.QuestionEvaluation::score).sum();
        return questionDetails.isEmpty() ? 0 : totalScore / questionDetails.size();
    }

    private List<EvaluationReport.CategoryScore> buildCategoryScores(Map<String, int[]> categoryStats) {
        return categoryStats.entrySet().stream()
                .map(entry -> new EvaluationReport.CategoryScore(
                        entry.getKey(),
                        entry.getValue()[1] == 0 ? 0 : entry.getValue()[0] / entry.getValue()[1],
                        entry.getValue()[1]
                ))
                .toList();
    }

    private SummaryDTO summarizeEvaluation(
            ChatClient chatClient,
            String sessionId,
            String resumeText,
            String referenceContext,
            MergedEvaluation merged
    ) {
        try {
            String systemPrompt = summarySystemPromptTemplate.render() + "\n\n" + summaryOutputConverter.getFormat();
            String userPrompt = summaryUserPromptTemplate.render(
                    buildSummaryPromptParams(resumeText, referenceContext, merged)
            );
            SummaryDTO summary = structuredOutputInvoker.invoke(
                    chatClient,
                    systemPrompt,
                    userPrompt,
                    summaryOutputConverter,
                    ErrorCode.INTERVIEW_EVALUATION_FAILED,
                    "面试总结生成失败: ",
                    "interview_evaluation_summary",
                    log
            );
            return summary == null ? buildFallbackSummary(merged) : summary;
        } catch (Exception e) {
            log.error("面试总结评估失败: sessionId={}", sessionId, e);
            return buildFallbackSummary(merged);
        }
    }

    private Map<String, Object> buildSummaryPromptParams(
            String resumeText,
            String referenceContext,
            MergedEvaluation merged
    ) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("resumeText", StringUtils.hasText(resumeText) ? resumeText : DEFAULT_RESUME_TEXT);
        params.put(
                "referenceContext",
                StringUtils.hasText(referenceContext) ? referenceContext : DEFAULT_REFERENCE_CONTEXT
        );
        params.put("categorySummary", buildCategorySummaryText(merged.categoryScores()));
        params.put("questionHighlights", buildQuestionHighlightsText(merged.questionDetails()));
        params.put("fallbackOverallFeedback", buildFallbackOverallFeedback(merged.overallScore()));
        params.put("fallbackStrengths", String.join("\n", merged.fallbackStrengths()));
        params.put("fallbackImprovements", String.join("\n", merged.fallbackImprovements()));
        return params;
    }

    private String buildCategorySummaryText(List<EvaluationReport.CategoryScore> categoryScores) {
        StringBuilder builder = new StringBuilder();
        for (EvaluationReport.CategoryScore categoryScore : categoryScores) {
            builder.append("- ").append(categoryScore.category())
                    .append(": ").append(categoryScore.score())
                    .append("（").append(categoryScore.questionCount()).append("题）\n");
        }
        return builder.toString().strip();
    }

    private String buildQuestionHighlightsText(List<EvaluationReport.QuestionEvaluation> questionDetails) {
        StringBuilder builder = new StringBuilder();
        for (EvaluationReport.QuestionEvaluation question : questionDetails) {
            builder.append("- Q").append(question.questionIndex())
                    .append(" [").append(question.category()).append("]")
                    .append(" 得分=").append(question.score())
                    .append(" 反馈=").append(safeText(question.feedback(), ""))
                    .append('\n');
        }
        return builder.toString().strip();
    }

    private SummaryDTO buildFallbackSummary(MergedEvaluation merged) {
        return new SummaryDTO(
                buildFallbackOverallFeedback(merged.overallScore()),
                ensureSummaryList(merged.fallbackStrengths(), List.of(), DEFAULT_STRENGTH),
                ensureSummaryList(merged.fallbackImprovements(), List.of(), DEFAULT_IMPROVEMENT)
        );
    }

    private List<String> ensureSummaryList(
            List<String> primary,
            List<String> secondary,
            String defaultValue
    ) {
        List<String> normalizedPrimary = deduplicateTexts(primary, 6);
        if (!normalizedPrimary.isEmpty()) {
            return normalizedPrimary;
        }
        List<String> normalizedSecondary = deduplicateTexts(secondary, 6);
        if (!normalizedSecondary.isEmpty()) {
            return normalizedSecondary;
        }
        return List.of(defaultValue);
    }

    private String buildFallbackOverallFeedback(int overallScore) {
        if (overallScore >= 85) {
            return "整体表现较强，回答完整度和技术深度都比较好。";
        }
        if (overallScore >= 70) {
            return "整体表现良好，核心知识基本具备，但仍有进一步展开的空间。";
        }
        if (overallScore >= 60) {
            return "整体达到基础要求，但回答深度和稳定性仍需加强。";
        }
        return "整体表现偏弱，关键知识点掌握还不够稳定。";
    }

    private List<String> deduplicateTexts(List<String> items, int maxSize) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : items) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String normalized = item.strip();
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
            if (result.size() >= maxSize) {
                break;
            }
        }
        return result;
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    private EvaluationReport buildEmptyReport(String sessionId) {
        return new EvaluationReport(
                sessionId,
                0,
                0,
                List.of(),
                List.of(),
                DEFAULT_EMPTY_FEEDBACK,
                List.of(),
                List.of(DEFAULT_EMPTY_IMPROVEMENT),
                List.of()
        );
    }
}
