package org.example.modules.interview.controller;

import org.example.common.constant.CommonConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.common.result.Result;
import org.example.modules.interview.skill.InterviewSkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Interview skill controller tests")
class InterviewSkillControllerTest {

    @Mock
    private InterviewSkillService interviewSkillService;

    @InjectMocks
    private InterviewSkillController interviewSkillController;

    @Nested
    @DisplayName("List and detail")
    class ListAndDetailTests {

        @Test
        @DisplayName("should return all skills")
        void shouldReturnAllSkills() {
            List<InterviewSkillService.SkillDTO> expected = List.of(buildSkill("java-backend"));
            when(interviewSkillService.getAllSkills()).thenReturn(expected);

            Result<List<InterviewSkillService.SkillDTO>> result = interviewSkillController.listSkills();

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(interviewSkillService).getAllSkills();
        }

        @Test
        @DisplayName("should return skill by id")
        void shouldReturnSkillById() {
            InterviewSkillService.SkillDTO expected = buildSkill("java-backend");
            when(interviewSkillService.getSkill("java-backend")).thenReturn(expected);

            Result<InterviewSkillService.SkillDTO> result = interviewSkillController.getSkill("java-backend");

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(interviewSkillService).getSkill("java-backend");
        }

        @Test
        @DisplayName("should propagate business exception from service")
        void shouldPropagateBusinessExceptionFromService() {
            when(interviewSkillService.getSkill(anyString()))
                    .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "面试方向不存在"));

            assertThatThrownBy(() -> interviewSkillController.getSkill("missing-skill"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("面试方向不存在");
        }
    }

    @Nested
    @DisplayName("Parse JD")
    class ParseJdTests {

        @Test
        @DisplayName("should parse jd text and return categories")
        void shouldParseJdTextAndReturnCategories() {
            List<InterviewSkillService.CategoryDTO> expected = List.of(
                    new InterviewSkillService.CategoryDTO("JAVA", "Java", "CORE", "java.md", true),
                    new InterviewSkillService.CategoryDTO(
                            "PROJECT",
                            "项目经验",
                            "ALWAYS_ONE",
                            null,
                            false
                    )
            );
            InterviewSkillController.ParseJdRequest request =
                    new InterviewSkillController.ParseJdRequest("负责 Java 后端开发");
            when(interviewSkillService.parseJd("负责 Java 后端开发")).thenReturn(expected);

            Result<List<InterviewSkillService.CategoryDTO>> result = interviewSkillController.parseJd(request);

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(interviewSkillService).parseJd("负责 Java 后端开发");
        }

        @Test
        @DisplayName("should propagate business exception when jd parsing fails")
        void shouldPropagateBusinessExceptionWhenJdParsingFails() {
            InterviewSkillController.ParseJdRequest request =
                    new InterviewSkillController.ParseJdRequest("负责 Java 后端开发");
            when(interviewSkillService.parseJd("负责 Java 后端开发"))
                    .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "JD 不能为空"));

            assertThatThrownBy(() -> interviewSkillController.parseJd(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("JD 不能为空");
        }
    }

    private void assertSuccess(Result<?> result) {
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(CommonConstants.StatusCode.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
    }

    private InterviewSkillService.SkillDTO buildSkill(String id) {
        return new InterviewSkillService.SkillDTO(
                id,
                "Java 后端开发",
                "Java 后端面试方向",
                List.of(
                        new InterviewSkillService.SkillCategoryDTO("JAVA", "Java", "CORE", "java.md", true)
                ),
                true,
                "",
                "面试官 persona",
                new InterviewSkillService.DisplayDTO("J", "from-blue-500 to-indigo-500", "bg-blue-100", "text-blue-600")
        );
    }
}
