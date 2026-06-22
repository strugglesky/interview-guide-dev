package org.example.modules.interview.service;

import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.config.InterviewQuestionProperties;
import org.example.common.model.ErrorCode;
import org.example.modules.interview.model.HistoricalQuestion;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.skill.InterviewSkillService;
import org.example.modules.interview.skill.InterviewSkillService.CategoryDTO;
import org.example.modules.interview.skill.InterviewSkillService.SkillDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * 面试问题生成服务
 * 无简历：单次 Skill 驱动出题
 * 有简历：并行调用（简历题 60% + 方向题 40%）
 */
@Service
public class InterviewQuestionService {
    /** SLF4J 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    /**
     * 默认题目类型，当 LLM 输出未指定 type 时使用（例如 GENERAL）
     */
    private static final String DEFAULT_QUESTION_TYPE = "GENERAL";

    /**
     * 每道主问题允许的最大追问数量（防止生成过多追问）
     */
    private static final int MAX_FOLLOW_UP_COUNT = 2;

    /**
     * 在同时提供简历与方向时，简历题所占的比例（其余为方向题）
     */
    private static final double RESUME_QUESTION_RATIO = 0.6;

    /**
     * 当没有简历时，注入到 system prompt 的通用面试模式补充说明，避免 LLM 假设存在简历
     */
    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        \n\n# 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    /**
     * 难度描述映射：用于将 difficulty key 映射为对 LLM 更友好的描述文本
     */
    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
            "junior", "校招/0-1年经验。考察基础概念和简单应用。",
            "mid", "1-3年经验。考察原理理解和实战经验。",
            "senior", "3年+经验。考察架构设计和深度调优。"
    );

    /**
     * 通用回退问题集（当 LLM 失败或 skill 无分类时用于生成默认题）
     * 每行格式：{question, type, category}
     */
    private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
            {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"},
            {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"},
            {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"},
            {"你如何保证代码质量？介绍你实践过的有效手段。", "GENERAL", "综合能力"},
            {"描述一个你做过的技术优化案例，优化的动机、方案和效果。", "GENERAL", "综合能力"},
            {"你在团队协作中遇到过最大的分歧是什么？如何解决的？", "GENERAL", "综合能力"},
    };
    private static final String HISTORY_BOUNDARY_LABEL = "history";
    private static final String JD_BOUNDARY_LABEL = "jd";
    private static final String RESUME_BOUNDARY_LABEL = "resume";

    private final PromptTemplate skillSystemPromptTemplate;
    private final PromptTemplate skillUserPromptTemplate;
    private final PromptTemplate resumeSystemPromptTemplate;
    private final PromptTemplate resumeUserPromptTemplate;
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewSkillService skillService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final ExecutorService questionExecutor;
    private final int followUpCount;

    private record QuestionListDTO(List<QuestionDTO> questions) {}

    private record QuestionDTO(String question, String type, String category,
                               String topicSummary, List<String> followUps) {}

    public InterviewQuestionService(
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewSkillService skillService,
            InterviewQuestionProperties properties,
            ResourceLoader resourceLoader,
            LlmProviderRegistry llmProviderRegistry,
            PromptSanitizer promptSanitizer) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.skillSystemPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionSystemPromptPath());
        this.skillUserPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionUserPromptPath());
        this.resumeSystemPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionSystemPromptPath());
        this.resumeUserPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), MAX_FOLLOW_UP_COUNT));
    }

    private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
        return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
    }


    /**
     * 根据 skill、难度与（可选）简历并发生成面试问题：
     * - 若提供 resumeText，则并行生成简历题（比例由 RESUME_QUESTION_RATIO 控制）和方向题；
     * - 若不提供 resumeText，则仅生成方向题；
     * - 在 LLM 失败或结果不满足时会降级为回退策略。
     */
    public List<InterviewQuestionDTO> generateQuestionsBySkills(
            ChatClient chatClient,
            String skillId,
            String difficulty,
            String resumeText,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions,
            List<CategoryDTO> customCategories,
            String jdText) {
        if (questionCount <= 0) {
            return List.of();
        }
        SkillDTO skill = resolveSkill(skillId, customCategories, jdText);
        String normalizedResumeText = normalizeText(resumeText);
        List<HistoricalQuestion> history = normalizeHistoricalQuestions(historicalQuestions);
        String difficultyDescription = resolveDifficultyDescription(difficulty);
        ChatClient skillChatClient = resolveSkillChatClient(chatClient);
        if (!StringUtils.hasText(normalizedResumeText)) {
            return generateSkillQuestionSet(
                    skillChatClient, skill, difficultyDescription, questionCount, history, jdText
            );
        }
        return generateHybridQuestions(
                chatClient, skill, difficultyDescription, normalizedResumeText, questionCount, history, jdText
        );
    }

    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
        return customCategories != null && !customCategories.isEmpty()
                ? skillService.buildCustomSkill(customCategories, jdText)
                : skillService.buildCustomSkill(jdText, skillId);
    }

    private String resolveDifficultyDescription(String difficulty) {
        String key = StringUtils.hasText(difficulty) ? difficulty.strip().toLowerCase(Locale.ROOT) : "mid";
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(key, DIFFICULTY_DESCRIPTIONS.get("mid"));
    }

    private String normalizeText(String text) {
        return StringUtils.hasText(text) ? text.strip() : "";
    }

    private List<HistoricalQuestion> normalizeHistoricalQuestions(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) return List.of();
        List<HistoricalQuestion> result = new ArrayList<>();
        for (HistoricalQuestion question : historicalQuestions) {
            if (question != null && StringUtils.hasText(question.question())) result.add(question);
        }
        return result;
    }

    private List<InterviewQuestionDTO> generateHybridQuestions(ChatClient chatClient, SkillDTO skill,
                                                               String difficultyDescription, String resumeText,
                                                               int questionCount, List<HistoricalQuestion> history,
                                                               String jdText) {
        int resumeCount = calculateResumeQuestionCount(questionCount);
        int skillCount = Math.max(0, questionCount - resumeCount);
        Future<List<InterviewQuestionDTO>> resumeFuture = questionExecutor.submit(
                () -> generateResumeQuestionSet(chatClient, skill, difficultyDescription, resumeText, resumeCount, history));
        Future<List<InterviewQuestionDTO>> skillFuture = questionExecutor.submit(
                () -> generateSkillQuestionSet(resolveSkillChatClient(chatClient), skill, difficultyDescription, skillCount, history, jdText));
        List<InterviewQuestionDTO> resumeQuestions = awaitQuestionSet("简历题", resumeFuture,
                () -> buildFallbackQuestionSet(skill, resumeCount, true));
        List<InterviewQuestionDTO> skillQuestions = awaitQuestionSet("方向题", skillFuture,
                () -> buildFallbackQuestionSet(skill, skillCount, false));
        return mergeQuestionSets(resumeQuestions, skillQuestions);
    }

    private int calculateResumeQuestionCount(int questionCount) {
        if (questionCount <= 1) return questionCount;
        int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
        return Math.min(questionCount - 1, resumeCount);
    }

    private List<InterviewQuestionDTO> awaitQuestionSet(String phase, Future<List<InterviewQuestionDTO>> future,
                                                        Supplier<List<InterviewQuestionDTO>> fallbackSupplier) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待{}生成结果被中断", phase, e);
        } catch (ExecutionException e) {
            log.error("等待{}生成结果失败", phase, e);
        }
        return fallbackSupplier.get();
    }

    private List<InterviewQuestionDTO> generateSkillQuestionSet(ChatClient chatClient, SkillDTO skill,
                                                                String difficultyDescription, int questionCount,
                                                                List<HistoricalQuestion> history, String jdText) {
        if (questionCount <= 0) return List.of();
        if (skill.categories() == null || skill.categories().isEmpty()) {
            return buildFallbackQuestionSet(skill, questionCount, false);
        }
        QuestionListDTO result = invokeSkillQuestionGeneration(chatClient, skill, difficultyDescription,
                questionCount, history, jdText);
        return convertToInterviewQuestions(completeQuestionDTOs(result, skill, questionCount, false));
    }

    private List<InterviewQuestionDTO> generateResumeQuestionSet(ChatClient chatClient, SkillDTO skill,
                                                                 String difficultyDescription, String resumeText,
                                                                 int questionCount, List<HistoricalQuestion> history) {
        if (questionCount <= 0) return List.of();
        QuestionListDTO result = invokeResumeQuestionGeneration(chatClient, skill, difficultyDescription,
                resumeText, questionCount, history);
        return convertToInterviewQuestions(completeQuestionDTOs(result, skill, questionCount, true));
    }

    private QuestionListDTO invokeSkillQuestionGeneration(ChatClient chatClient, SkillDTO skill,
                                                          String difficultyDescription, int questionCount,
                                                          List<HistoricalQuestion> history, String jdText) {
        try {
            String userPrompt = skillUserPromptTemplate.render(
                    buildSkillPromptParams(skill, difficultyDescription, questionCount, history, jdText));
            return structuredOutputInvoker.invoke(chatClient, buildSkillSystemPrompt(), userPrompt, outputConverter,
                    ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "面试问题生成失败: ",
                    "interview_question_skill", log);
        } catch (Exception e) {
            log.error("Skill 模式生成面试问题失败: skillId={}, questionCount={}", skill.id(), questionCount, e);
            return null;
        }
    }

    private QuestionListDTO invokeResumeQuestionGeneration(ChatClient chatClient, SkillDTO skill,
                                                           String difficultyDescription, String resumeText,
                                                           int questionCount, List<HistoricalQuestion> history) {
        try {
            String systemPrompt = resumeSystemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
            String userPrompt = resumeUserPromptTemplate.render(
                    buildResumePromptParams(skill, difficultyDescription, resumeText, questionCount, history));
            return structuredOutputInvoker.invoke(resolveResumeChatClient(chatClient), systemPrompt, userPrompt,
                    outputConverter, ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "面试问题生成失败: ", "interview_question_resume", log);
        } catch (Exception e) {
            log.error("简历模式生成面试问题失败: skillId={}, questionCount={}", skill.id(), questionCount, e);
            return null;
        }
    }

    private Map<String, Object> buildSkillPromptParams(SkillDTO skill, String difficultyDescription,
                                                       int questionCount, List<HistoricalQuestion> history,
                                                       String jdText) {
        Map<String, Integer> allocation = skillService.calculateAllocation(skill.categories(), questionCount);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("questionCount", questionCount);
        params.put("followUpCount", followUpCount);
        params.put("difficultyDescription", difficultyDescription);
        params.put("skillName", safePromptValue(skill.name(), "通用技术面试"));
        params.put("skillDescription", safePromptValue(skill.description(), "围绕候选人的目标方向进行技术考察。"));
        params.put("skillToolCommand", buildSkillToolCommand(skill));
        params.put("allocationTable", skillService.buildAllocationDescription(allocation, skill.categories()));
        params.put("historicalSection", buildHistoricalSection(history));
        params.put("referenceSection", buildReferenceSection(skill, allocation));
        params.put("jdSection", buildJdSection(jdText));
        return params;
    }

    private Map<String, Object> buildResumePromptParams(SkillDTO skill, String difficultyDescription,
                                                        String resumeText, int questionCount,
                                                        List<HistoricalQuestion> history) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("questionCount", questionCount);
        params.put("followUpCount", followUpCount);
        params.put("skillName", safePromptValue(skill.name(), "项目经历面试"));
        params.put("skillDescription", safePromptValue(skill.description(), "围绕候选人的简历项目经历进行追问。"));
        params.put("difficultyDescription", difficultyDescription);
        params.put("resumeText", buildWrappedPromptText(RESUME_BOUNDARY_LABEL, resumeText, "未提供简历内容。"));
        params.put("historicalSection", buildHistoricalSection(history));
        return params;
    }

    private String buildSkillSystemPrompt() {
        return skillSystemPromptTemplate.render() + GENERIC_MODE_SYSTEM_APPEND + "\n\n" + outputConverter.getFormat();
    }

    private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) return "无";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < historicalQuestions.size() && i < 10; i++) {
            HistoricalQuestion question = historicalQuestions.get(i);
            builder.append(i + 1).append(". [").append(resolveHistoryType(question)).append("] ")
                    .append(resolveHistorySummary(question)).append('\n');
        }
        return buildWrappedPromptText(HISTORY_BOUNDARY_LABEL, builder.toString().strip(), "无");
    }

    private String buildReferenceSection(SkillDTO skill, Map<String, Integer> allocation) {
        String value = skillService.buildReferenceSection(skill, allocation);
        return StringUtils.hasText(value) ? value : "无";
    }

    private String buildJdSection(String jdText) {
        return buildWrappedPromptText(JD_BOUNDARY_LABEL, jdText, "无");
    }

    private String buildSkillToolCommand(SkillDTO skill) {
        return skill.isPreset() ? "加载面试技能 " + skill.id() + " 的 SKILL.md"
                : "custom（无需加载预设 Skill，直接按分类、JD 与参考资料出题）";
    }

    private List<QuestionDTO> completeQuestionDTOs(QuestionListDTO result, SkillDTO skill,
                                                   int questionCount, boolean resumeMode) {
        List<QuestionDTO> questions = new ArrayList<>();
        if (result != null && result.questions() != null) {
            for (QuestionDTO question : result.questions()) {
                if (question != null && StringUtils.hasText(question.question())) {
                    questions.add(normalizeQuestionDTO(question, skill, resumeMode));
                }
                if (questions.size() >= questionCount) break;
            }
        }
        if (questions.size() < questionCount) {
            questions.addAll(buildFallbackQuestionDTOs(skill, questionCount - questions.size(), resumeMode));
        }
        return questions;
    }

    private List<QuestionDTO> buildFallbackQuestionDTOs(SkillDTO skill, int count, boolean resumeMode) {
        List<QuestionDTO> fallbacks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String[] seed = GENERIC_FALLBACK_QUESTIONS[i % GENERIC_FALLBACK_QUESTIONS.length];
            String category = resumeMode ? safePromptValue(skill.name(), "项目经历") : seed[2];
            fallbacks.add(new QuestionDTO(seed[0], seed[1], category,
                    buildFallbackTopicSummary(seed[0]), buildFallbackFollowUps()));
        }
        return fallbacks;
    }

    private List<InterviewQuestionDTO> convertToInterviewQuestions(List<QuestionDTO> questions) {
        List<InterviewQuestionDTO> result = new ArrayList<>();
        for (QuestionDTO question : questions) appendQuestionWithFollowUps(result, question);
        return result;
    }

    private void appendQuestionWithFollowUps(List<InterviewQuestionDTO> result, QuestionDTO question) {
        int parentIndex = result.size() + 1;
        result.add(InterviewQuestionDTO.create(parentIndex, resolveQuestionText(question), resolveQuestionType(question),
                resolveQuestionCategory(question), resolveTopicSummary(question), false, null));
        for (String followUp : normalizeFollowUps(question.followUps())) {
            result.add(InterviewQuestionDTO.create(result.size() + 1, followUp, resolveQuestionType(question),
                    resolveQuestionCategory(question), resolveTopicSummary(question), true, parentIndex));
        }
    }

    private QuestionDTO normalizeQuestionDTO(QuestionDTO question, SkillDTO skill, boolean resumeMode) {
        String category = !StringUtils.hasText(question.category()) && resumeMode
                ? safePromptValue(skill.name(), "项目经历") : resolveQuestionCategory(question);
        return new QuestionDTO(resolveQuestionText(question), resolveQuestionType(question), category,
                resolveTopicSummary(question), normalizeFollowUps(question.followUps()));
    }

    private String resolveQuestionText(QuestionDTO question) {
        return StringUtils.hasText(question.question()) ? question.question().strip() : "请介绍一个相关技术问题。";
    }

    private String resolveQuestionType(QuestionDTO question) {
        return StringUtils.hasText(question.type()) ? question.type().strip() : DEFAULT_QUESTION_TYPE;
    }

    private String resolveQuestionCategory(QuestionDTO question) {
        return StringUtils.hasText(question.category()) ? question.category().strip()
                : StringUtils.hasText(question.type()) ? question.type().strip() : "综合能力";
    }

    private String resolveTopicSummary(QuestionDTO question) {
        return StringUtils.hasText(question.topicSummary()) ? question.topicSummary().strip()
                : buildFallbackTopicSummary(resolveQuestionText(question));
    }

    private List<String> normalizeFollowUps(List<String> followUps) {
        if (followUpCount <= 0) return List.of();
        List<String> result = new ArrayList<>();
        if (followUps != null) {
            for (String followUp : followUps) {
                if (StringUtils.hasText(followUp)) result.add(followUp.strip());
                if (result.size() >= followUpCount) break;
            }
        }
        while (result.size() < followUpCount) result.add(buildFallbackFollowUps().get(result.size()));
        return result;
    }

    private List<InterviewQuestionDTO> buildFallbackQuestionSet(SkillDTO skill, int questionCount, boolean resumeMode) {
        return convertToInterviewQuestions(buildFallbackQuestionDTOs(skill, questionCount, resumeMode));
    }

    private String buildFallbackTopicSummary(String questionText) {
        return questionText.length() <= 20 ? questionText : questionText.substring(0, 20);
    }

    private List<String> buildFallbackFollowUps() {
        List<String> followUps = new ArrayList<>();
        if (followUpCount >= 1) followUps.add("请补充说明你的实现思路、关键步骤以及核心取舍。");
        if (followUpCount >= 2) followUps.add("如果规模扩大或出现边界情况，你会如何优化这道题中的方案？");
        return followUps;
    }

    private String resolveHistoryType(HistoricalQuestion question) {
        return StringUtils.hasText(question.type()) ? question.type().strip() : DEFAULT_QUESTION_TYPE;
    }

    private String resolveHistorySummary(HistoricalQuestion question) {
        if (StringUtils.hasText(question.topicSummary())) return question.topicSummary().strip();
        String raw = question.question().strip();
        return raw.length() <= 30 ? raw : raw.substring(0, 30);
    }

    private String buildWrappedPromptText(String label, String text, String fallback) {
        if (!StringUtils.hasText(text)) {
            return fallback;
        }
        String sanitized = promptSanitizer.sanitize(text.strip());
        return promptSanitizer.wrapWithDelimiters(label, sanitized);
    }

    private List<InterviewQuestionDTO> mergeQuestionSets(List<InterviewQuestionDTO> first,
                                                         List<InterviewQuestionDTO> second) {
        List<InterviewQuestionDTO> merged = new ArrayList<>(first);
        merged.addAll(shiftQuestionIndices(second, merged.size()));
        return merged;
    }

    private List<InterviewQuestionDTO> shiftQuestionIndices(List<InterviewQuestionDTO> questions, int offset) {
        if (offset <= 0) return questions;
        List<InterviewQuestionDTO> shifted = new ArrayList<>();
        for (InterviewQuestionDTO question : questions) {
            Integer parentIndex = question.parentQuestionIndex() == null ? null : question.parentQuestionIndex() + offset;
            shifted.add(InterviewQuestionDTO.create(question.questionIndex() + offset, question.question(),
                    question.type(), question.category(), question.topicSummary(), question.isFollowUp(), parentIndex));
        }
        return shifted;
    }

    private String safePromptValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    private ChatClient resolveSkillChatClient(ChatClient chatClient) {
        return chatClient != null ? chatClient : llmProviderRegistry.getChatClientOrDefault(null);
    }

    private ChatClient resolveResumeChatClient(ChatClient chatClient) {
        return chatClient != null ? chatClient : llmProviderRegistry.getPlainChatClient(null);
    }




}
