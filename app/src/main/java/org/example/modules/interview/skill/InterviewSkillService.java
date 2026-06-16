package org.example.modules.interview.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.config.InterviewSkillProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试方向 Skill 管理：分类分配、References 注入、自定义 Skill 构建。
 *
 * 与 SkillsTool 互补：SkillsTool 负责 LLM 按需加载 persona（SKILL.md body），
 * 本类负责后端解析分类配置（skill.meta.yml）并批量注入 references 到 Prompt。
 */
@Slf4j
@Service
public class InterviewSkillService {
    /** 自定义面试方向的固定 Skill ID，用于区分预设 Skill。 */
    public static final String CUSTOM_SKILL_ID = "custom";

    /** JD 文本最小长度，过短时不触发有效解析。 */
    private static final int MIN_JD_LENGTH = 50;
    /** 分类展示名称最大长度，避免模型输出过长标签。 */
    private static final int MAX_CATEGORY_LABEL_LENGTH = 50;
    /** 分类 key 最大长度，保证持久化和 Prompt 注入时字段可控。 */
    private static final int MAX_CATEGORY_KEY_LENGTH = 50;

    /**
     * 用于从 Skill Markdown 文件中提取 front matter（YAML）和 body 的正则：
     * 支持单行开头的 "---" 标记和跨行内容（DOTALL 模式）
     */
    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$");
    /** 从 classpath skill 路径中提取 skillId。 */
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    /** Skill 分类配置文件名。 */
    private static final String SKILL_META_FILE = "skill.meta.yml";
    /** JD 解析使用的系统提示词模板路径。 */
    private static final String JD_PARSE_SYSTEM_PROMPT_PATH = "classpath:prompts/jd-parse-system.st";

    /** 注入到生成题目 Prompt 的 references 最大字符数。 */
    private static final int MAX_REFERENCE_SECTION_CHARS = 12000;
    /** 注入到评估 Prompt 的 references 最大字符数。 */
    private static final int MAX_EVALUATION_REFERENCE_SECTION_CHARS = 6000;
    /** 单个 reference 文档允许注入的最大字符数。 */
    private static final int MAX_SINGLE_REFERENCE_CHARS = 3000;


    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final BeanOutputConverter<CategoryListDTO> jdOutputConverter;
    private final PromptTemplate jdSystemPromptTemplate;
    private final ResourceLoader resourceLoader;
    private final PromptSanitizer promptSanitizer;

    /** 预设 Skill 注册表，启动时从 classpath:skills/{skillId}/SKILL.md 加载 */
    private final Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry = new TreeMap<>();

    /** 参考内容缓存（classpath 资源不可变，加载一次后复用） */
    private final Map<String, String> referenceCache = new ConcurrentHashMap<>();

    /** 全局 category key → (ref文件名, 是否shared) 映射，启动时构建，之后只读 */
    private final Map<String, RefMapping> categoryRefIndex = new HashMap<>();

    /** JD 解析用的参考文件清单 Markdown 表格，启动时生成一次 */
    private String cachedReferenceFileList;

