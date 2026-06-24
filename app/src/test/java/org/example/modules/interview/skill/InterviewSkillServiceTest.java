package org.example.modules.interview.skill;

import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("Interview skill service tests")
class InterviewSkillServiceTest {

    @Test
    @DisplayName("should extract skill id from classpath resource description")
    void shouldExtractSkillIdFromClasspathResourceDescription() throws Exception {
        InterviewSkillService service = buildService();
        Method method = InterviewSkillService.class.getDeclaredMethod(
                "extractSkillId",
                org.springframework.core.io.Resource.class
        );
        method.setAccessible(true);

        Object skillId = method.invoke(
                service,
                new DefaultResourceLoader().getResource("classpath:skills/java-backend/SKILL.md")
        );

        assertThat(skillId).isEqualTo("java-backend");
    }

    @Test
    @DisplayName("should load preset java backend skill")
    void shouldLoadPresetJavaBackendSkill() throws Exception {
        InterviewSkillService service = buildService();

        assertThatCode(service::loadPresetSkills).doesNotThrowAnyException();
        InterviewSkillService.SkillDTO skill = service.getSkill("java-backend");

        assertThat(skill).isNotNull();
        assertThat(skill.id()).isEqualTo("java-backend");
        assertThat(skill.categories()).isNotEmpty();
    }

    @Test
    @DisplayName("should throw not found when preset skill missing")
    void shouldThrowNotFoundWhenPresetSkillMissing() throws Exception {
        InterviewSkillService service = buildService();
        service.loadPresetSkills();

        assertThatThrownBy(() -> service.getSkill("missing-skill"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.getCode()));
    }

    private InterviewSkillService buildService() throws IOException {
        return new InterviewSkillService(
                mock(LlmProviderRegistry.class),
                mock(StructuredOutputInvoker.class),
                new DefaultResourceLoader(),
                mock(PromptSanitizer.class)
        );
    }
}
