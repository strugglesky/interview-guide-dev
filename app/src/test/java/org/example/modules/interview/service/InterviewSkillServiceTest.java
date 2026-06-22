package org.example.modules.interview.service;

import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.config.InterviewSkillProperties;
import org.example.common.config.LlmProviderProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.interview.skill.InterviewSkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Interview skill service tests")
class InterviewSkillServiceTest {

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    @Mock
    private ChatClient chatClient;

    private InterviewSkillService interviewSkillService;

    @BeforeEach
    void setUp() throws Exception {
        interviewSkillService = createService();
        registerPresetSkillData();
    }

    @Nested
    @DisplayName("Preset skills")
    class PresetSkills {

        @Test
        @DisplayName("should load preset skills from classpath")
        void shouldLoadPresetSkillsFromClasspath() {
            List<InterviewSkillService.SkillDTO> skills = interviewSkillService.getAllSkills();

            assertThat(skills).isNotEmpty();
            assertThat(skills).extracting(InterviewSkillService.SkillDTO::id)
                    .contains("java-backend");
        }

        @Test
        @DisplayName("should get preset java backend skill with normalized categories")
        void shouldGetPresetJavaBackendSkillWithNormalizedCategories() {
            InterviewSkillService.SkillDTO skill = interviewSkillService.getSkill("java-backend");

            assertThat(skill.id()).isEqualTo("java-backend");
            assertThat(skill.isPreset()).isTrue();
            assertThat(skill.categories()).extracting(InterviewSkillService.SkillCategoryDTO::key)
                    .contains("JAVA", "MYSQL", "REDIS", "SPRING", "PROJECT");
            assertThat(skill.categories()).anySatisfy(category -> {
                assertThat(category.key()).isEqualTo("JAVA");
                assertThat(category.ref()).isEqualTo("java.md");
                assertThat(category.shared()).isTrue();
                assertThat(category.priority()).isEqualTo("CORE");
            });
        }

        @Test
        @DisplayName("should throw business exception when skill does not exist")
        void shouldThrowBusinessExceptionWhenSkillDoesNotExist() {
            assertThatThrownBy(() -> interviewSkillService.getSkill("missing-skill"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception ->
                            assertThat(((BusinessException) exception).getCode())
                                    .isEqualTo(ErrorCode.NOT_FOUND.getCode()));
        }
    }

    @Nested
    @DisplayName("Build custom skill")
    class BuildCustomSkill {

        @Test
        @DisplayName("should build custom skill and reuse mapped references")
        void shouldBuildCustomSkillAndReuseMappedReferences() {
            List<InterviewSkillService.CategoryDTO> categories = List.of(
                    new InterviewSkillService.CategoryDTO("java", "Java Core", "core", null, null),
                    new InterviewSkillService.CategoryDTO(
                            "project",
                            "Project Experience",
                            "always_one",
                            null,
                            null
                    ),
                    new InterviewSkillService.CategoryDTO(
                            "custom-topic",
                            "Custom Topic",
                            "normal",
                            "custom.md",
                            false
                    )
            );
            String jd = """
                    负责 Java 后端服务开发，涉及 Spring Boot、MySQL、Redis 与项目交付，
                    需要候选人具备良好的系统设计能力和真实项目经验。
                    """;

            InterviewSkillService.SkillDTO skill = interviewSkillService.buildCustomSkill(categories, jd);

            assertThat(skill.id()).isEqualTo(InterviewSkillService.CUSTOM_SKILL_ID);
            assertThat(skill.isPreset()).isFalse();
            assertThat(skill.sourceJd()).isEqualTo(jd.strip());
            assertThat(skill.categories()).hasSize(3);
            assertThat(skill.categories()).anySatisfy(category -> {
                assertThat(category.key()).isEqualTo("JAVA");
                assertThat(category.ref()).isEqualTo("java.md");
                assertThat(category.shared()).isTrue();
                assertThat(category.priority()).isEqualTo("CORE");
            });
            assertThat(skill.categories()).anySatisfy(category -> {
                assertThat(category.key()).isEqualTo("PROJECT");
                assertThat(category.priority()).isEqualTo("ALWAYS_ONE");
            });
            assertThat(skill.categories()).anySatisfy(category -> {
                assertThat(category.key()).isEqualTo("CUSTOM_TOPIC");
                assertThat(category.ref()).isEqualTo("custom.md");
                assertThat(category.shared()).isFalse();
                assertThat(category.priority()).isEqualTo("NORMAL");
            });
            assertThat(skill.persona()).contains("Java Core");
            assertThat(skill.persona()).contains(jd.strip());
        }