    public InterviewSkillService(LlmProviderRegistry llmProviderRegistry,
                                 StructuredOutputInvoker structuredOutputInvoker,
                                 ResourceLoader resourceLoader,
                                 PromptSanitizer promptSanitizer) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.jdOutputConverter = new BeanOutputConverter<>(CategoryListDTO.class);
        this.resourceLoader = resourceLoader;
        this.promptSanitizer = promptSanitizer;
        this.jdSystemPromptTemplate = new PromptTemplate(loadClasspathPrompt(JD_PARSE_SYSTEM_PROMPT_PATH));
    }

    private String loadClasspathPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    record RefMapping(String ref, boolean shared, String sourceSkillId) {}


    /**
     * 启动时加载 classpath 下的预设 Skill：
     * - 合并 skill.meta.yml 中的元信息
     * - 将有效的 SkillDefinition 注册到 presetRegistry
     * - 构建 category→reference 索引并生成参考文件清单cachedReferenceFileList
     */
    @PostConstruct
    void loadPresetSkills() throws Exception{
        presetRegistry.clear();
        categoryRefIndex.clear();
        Resource[] resources = new PathMatchingResourcePatternResolver(resourceLoader)
                .getResources("classpath*:skills/*/SKILL.md");
        for (Resource resource : resources) {
            loadAndRegisterPresetSkill(resource);
        }
        cachedReferenceFileList = buildReferenceFileListMarkdown();
        log.info("Preset interview skills loaded: count={}", presetRegistry.size());
    }

    /**
     * 返回所有预设 Skill 的 DTO 列表（按 key 排序），用于前端展示
     */
    public List<SkillDTO> getAllSkills() {
        return presetRegistry.entrySet().stream()
                .map(entry -> toSkillDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 根据 skillId 返回对应的 SkillDTO，找不到时抛出 BusinessException
     */
    public SkillDTO getSkill(String skillId) {
        String normalizedSkillId = normalizeSkillId(skillId);
        InterviewSkillProperties.SkillDefinition definition = presetRegistry.get(normalizedSkillId);
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "面试方向不存在");
        }
        return toSkillDTO(normalizedSkillId, definition);
    }

    /**
     * 从 JD 解析结果构建自定义 SkillDTO。
     * 遍历 customCategories，尝试在 categoryRefIndex 中匹配参考文件。
     */
    public SkillDTO buildCustomSkill(List<CategoryDTO> customCategories, String jdText) {
        List<SkillCategoryDTO> categories = normalizeCustomCategories(customCategories);
        return new SkillDTO(
                CUSTOM_SKILL_ID,
                "custom",
                "基于 JD 动态构建的自定义面试方向",
                categories,
                false,
                StringUtils.hasText(jdText) ? jdText.strip() : "",
                buildCustomPersona(categories, jdText),
                buildCustomDisplay()
        );
    }

    /**
     * 从 JD 解析结果构建自定义 SkillDTO。
     * 遍历 customCategories，尝试在 categoryRefIndex 中匹配参考文件。
     */
    public SkillDTO buildCustomSkill(String jd, String skillId) {
        if (StringUtils.hasText(skillId) && !CUSTOM_SKILL_ID.equals(normalizeSkillId(skillId))) {
            return getSkill(skillId);
        }
        return buildCustomSkill(parseJd(jd), jd);
    }

    /**
     * 使用 LLM 解析职位描述（JD），返回 CategoryDTO 列表。
     * - 校验最小长度
     * - 准备 system 与 user prompt（包含参考文件清单）
     * - 调用 structuredOutputInvoker 并转换为 CategoryListDTO
     */
    public List<CategoryDTO> parseJd(String jdText) {
        String normalizedJd = validateJdText(jdText);
        String systemPrompt = buildJdParseSystemPrompt();
        String userPrompt = buildJdParseUserPrompt(normalizedJd);
        CategoryListDTO categoryList = structuredOutputInvoker.invoke(
                llmProviderRegistry.getPlainChatClient(null),
                systemPrompt,
                userPrompt,
                jdOutputConverter,
                ErrorCode.AI_SERVICE_ERROR,
                "JD 解析失败: ",
                "jd_parse",
                log
        );
        return normalizeParsedCategories(categoryList);
    }


    /**
     * 根据分类优先级（ALWAYS_ONE / CORE / default）将 totalQuestions 分配到各分类。
     * 分配策略：
     *  1) ALWAYS_ONE 每类优先保底 1 题
     *  2) 给 CORE 和普通类目各 1 题以保证覆盖
     *  3) 剩余题按 CORE 优先轮转分配
     */
    public Map<String, Integer> calculateAllocation(List<SkillCategoryDTO> categories, int totalQuestions){
        Map<String, Integer> allocation = initializeAllocation(categories);
        if (categories == null || categories.isEmpty() || totalQuestions <= 0) {
            return allocation;
        }
        int remaining = allocateGuaranteedQuestions(categories, allocation, totalQuestions);
        if (remaining <= 0) {
            return allocation;
        }
        List<SkillCategoryDTO> rotationTargets = selectRotationTargets(categories);
        distributeRemainingQuestions(rotationTargets, allocation, remaining);
        return allocation;
    }

    /**
     * 将 allocation 转为 Markdown 表格形式的描述文本，按传入 categories 顺序输出
     */
    public String buildAllocationDescription(Map<String, Integer>  allocation, List<SkillCategoryDTO> categories){
        StringBuilder builder = new StringBuilder("| 分类 | key | 优先级 | 题量 |\n")
                .append("| --- | --- | --- | --- |\n");
        if (categories == null || categories.isEmpty()) {
            return builder.toString();
        }
        for (SkillCategoryDTO category : categories) {
            builder.append("| ").append(category.label())
                    .append(" | ").append(category.key())
                    .append(" | ").append(category.priority())
                    .append(" | ").append(allocation.getOrDefault(category.key(), 0))
                    .append(" |\n");
        }
        return builder.toString();
    }

    /**
     * 根据 allocation 选择需要注入的分类，然后拼接对应的 reference 内容（受 MAX_REFERENCE_SECTION_CHARS 限制）
     */
    public String buildReferenceSection(SkillDTO  skill, Map<String, Integer>  allocation){
        List<SkillCategoryDTO> selectedCategories = filterAllocatedReferenceCategories(skill, allocation);
        return buildReferenceContent(skill, selectedCategories, MAX_REFERENCE_SECTION_CHARS);
    }

    /**
     * 为评估阶段构建参考线：覆盖 skill 下所有配置了 reference 的分类，受 MAX_EVALUATION_REFERENCE_SECTION_CHARS 限制
     */
    public String buildEvaluationReferenceSection(SkillDTO  skill, Map<String, Integer>  allocation){
        List<SkillCategoryDTO> selectedCategories = filterAllReferenceCategories(skill);
        return buildReferenceContent(skill, selectedCategories, MAX_EVALUATION_REFERENCE_SECTION_CHARS);
    }

    /**
     * 安全版本的评估参考基线：skillId 为空或加载失败时返回空字符串，不抛异常。
     */
    public String buildEvaluationReferenceSectionSafe(String skillId){
        if (!StringUtils.hasText(skillId) || CUSTOM_SKILL_ID.equals(normalizeSkillId(skillId))) {
            return "";
        }
        try {
            return buildEvaluationReferenceSection(getSkill(skillId), Map.of());
        } catch (BusinessException e) {
            log.warn("Failed to build evaluation reference section safely: skillId={}", skillId, e);
            return "";
        }
    }

    /**
     * JD 解析返回分类（可携带 LLM 匹配的 ref/shared 信息，后端会按本地 categoryRefIndex 纠正）
     */
    public record CategoryDTO(String key, String label, String priority,
                              String ref, Boolean shared) {}

    private record CategoryListDTO(List<CategoryDTO> categories) {}

    public record SkillDTO(String id, String name, String description,
                           List<SkillCategoryDTO> categories,
                           boolean isPreset, String sourceJd, String persona, DisplayDTO display) {}

    /**
     * 预设 Skill 分类（可携带 references 绑定信息）
     */
    public record SkillCategoryDTO(String key, String label, String priority, String ref, boolean shared) {}

    public record DisplayDTO(String icon, String gradient, String iconBg, String iconColor) {}

    private void loadAndRegisterPresetSkill(Resource resource) throws IOException {
        String skillId = extractSkillId(resource);
        if (!StringUtils.hasText(skillId)) {
            log.warn("Skip skill resource without skillId: {}", resource);
            return;
        }
        InterviewSkillProperties.SkillDefinition definition = parseSkillDefinition(skillId, resource);
        if (definition == null) {
            return;
        }
        presetRegistry.put(skillId, definition);
        registerCategoryReferences(skillId, definition.getCategories());
    }

    private InterviewSkillProperties.SkillDefinition parseSkillDefinition(String skillId, Resource skillResource)
            throws IOException {
        String markdown = skillResource.getContentAsString(StandardCharsets.UTF_8);
        InterviewSkillProperties.SkillFrontMatterDefinition frontMatter = parseFrontMatter(markdown);
        InterviewSkillProperties.SkillMetaDefinition metaDefinition = parseMetaDefinition(skillId);
        String persona = extractPersona(markdown);
        return new InterviewSkillProperties.SkillDefinition(
                StringUtils.hasText(frontMatter.getName()) ? frontMatter.getName() : skillId,
                frontMatter.getDescription(),
                persona,
                StringUtils.hasText(metaDefinition.getDisplayName()) ? metaDefinition.getDisplayName() : frontMatter.getName(),
                metaDefinition.getDisplay(),
                metaDefinition.getCategories()
        );
    }

    private InterviewSkillProperties.SkillFrontMatterDefinition parseFrontMatter(String markdown) {
        Matcher matcher = FRONT_MATTER_PATTERN.matcher(markdown);
        if (!matcher.matches()) {
            return new InterviewSkillProperties.SkillFrontMatterDefinition("", "");
        }
        return new InterviewSkillProperties.SkillFrontMatterDefinition(
                parseSimpleScalar(matcher.group(1), "name"),
                parseSimpleScalar(matcher.group(1), "description")
        );
    }

    private InterviewSkillProperties.SkillMetaDefinition parseMetaDefinition(String skillId) throws IOException {
        String path = "classpath:skills/" + skillId + "/" + SKILL_META_FILE;
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            return new InterviewSkillProperties.SkillMetaDefinition(null, null, List.of());
        }
        String content = resource.getContentAsString(StandardCharsets.UTF_8);
        return new InterviewSkillProperties.SkillMetaDefinition(
                parseSimpleScalar(content, "displayName"),
                parseDisplayDef(content),
                parseCategoryDefs(content)
        );
    }

    private String parseSimpleScalar(String content, String key) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(key)) {
            return null;
        }
        String prefix = key + ":";
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith(prefix)) {
                return trimToNull(trimYamlValue(trimmed.substring(prefix.length())));
            }
        }
        return null;
    }

    private InterviewSkillProperties.DisplayDef parseDisplayDef(String content) {
        List<String> lines = List.of(content.split("\\r?\\n"));
        int displayIndex = findSectionIndex(lines, "display:");
        if (displayIndex < 0) {
            return null;
        }
        Map<String, String> fields = parseIndentedKeyValues(lines, displayIndex + 1, 2);
        return new InterviewSkillProperties.DisplayDef(
                fields.get("icon"),
                fields.get("gradient"),
                fields.get("iconBg"),
                fields.get("iconColor")
        );
    }

    private List<InterviewSkillProperties.CategoryDef> parseCategoryDefs(String content) {
        List<String> lines = List.of(content.split("\\r?\\n"));
        int categoriesIndex = findSectionIndex(lines, "categories:");
        if (categoriesIndex < 0) {
            return List.of();
        }
        List<InterviewSkillProperties.CategoryDef> result = new ArrayList<>();
        Map<String, String> current = null;
        for (int i = categoriesIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            int indent = countLeadingSpaces(line);
            String trimmed = line.strip();
            if (indent < 2) {
                break;
            }
            if (trimmed.startsWith("- ")) {
                if (current != null) {
                    result.add(toCategoryDef(current, result.size()));
                }
                current = new LinkedHashMap<>();
                parseCategoryLine(current, trimmed.substring(2));
                continue;
            }
            if (current != null && indent >= 4) {
                parseCategoryLine(current, trimmed);
            }
        }
        if (current != null) {
            result.add(toCategoryDef(current, result.size()));
        }
        return result;
    }

    private InterviewSkillProperties.CategoryDef toCategoryDef(Map<String, String> rawMap, int index) {
        return new InterviewSkillProperties.CategoryDef(
                normalizeCategoryKey(rawMap.get("key"), index),
                normalizeCategoryLabel(rawMap.get("label"), rawMap.get("key")),
                normalizePriority(rawMap.get("priority")),
                trimToNull(rawMap.get("ref")),
                toBoolean(rawMap.get("shared"))
        );
    }

    private void parseCategoryLine(Map<String, String> target, String line) {
        int colonIndex = line.indexOf(':');
        if (colonIndex <= 0) {
            return;
        }
        String key = line.substring(0, colonIndex).strip();
        String value = trimYamlValue(line.substring(colonIndex + 1));
        target.put(key, trimToNull(value));
    }

    private Map<String, String> parseIndentedKeyValues(List<String> lines, int startIndex, int minIndent) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = startIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            int indent = countLeadingSpaces(line);
            if (indent < minIndent) {
                break;
            }
            String trimmed = line.strip();
            if (trimmed.startsWith("- ")) {
                break;
            }
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colonIndex).strip();
            String value = trimYamlValue(trimmed.substring(colonIndex + 1));
            result.put(key, trimToNull(value));
        }
        return result;
    }

    private int findSectionIndex(List<String> lines, String sectionHeader) {
        for (int i = 0; i < lines.size(); i++) {
            if (sectionHeader.equals(lines.get(i).strip())) {
                return i;
            }
        }
        return -1;
    }

    private int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private String trimYamlValue(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.strip();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void registerCategoryReferences(String skillId, List<InterviewSkillProperties.CategoryDef> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        for (InterviewSkillProperties.CategoryDef category : categories) {
            if (!StringUtils.hasText(category.getKey()) || !StringUtils.hasText(category.getRef())) {
                continue;
            }
            categoryRefIndex.putIfAbsent(
                    category.getKey(),
                    new RefMapping(category.getRef(), Boolean.TRUE.equals(category.getShared()), skillId)
            );
        }
    }

    private String buildReferenceFileListMarkdown() {
        StringBuilder builder = new StringBuilder("| 分类Key | reference 文件 | 范围 |\n")
                .append("| --- | --- | --- |\n");
        categoryRefIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append("| ")
                        .append(entry.getKey()).append(" | ")
                        .append(entry.getValue().ref()).append(" | ")
                        .append(entry.getValue().shared() ? "shared" : entry.getValue().sourceSkillId())
                        .append(" |\n"));
        return builder.toString();
    }

    private SkillDTO toSkillDTO(String skillId, InterviewSkillProperties.SkillDefinition definition) {
        List<SkillCategoryDTO> categories = definition.getCategories().stream()
                .map(this::toSkillCategoryDTO)
                .toList();
        return new SkillDTO(
                skillId,
                definition.getName(),
                definition.getDescription(),
                categories,
                true,
                "",
                definition.getPersona(),
                toDisplayDTO(definition.getDisplay())
        );
    }

    private SkillCategoryDTO toSkillCategoryDTO(InterviewSkillProperties.CategoryDef category) {
        return new SkillCategoryDTO(
                normalizeCategoryKey(category.getKey(), 0),
                normalizeCategoryLabel(category.getLabel(), category.getKey()),
                normalizePriority(category.getPriority()),
                trimToNull(category.getRef()),
                Boolean.TRUE.equals(category.getShared())
        );
    }

    private DisplayDTO toDisplayDTO(InterviewSkillProperties.DisplayDef display) {
        if (display == null) {
            return buildCustomDisplay();
        }
        return new DisplayDTO(display.getIcon(), display.getGradient(), display.getIconBg(), display.getIconColor());
    }

    private List<SkillCategoryDTO> normalizeCustomCategories(List<CategoryDTO> customCategories) {
        if (customCategories == null || customCategories.isEmpty()) {
            return List.of();
        }
        List<SkillCategoryDTO> result = new ArrayList<>();
        for (int i = 0; i < customCategories.size(); i++) {
            CategoryDTO category = customCategories.get(i);
            String key = normalizeCategoryKey(category.key(), i);
            RefMapping mapping = categoryRefIndex.get(key);
            result.add(new SkillCategoryDTO(
                    key,
                    normalizeCategoryLabel(category.label(), key),
                    normalizePriority(category.priority()),
                    mapping != null ? mapping.ref() : trimToNull(category.ref()),
                    mapping != null ? mapping.shared() : Boolean.TRUE.equals(category.shared())
            ));
        }
        return result;
    }

    private String buildCustomPersona(List<SkillCategoryDTO> categories, String jdText) {
        StringBuilder builder = new StringBuilder("""
                你是一位严谨的面试官，需要根据候选人的目标岗位组织面试。
                提问时优先覆盖核心能力，再结合项目经历追问实现细节、边界条件和取舍。
                """);
        if (categories != null && !categories.isEmpty()) {
            builder.append("\n重点考察分类：\n");
            for (SkillCategoryDTO category : categories) {
                builder.append("- ").append(category.label())
                        .append(" (").append(category.priority()).append(")\n");
            }
        }
        if (StringUtils.hasText(jdText)) {
            builder.append("\n岗位描述：\n").append(jdText.strip());
        }
        return builder.toString().strip();
    }

    private DisplayDTO buildCustomDisplay() {
        return new DisplayDTO(
                "C",
                "from-slate-500 to-slate-700",
                "bg-slate-100 dark:bg-slate-900/30",
                "text-slate-600 dark:text-slate-300"
        );
    }

    private String validateJdText(String jdText) {
        if (!StringUtils.hasText(jdText)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 不能为空");
        }
        String normalized = jdText.strip();
        if (normalized.length() < MIN_JD_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 内容过短，无法解析");
        }
        return normalized;
    }

    private String buildJdParseSystemPrompt() {
        Map<String, Object> params = Map.of(
                "referenceFileList",
                StringUtils.hasText(cachedReferenceFileList) ? cachedReferenceFileList : ""
        );
        return jdSystemPromptTemplate.render(params) + "\n\n" + jdOutputConverter.getFormat();
    }

    private String buildJdParseUserPrompt(String jdText) {
        return """
                请根据以下职位描述提取 3-7 个面试考察方向，并结合参考文件列表进行匹配。

                职位描述：
                %s
                """.formatted(jdText);
    }

    private List<CategoryDTO> normalizeParsedCategories(CategoryListDTO categoryList) {
        if (categoryList == null || categoryList.categories() == null || categoryList.categories().isEmpty()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析结果为空");
        }
        List<CategoryDTO> result = new ArrayList<>();
        List<CategoryDTO> categories = categoryList.categories();
        for (int i = 0; i < categories.size(); i++) {
            CategoryDTO category = categories.get(i);
            String key = normalizeCategoryKey(category.key(), i);
            RefMapping mapping = categoryRefIndex.get(key);
            result.add(new CategoryDTO(
                    key,
                    normalizeCategoryLabel(category.label(), key),
                    normalizePriority(category.priority()),
                    mapping != null ? mapping.ref() : trimToNull(category.ref()),
                    mapping != null ? mapping.shared() : category.shared()
            ));
        }
        return result;
    }

    private Map<String, Integer> initializeAllocation(List<SkillCategoryDTO> categories) {
        Map<String, Integer> allocation = new LinkedHashMap<>();
        if (categories == null) {
            return allocation;
        }
        for (SkillCategoryDTO category : categories) {
            allocation.put(category.key(), 0);
        }
        return allocation;
    }

    private int allocateGuaranteedQuestions(List<SkillCategoryDTO> categories,
                                            Map<String, Integer> allocation,
                                            int totalQuestions) {
        int remaining = totalQuestions;
        remaining = allocateOnePerCategory(filterByPriority(categories, "ALWAYS_ONE"), allocation, remaining);
        List<SkillCategoryDTO> orderedCoverage = new ArrayList<>();
        orderedCoverage.addAll(filterByPriority(categories, "CORE"));
        orderedCoverage.addAll(filterDefaultPriority(categories));
        return allocateOnePerCategory(orderedCoverage, allocation, remaining);
    }

    private List<SkillCategoryDTO> selectRotationTargets(List<SkillCategoryDTO> categories) {
        List<SkillCategoryDTO> cores = filterByPriority(categories, "CORE");
        if (!cores.isEmpty()) {
            return cores;
        }
        List<SkillCategoryDTO> defaults = filterDefaultPriority(categories);
        return defaults.isEmpty() ? categories : defaults;
    }

    private int allocateOnePerCategory(List<SkillCategoryDTO> categories,
                                       Map<String, Integer> allocation,
                                       int remaining) {
        for (SkillCategoryDTO category : categories) {
            if (remaining <= 0) {
                break;
            }
            allocation.computeIfPresent(category.key(), (key, value) -> value + 1);
            remaining--;
        }
        return remaining;
    }

    private void distributeRemainingQuestions(List<SkillCategoryDTO> categories,
                                              Map<String, Integer> allocation,
                                              int remaining) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        int index = 0;
        while (remaining > 0) {
            SkillCategoryDTO category = categories.get(index % categories.size());
            allocation.computeIfPresent(category.key(), (key, value) -> value + 1);
            remaining--;
            index++;
        }
    }

    private List<SkillCategoryDTO> filterByPriority(List<SkillCategoryDTO> categories, String priority) {
        return categories.stream()
                .filter(category -> priority.equalsIgnoreCase(category.priority()))
                .toList();
    }

    private List<SkillCategoryDTO> filterDefaultPriority(List<SkillCategoryDTO> categories) {
        return categories.stream()
                .filter(category -> !"ALWAYS_ONE".equalsIgnoreCase(category.priority()))
                .filter(category -> !"CORE".equalsIgnoreCase(category.priority()))
                .toList();
    }

    private List<SkillCategoryDTO> filterAllocatedReferenceCategories(SkillDTO skill,
                                                                      Map<String, Integer> allocation) {
        if (skill.categories() == null || skill.categories().isEmpty()) {
            return List.of();
        }
        return skill.categories().stream()
                .filter(category -> allocation.getOrDefault(category.key(), 0) > 0)
                .filter(category -> StringUtils.hasText(category.ref()) || hasReferenceMapping(category.key()))
                .toList();
    }

    private List<SkillCategoryDTO> filterAllReferenceCategories(SkillDTO skill) {
        if (skill.categories() == null || skill.categories().isEmpty()) {
            return List.of();
        }
        return skill.categories().stream()
                .filter(category -> StringUtils.hasText(category.ref()) || hasReferenceMapping(category.key()))
                .toList();
    }

    private String buildReferenceContent(SkillDTO skill,
                                         List<SkillCategoryDTO> categories,
                                         int maxTotalChars) {
        if (categories == null || categories.isEmpty()) {
            return "";
        }
        Map<String, List<SkillCategoryDTO>> grouped = groupCategoriesByReferencePath(skill, categories);
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, List<SkillCategoryDTO>> entry : grouped.entrySet()) {
            String block = buildReferenceBlock(entry.getKey(), entry.getValue(), maxTotalChars - builder.length());
            if (!StringUtils.hasText(block)) {
                continue;
            }
            if (builder.length() + block.length() > maxTotalChars) {
                break;
            }
            builder.append(block);
        }
        return builder.toString().strip();
    }

    private Map<String, List<SkillCategoryDTO>> groupCategoriesByReferencePath(SkillDTO skill,
                                                                                List<SkillCategoryDTO> categories) {
        Map<String, List<SkillCategoryDTO>> grouped = new LinkedHashMap<>();
        for (SkillCategoryDTO category : categories) {
            String path = resolveReferencePath(skill, category);
            if (!StringUtils.hasText(path)) {
                continue;
            }
            grouped.computeIfAbsent(path, key -> new ArrayList<>()).add(category);
        }
        return grouped;
    }

    private String buildReferenceBlock(String path, List<SkillCategoryDTO> categories, int remainingChars) {
        if (remainingChars <= 0) {
            return "";
        }
        String content = readReferenceContent(path);
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String title = categories.stream()
                .map(category -> category.label() + " (" + category.key() + ")")
                .distinct()
                .reduce((left, right) -> left + " / " + right)
                .orElse(path);
        String body = truncateContent(content.strip(), MAX_SINGLE_REFERENCE_CHARS);
        String block = "### " + title + "\n" + body + "\n\n";
        return block.length() <= remainingChars ? block : truncateContent(block, remainingChars);
    }

    private String resolveReferencePath(SkillDTO skill, SkillCategoryDTO category) {
        RefMapping mapping = categoryRefIndex.get(category.key());
        if (category.shared()) {
            String ref = StringUtils.hasText(category.ref()) ? category.ref() : mapping != null ? mapping.ref() : null;
            return StringUtils.hasText(ref) ? "classpath:skills/_shared/references/" + ref : null;
        }
        String ref = StringUtils.hasText(category.ref()) ? category.ref() : mapping != null ? mapping.ref() : null;
        String sourceSkillId = skill.isPreset() ? skill.id() : mapping != null ? mapping.sourceSkillId() : null;
        if (!StringUtils.hasText(ref) || !StringUtils.hasText(sourceSkillId)) {
            return null;
        }
        return "classpath:skills/" + sourceSkillId + "/" + ref;
    }

    private String readReferenceContent(String path) {
        try {
            return referenceCache.computeIfAbsent(path, key -> {
                try {
                    return loadClasspathPrompt(key);
                } catch (IOException e) {
                    log.error("Failed to load interview reference: path={}", key, e);
                    return "";
                }
            });
        } catch (Exception e) {
            log.error("Failed to read interview reference from cache: path={}", path, e);
            return "";
        }
    }

    private boolean hasReferenceMapping(String categoryKey) {
        return categoryRefIndex.containsKey(categoryKey);
    }

    private String extractSkillId(Resource resource) {
        String description = String.valueOf(resource);
        Matcher matcher = SKILL_ID_PATTERN.matcher(description.replace('\\', '/'));
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractPersona(String markdown) {
        Matcher matcher = FRONT_MATTER_PATTERN.matcher(markdown);
        if (matcher.matches()) {
            return matcher.group(2).strip();
        }
        return markdown.strip();
    }

    private String normalizeSkillId(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试方向不能为空");
        }
        return skillId.strip();
    }

    private String normalizeCategoryKey(String rawKey, int fallbackIndex) {
        String base = StringUtils.hasText(rawKey) ? rawKey.strip().toUpperCase(Locale.ROOT) : "";
        String normalized = base.replaceAll("[^A-Z0-9]+", "_").replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (!StringUtils.hasText(normalized)) {
            normalized = "CATEGORY_" + (fallbackIndex + 1);
        }
        return truncateContent(normalized, MAX_CATEGORY_KEY_LENGTH);
    }

    private String normalizeCategoryLabel(String rawLabel, String fallback) {
        String label = StringUtils.hasText(rawLabel) ? rawLabel.strip() : fallback;
        if (!StringUtils.hasText(label)) {
            label = "Unnamed Category";
        }
        return truncateContent(label, MAX_CATEGORY_LABEL_LENGTH);
    }

    private String normalizePriority(String rawPriority) {
        String normalized = StringUtils.hasText(rawPriority)
                ? rawPriority.strip().toUpperCase(Locale.ROOT)
                : "NORMAL";
        if ("ALWAYS_ONE".equals(normalized) || "CORE".equals(normalized)) {
            return normalized;
        }
        return "NORMAL";
    }

    private String truncateContent(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, Math.max(0, maxLength));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Boolean.parseBoolean(text.strip());
        }
        return Boolean.FALSE;
    }
}
