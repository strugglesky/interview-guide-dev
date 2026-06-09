package org.example.modules.resume.service;

import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.exception.BusinessException;
import org.example.common.config.ResumeAnalysisProperties;
import org.example.common.model.ErrorCode;
import org.example.modules.resume.model.ResumeAnalysisResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历评分服务
 * 使用Spring AI调用LLM对简历进行评分和建议
 */
@Service
public class ResumeGradingService {
    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);

    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;

    public ResumeGradingService(ChatClient.Builder chatClientBuilder,
                                StructuredOutputInvoker structuredOutputInvoker,
                                ResumeAnalysisProperties properties,
                                ResourceLoader resourceLoader) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }

    // 中间DTO用于接收AI响应
    private record ResumeAnalysisResponseDTO(
            int overallScore,
            ScoreDetailDTO scoreDetail,
            String summary,
            List<String> strengths,
            List<SuggestionDTO> suggestions
    ) {}

    private record ScoreDetailDTO(
            int contentScore,
            int structureScore,
            int skillMatchScore,
            int expressionScore,
            int projectScore
    ) {}

    private record SuggestionDTO(
            String category,
            String priority,
            String issue,
            String recommendation
    ) {}

    /**
     * 分析简历并返回评分和建议
     *
     * @param resumeText 简历文本内容
     * @return 分析结果
     */
    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        String normalizedResumeText = validateResumeText(resumeText);
        log.info("开始分析简历: textLength={}", normalizedResumeText.length());
        try {
            String systemPrompt = renderSystemPrompt();
            String userPrompt = renderUserPrompt(normalizedResumeText);
            ResumeAnalysisResponseDTO response = structuredOutputInvoker.invoke(
                    chatClient,
                    systemPrompt,
                    userPrompt,
                    outputConverter,
                    ErrorCode.RESUME_ANALYSIS_FAILED,
                    "简历分析失败: ",
                    "resume_analysis",
                    log
            );
            // 结构化输出先落到中间 DTO，再转换为项目对外统一使用的响应对象。
            ResumeAnalysisResponse result = toResumeAnalysisResponse(response, normalizedResumeText);
            log.info(
                    "简历分析完成: textLength={}, overallScore={}, strengthsCount={}, suggestionsCount={}",
                    normalizedResumeText.length(),
                    result.overallScore(),
                    result.strengths().size(),
                    result.suggestions().size()
            );
            return result;
        } catch (BusinessException e) {
            // 参数错误仍然按原语义抛出，只有评分失败才降级为错误响应。
            if (ErrorCode.BAD_REQUEST.getCode().equals(e.getCode())) {
                throw e;
            }
            log.error("简历分析失败，返回兜底错误响应: textLength={}, error={}",
                    normalizedResumeText.length(), e.getMessage(), e);
            return buildErrorResponse(normalizedResumeText, e.getMessage());
        } catch (Exception e) {
            log.error("简历分析出现未预期异常，返回兜底错误响应: textLength={}",
                    normalizedResumeText.length(), e);
            return buildErrorResponse(normalizedResumeText, "简历分析服务暂时不可用");
        }
    }

    private String validateResumeText(String resumeText) {
        if (!StringUtils.hasText(resumeText)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文本不能为空");
        }
        return resumeText.strip();
    }

    private String renderSystemPrompt() {
        // 将结构化输出格式要求拼接到系统提示词中，确保模型按约定 JSON 结构返回。
        return systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
    }

    private String renderUserPrompt(String resumeText) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("resumeText", resumeText);
        return userPromptTemplate.render(params);
    }

    private ResumeAnalysisResponse toResumeAnalysisResponse(
            ResumeAnalysisResponseDTO response,
            String resumeText
    ) {
        if (response == null) {
            return buildErrorResponse(resumeText, "简历分析结果为空");
        }
        ScoreDetailDTO scoreDetail = response.scoreDetail();
        ResumeAnalysisResponse.ScoreDetail detail = new ResumeAnalysisResponse.ScoreDetail(
                scoreDetail != null ? scoreDetail.contentScore() : 0,
                scoreDetail != null ? scoreDetail.structureScore() : 0,
                scoreDetail != null ? scoreDetail.skillMatchScore() : 0,
                scoreDetail != null ? scoreDetail.expressionScore() : 0,
                scoreDetail != null ? scoreDetail.projectScore() : 0
        );
        return new ResumeAnalysisResponse(
                response.overallScore(),
                detail,
                response.summary(),
                response.strengths() != null ? response.strengths() : List.of(),
                toSuggestionList(response.suggestions()),
                resumeText
        );
    }

    private List<ResumeAnalysisResponse.Suggestion> toSuggestionList(
            List<SuggestionDTO> suggestions
    ) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }
        return suggestions.stream()
                .map(suggestion -> new ResumeAnalysisResponse.Suggestion(
                        suggestion.category(),
                        suggestion.priority(),
                        suggestion.issue(),
                        suggestion.recommendation()
                ))
                .toList();
    }

    private ResumeAnalysisResponse buildErrorResponse(String resumeText, String errorMessage) {
        String normalizedErrorMessage = StringUtils.hasText(errorMessage)
                ? errorMessage
                : "简历分析服务暂时不可用";
        // 评分失败时返回结构完整的兜底对象，保证后续持久化和展示链路可继续工作。
        return new ResumeAnalysisResponse(
                0,
                new ResumeAnalysisResponse.ScoreDetail(0, 0, 0, 0, 0),
                "简历评分暂时失败，请稍后重试",
                List.of(),
                List.of(new ResumeAnalysisResponse.Suggestion(
                        "系统",
                        "高",
                        normalizedErrorMessage,
                        "请稍后重试简历分析；如问题持续存在，请检查模型服务配置和提示词模板。"
                )),
                resumeText
        );
    }
}
