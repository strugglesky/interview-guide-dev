package org.example.modules.resume.service;

import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.config.ResumeAnalysisProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历评分服务测试")
class ResumeGradingServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;

    private ResumeGradingService resumeGradingService;

    @BeforeEach
    void setUp() throws IOException {
        ResumeAnalysisProperties properties = new ResumeAnalysisProperties();
        when(chatClientBuilder.build()).thenReturn(chatClient);
        resumeGradingService = new ResumeGradingService(
                chatClientBuilder,
                structuredOutputInvoker,
                properties,
                new DefaultResourceLoader()
        );
    }

    @Nested
    @DisplayName("成功分析")
    class AnalyzeSuccess {

        @Test
        @DisplayName("应调用结构化输出并返回映射后的分析结果")
        void shouldAnalyzeResumeSuccessfully() throws Exception {
            Object responseDto = buildResponseDto(
                    88,
                    buildScoreDetailDto(20, 18, 22, 12, 14),
                    "简历整体较强，项目经历有一定亮点",
                    List.of("技术栈覆盖较完整", "项目经历较丰富"),
                    List.of(
                            buildSuggestionDto("项目", "高", "量化指标不足", "补充性能优化前后对比数据"),
                            buildSuggestionDto("表达", "中", "措辞略平", "改用结果导向的项目描述")
                    )
            );
            when(structuredOutputInvoker.invoke(
                    eq(chatClient),
                    any(String.class),
                    any(String.class),
                    any(BeanOutputConverter.class),
                    eq(ErrorCode.RESUME_ANALYSIS_FAILED),
                    eq("简历分析失败: "),
                    eq("resume_analysis"),
                    any(Logger.class)
            )).thenReturn(responseDto);

            // 使用带首尾空白的简历文本，验证服务会先做标准化再传给模型和返回结果。
            ResumeAnalysisResponse result = resumeGradingService.analyzeResume("  Java resume content  ");

            assertThat(result.overallScore()).isEqualTo(88);
            assertThat(result.summary()).isEqualTo("简历整体较强，项目经历有一定亮点");
            assertThat(result.scoreDetail().contentScore()).isEqualTo(20);
            assertThat(result.scoreDetail().structureScore()).isEqualTo(18);
            assertThat(result.scoreDetail().skillMatchScore()).isEqualTo(22);
            assertThat(result.scoreDetail().expressionScore()).isEqualTo(12);
            assertThat(result.scoreDetail().projectScore()).isEqualTo(14);
            assertThat(result.strengths()).containsExactly("技术栈覆盖较完整", "项目经历较丰富");
            assertThat(result.suggestions()).hasSize(2);
            assertThat(result.suggestions().getFirst().category()).isEqualTo("项目");
            assertThat(result.originalText()).isEqualTo("Java resume content");

            // 捕获用户提示词，确认传给提示模板的是去除首尾空白后的简历内容。
            ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
            verify(structuredOutputInvoker).invoke(
                    eq(chatClient),
                    any(String.class),
                    userPromptCaptor.capture(),
                    any(BeanOutputConverter.class),
                    eq(ErrorCode.RESUME_ANALYSIS_FAILED),
                    eq("简历分析失败: "),
                    eq("resume_analysis"),
                    any(Logger.class)
            );
            assertThat(userPromptCaptor.getValue()).contains("Java resume content");
        }

        @Test
        @DisplayName("scoreDetail为空时应回退为全零分项")
        void shouldFallbackToZeroScoresWhenScoreDetailMissing() throws Exception {
            Object responseDto = buildResponseDto(
                    60,
                    null,
                    "分项评分缺失",
                    List.of("摘要正常"),
                    List.of()
            );
            when(structuredOutputInvoker.invoke(
                    eq(chatClient),
                    any(String.class),
                    any(String.class),
                    any(BeanOutputConverter.class),
                    eq(ErrorCode.RESUME_ANALYSIS_FAILED),
                    eq("简历分析失败: "),
                    eq("resume_analysis"),
                    any(Logger.class)
            )).thenReturn(responseDto);

            ResumeAnalysisResponse result = resumeGradingService.analyzeResume("resume");

            assertThat(result.overallScore()).isEqualTo(60);
            assertThat(result.scoreDetail().contentScore()).isZero();
            assertThat(result.scoreDetail().structureScore()).isZero();
            assertThat(result.scoreDetail().skillMatchScore()).isZero();
            assertThat(result.scoreDetail().expressionScore()).isZero();
            assertThat(result.scoreDetail().projectScore()).isZero();
        }
    }

    @Nested
    @DisplayName("失败兜底")
    class AnalyzeFallback {

        @Test
        @DisplayName("结构化调用抛出业务异常时应返回错误响应")
        void shouldReturnErrorResponseWhenBusinessExceptionThrown() {
            when(structuredOutputInvoker.invoke(
                    eq(chatClient),
                    any(String.class),
                    any(String.class),
                    any(BeanOutputConverter.class),
                    eq(ErrorCode.RESUME_ANALYSIS_FAILED),
                    eq("简历分析失败: "),
                    eq("resume_analysis"),
                    any(Logger.class)
            )).thenThrow(new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "模型服务不可用"));

            // AI 评分失败时不再抛出异常，而是返回结构完整的兜底响应。
            ResumeAnalysisResponse result = resumeGradingService.analyzeResume("resume");

            assertThat(result.overallScore()).isZero();
            assertThat(result.summary()).isEqualTo("简历评分暂时失败，请稍后重试");
            assertThat(result.suggestions()).hasSize(1);
            assertThat(result.suggestions().getFirst().category()).isEqualTo("系统");
            assertThat(result.suggestions().getFirst().priority()).isEqualTo("高");
            assertThat(result.suggestions().getFirst().issue()).contains("模型服务不可用");
            assertThat(result.originalText()).isEqualTo("resume");
        }

        @Test
        @DisplayName("结构化调用抛出未知异常时应返回通用错误响应")
        void shouldReturnGenericErrorResponseWhenUnexpectedExceptionThrown() {
            when(structuredOutputInvoker.invoke(
                    eq(chatClient),
                    any(String.class),
                    any(String.class),
                    any(BeanOutputConverter.class),
                    eq(ErrorCode.RESUME_ANALYSIS_FAILED),
                    eq("简历分析失败: "),
                    eq("resume_analysis"),
                    any(Logger.class)
            )).thenThrow(new IllegalStateException("boom"));

            ResumeAnalysisResponse result = resumeGradingService.analyzeResume("resume");

            assertThat(result.overallScore()).isZero();
            assertThat(result.suggestions()).hasSize(1);
            assertThat(result.suggestions().getFirst().issue()).isEqualTo("简历分析服务暂时不可用");
        }

        @Test
        @DisplayName("结构化调用返回空结果时应返回错误响应")
        void shouldReturnErrorResponseWhenResponseDtoNull() {
            when(structuredOutputInvoker.invoke(
                    eq(chatClient),
                    any(String.class),
                    any(String.class),
                    any(BeanOutputConverter.class),
                    eq(ErrorCode.RESUME_ANALYSIS_FAILED),
                    eq("简历分析失败: "),
                    eq("resume_analysis"),
                    any(Logger.class)
            )).thenReturn(null);

            ResumeAnalysisResponse result = resumeGradingService.analyzeResume("resume");

            assertThat(result.overallScore()).isZero();
            assertThat(result.suggestions()).hasSize(1);
            assertThat(result.suggestions().getFirst().issue()).isEqualTo("简历分析结果为空");
        }
    }

    @Nested
    @DisplayName("参数校验")
    class Validation {

        @Test
        @DisplayName("简历文本为空时应抛出业务异常")
        void shouldThrowWhenResumeTextBlank() {
            assertThatThrownBy(() -> resumeGradingService.analyzeResume("   "))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> {
                        BusinessException businessException = (BusinessException) exception;
                        assertThat(businessException.getCode()).isEqualTo(
                                ErrorCode.BAD_REQUEST.getCode()
                        );
                    });
        }
    }

    private Object buildResponseDto(
            int overallScore,
            Object scoreDetail,
            String summary,
            List<String> strengths,
            List<Object> suggestions
    ) throws Exception {
        Class<?> dtoClass = Class.forName(
                "org.example.modules.resume.service.ResumeGradingService$ResumeAnalysisResponseDTO"
        );
        Constructor<?> constructor = dtoClass.getDeclaredConstructor(
                int.class,
                Class.forName(
                        "org.example.modules.resume.service.ResumeGradingService$ScoreDetailDTO"
                ),
                String.class,
                List.class,
                List.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(overallScore, scoreDetail, summary, strengths, suggestions);
    }

    private Object buildScoreDetailDto(
            int contentScore,
            int structureScore,
            int skillMatchScore,
            int expressionScore,
            int projectScore
    ) throws Exception {
        Class<?> dtoClass = Class.forName(
                "org.example.modules.resume.service.ResumeGradingService$ScoreDetailDTO"
        );
        Constructor<?> constructor = dtoClass.getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                int.class,
                int.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                contentScore,
                structureScore,
                skillMatchScore,
                expressionScore,
                projectScore
        );
    }

    private Object buildSuggestionDto(
            String category,
            String priority,
            String issue,
            String recommendation
    ) throws Exception {
        Class<?> dtoClass = Class.forName(
                "org.example.modules.resume.service.ResumeGradingService$SuggestionDTO"
        );
        Constructor<?> constructor = dtoClass.getDeclaredConstructor(
                String.class,
                String.class,
                String.class,
                String.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(category, priority, issue, recommendation);
    }
}