        @Test
        @DisplayName("should return preset skill when non custom skill id is provided")
        void shouldReturnPresetSkillWhenNonCustomSkillIdIsProvided() {
            InterviewSkillService.SkillDTO skill = interviewSkillService.buildCustomSkill(
                    "这段 JD 不会被使用，因为 skillId 指向预设技能。",
                    "java-backend"
            );

            assertThat(skill.id()).isEqualTo("java-backend");
            assertThat(skill.isPreset()).isTrue();
        }
    }

    @Nested
    @DisplayName("JD parsing")
    class JdParsing {

        @Test
        @DisplayName("should parse jd and normalize categories")
        void shouldParseJdAndNormalizeCategories() {
            String jd = """
                    负责 Java 后端服务研发，参与高并发系统设计与核心链路性能优化，
                    熟悉 Spring Boot、MySQL、Redis，能够独立负责复杂项目交付与排障。
                    """;
            List<InterviewSkillService.CategoryDTO> parsed = List.of(
                    new InterviewSkillService.CategoryDTO("java", "Java后端", "core", null, null),
                    new InterviewSkillService.CategoryDTO(
                            "system design scenario",
                            "系统设计",
                            "normal",
                            null,
                            null
                    )
            );
            when(llmProviderRegistry.getPlainChatClient(null)).thenReturn(chatClient);
            when(structuredOutputInvoker.invoke(
                    eq(chatClient),
                    anyString(),
                    anyString(),
                    any(BeanOutputConverter.class),
                    eq(ErrorCode.AI_SERVICE_ERROR),
                    eq("JD 解析失败: "),
                    eq("jd_parse"),
                    any()
            )).thenReturn(categoryList(parsed));

            List<InterviewSkillService.CategoryDTO> result = interviewSkillService.parseJd(jd);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).key()).isEqualTo("JAVA");
            assertThat(result.get(0).ref()).isEqualTo("java.md");
            assertThat(result.get(0).shared()).isTrue();
            assertThat(result.get(0).priority()).isEqualTo("CORE");
            assertThat(result.get(1).key()).isEqualTo("SYSTEM_DESIGN_SCENARIO");
            assertThat(result.get(1).ref()).isEqualTo("system-design-scenarios.md");
        }

        @Test
        @DisplayName("should reject blank jd before invoking llm")
        void shouldRejectBlankJdBeforeInvokingLlm() {
            assertThatThrownBy(() -> interviewSkillService.parseJd(" "))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception ->
                            assertThat(((BusinessException) exception).getCode())
                                    .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
            verifyNoInteractions(llmProviderRegistry, structuredOutputInvoker);
        }

        @Test
        @DisplayName("should reject too short jd before invoking llm")
        void shouldRejectTooShortJdBeforeInvokingLlm() {
            assertThatThrownBy(() -> interviewSkillService.parseJd("负责 Java 开发"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception ->
                            assertThat(((BusinessException) exception).getCode())
                                    .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
            verifyNoInteractions(llmProviderRegistry, structuredOutputInvoker);
        }
    }

    @Nested
    @DisplayName("Allocation and references")
    class AllocationAndReferences {

        @Test
        @DisplayName("should calculate allocation with always one and core priority")
        void shouldCalculateAllocationWithAlwaysOneAndCorePriority() {
            List<InterviewSkillService.SkillCategoryDTO> categories = List.of(
                    new InterviewSkillService.SkillCategoryDTO(
                            "PROJECT", "项目经验", "ALWAYS_ONE", null, false
                    ),
                    new InterviewSkillService.SkillCategoryDTO(
                            "JAVA", "Java", "CORE", "java.md", true
                    ),
                    new InterviewSkillService.SkillCategoryDTO(
                            "MYSQL", "MySQL", "CORE", "mysql.md", true
                    ),
                    new InterviewSkillService.SkillCategoryDTO(
                            "SPRING", "Spring", "NORMAL", "spring.md", true
                    )
            );

            Map<String, Integer> allocation = interviewSkillService.calculateAllocation(categories, 5);

            assertThat(allocation).containsEntry("PROJECT", 1);
            assertThat(allocation).containsEntry("JAVA", 2);
            assertThat(allocation).containsEntry("MYSQL", 1);
            assertThat(allocation).containsEntry("SPRING", 1);
        }

        @Test
        @DisplayName("should build allocation description in category order")
        void shouldBuildAllocationDescriptionInCategoryOrder() {
            List<InterviewSkillService.SkillCategoryDTO> categories = List.of(
                    new InterviewSkillService.SkillCategoryDTO(
                            "JAVA", "Java", "CORE", "java.md", true
                    ),
                    new InterviewSkillService.SkillCategoryDTO(
                            "PROJECT", "项目经验", "ALWAYS_ONE", null, false
                    )
            );
            Map<String, Integer> allocation = Map.of("JAVA", 2, "PROJECT", 1);

            String description = interviewSkillService.buildAllocationDescription(allocation, categories);

            assertThat(description).contains("| Java | JAVA | CORE | 2 |");
            assertThat(description).contains("| 项目经验 | PROJECT | ALWAYS_ONE | 1 |");
        }

        @Test
        @DisplayName("should build reference section for allocated categories only")
        void shouldBuildReferenceSectionForAllocatedCategoriesOnly() {
            InterviewSkillService.SkillDTO skill = interviewSkillService.getSkill("java-backend");
            Map<String, Integer> allocation = Map.of("JAVA", 2, "MYSQL", 0, "PROJECT", 1);

            String section = interviewSkillService.buildReferenceSection(skill, allocation);

            assertThat(section).contains("### Java (JAVA)");
            assertThat(section).contains("Java");
            assertThat(section).doesNotContain("### MySQL (MYSQL)");
        }

        @Test
        @DisplayName("should build empty safe evaluation reference when skill id invalid")
        void shouldBuildEmptySafeEvaluationReferenceWhenSkillIdInvalid() {
            String section = interviewSkillService.buildEvaluationReferenceSectionSafe("missing-skill");

            assertThat(section).isEmpty();
        }
    }

    private InterviewSkillService createService() throws IOException {
        return new InterviewSkillService(
                llmProviderRegistry,
                structuredOutputInvoker,
                new DefaultResourceLoader(),
                new PromptSanitizer(new LlmProviderProperties())
        );
    }

    @SuppressWarnings("unchecked")
    private void registerPresetSkillData() throws Exception {
        Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry =
                (Map<String, InterviewSkillProperties.SkillDefinition>) getFieldValue("presetRegistry");
        Map<String, Object> categoryRefIndex =
                (Map<String, Object>) getFieldValue("categoryRefIndex");
        presetRegistry.clear();
        categoryRefIndex.clear();

        List<InterviewSkillProperties.CategoryDef> categories = List.of(
                new InterviewSkillProperties.CategoryDef("JAVA", "Java", "CORE", "java.md", true),
                new InterviewSkillProperties.CategoryDef("MYSQL", "MySQL", "CORE", "mysql.md", true),
                new InterviewSkillProperties.CategoryDef("REDIS", "Redis", "CORE", "redis.md", true),
                new InterviewSkillProperties.CategoryDef("SPRING", "Spring", "NORMAL", "spring.md", true),
                new InterviewSkillProperties.CategoryDef(
                        "SYSTEM_DESIGN_SCENARIO",
                        "系统设计/场景题",
                        "NORMAL",
                        "system-design-scenarios.md",
                        true
                ),
                new InterviewSkillProperties.CategoryDef("PROJECT", "项目经验", "ALWAYS_ONE", null, false)
        );
        InterviewSkillProperties.SkillDefinition definition = new InterviewSkillProperties.SkillDefinition(
                "java-backend",
                "用于 Java 后端面试出题",
                "你是一位 Java 后端面试官",
                "Java 后端开发",
                new InterviewSkillProperties.DisplayDef(
                        "J",
                        "from-blue-500 to-indigo-500",
                        "bg-blue-100",
                        "text-blue-600"
                ),
                categories
        );
        presetRegistry.put("java-backend", definition);
        categoryRefIndex.put("JAVA", createRefMapping("java.md", true, "java-backend"));
        categoryRefIndex.put("MYSQL", createRefMapping("mysql.md", true, "java-backend"));
        categoryRefIndex.put("REDIS", createRefMapping("redis.md", true, "java-backend"));
        categoryRefIndex.put("SPRING", createRefMapping("spring.md", true, "java-backend"));
        categoryRefIndex.put(
                "SYSTEM_DESIGN_SCENARIO",
                createRefMapping("system-design-scenarios.md", true, "java-backend")
        );
    }

    private Object getFieldValue(String fieldName) throws Exception {
        Field field = InterviewSkillService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(interviewSkillService);
    }

    private Object createRefMapping(String ref, boolean shared, String sourceSkillId) {
        try {
            Class<?> mappingClass = Class.forName(
                    "org.example.modules.interview.skill.InterviewSkillService$RefMapping"
            );
            var constructor = mappingClass.getDeclaredConstructor(
                    String.class,
                    boolean.class,
                    String.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(ref, shared, sourceSkillId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create RefMapping for test", e);
        }
    }

    private Object categoryList(List<InterviewSkillService.CategoryDTO> categories) {
        try {
            Class<?> dtoClass = Class.forName(
                    "org.example.modules.interview.skill.InterviewSkillService$CategoryListDTO"
            );
            var constructor = dtoClass.getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(categories);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create CategoryListDTO for test", e);
        }
    }
}
