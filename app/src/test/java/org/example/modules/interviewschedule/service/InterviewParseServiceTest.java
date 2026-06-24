package org.example.modules.interviewschedule.service;

import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.config.LlmProviderProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.interviewschedule.model.ParseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试邀约解析服务测试")
public class InterviewParseServiceTest {

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private ChatClient chatClient;

    private InterviewParseService interviewParseService;

    @BeforeEach
    void setUp() {
        interviewParseService = new InterviewParseService(
                llmProviderRegistry,
                new ObjectMapper(),
                new PromptSanitizer(new LlmProviderProperties())
        );
    }

    @Nested
    @DisplayName("规则解析")
    class RuleParse {

        @Test
        @DisplayName("应按飞书格式直接解析完整面试信息")
        void shouldParseFeishuInviteByRules() {
            String rawText = """
                    【阿里巴巴】后端开发工程师一面邀请
                    公司：阿里巴巴
                    岗位：后端开发工程师
                    时间：2026-04-15 19:30
                    面试形式：视频面试（飞书）
                    面试官：李老师
                    备注：请提前10分钟入会
                    https://meeting.feishu.cn/abc123
                    """;

            ParseResponse result = interviewParseService.parse(rawText, "feishu");

            assertThat(result.getSuccess()).isTrue();
            assertThat(result.getParseMethod()).isEqualTo("rule");
            assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.90D);
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().getCompanyName()).isEqualTo("阿里巴巴");
            assertThat(result.getData().getPosition()).isEqualTo("后端开发工程师");
            assertThat(result.getData().getInterviewTime())
                    .isEqualTo(LocalDateTime.of(2026, 4, 15, 19, 30));
            assertThat(result.getData().getInterviewType()).isEqualTo("VIDEO");
            assertThat(result.getData().getMeetingLink())
                    .isEqualTo("https://meeting.feishu.cn/abc123");
            assertThat(result.getData().getRoundNumber()).isEqualTo(1);
            assertThat(result.getData().getInterviewer()).isEqualTo("李老师");
            assertThat(result.getData().getNotes()).contains("请提前10分钟入会", "面试官：李老师");
            verify(llmProviderRegistry, never()).getPlainChatClient(null);
        }

        @Test
        @DisplayName("应在腾讯会议文本缺少链接时提取会议号和密码")
        void shouldParseTencentMeetingIdAndPasswordByRules() {
            String rawText = """
                    【字节跳动】Java开发二面邀请
                    公司：字节跳动
                    岗位：Java开发
                    2026-05-20 14:00
                    腾讯会议
                    会议号：123456789
                    密码：8888
                    面试官：王老师
                    时长 45 分钟
                    """;

            ParseResponse result = interviewParseService.parse(rawText, "tencent");

            assertThat(result.getSuccess()).isTrue();
            assertThat(result.getParseMethod()).isEqualTo("rule");
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().getCompanyName()).isEqualTo("字节跳动");
            assertThat(result.getData().getPosition()).isEqualTo("Java开发");
            assertThat(result.getData().getInterviewTime())
                    .isEqualTo(LocalDateTime.of(2026, 5, 20, 14, 0));
            assertThat(result.getData().getInterviewType()).isEqualTo("VIDEO");
            assertThat(result.getData().getMeetingLink()).isEqualTo("会议号: 123456789，密码: 8888");
            assertThat(result.getData().getRoundNumber()).isEqualTo(2);
            assertThat(result.getData().getNotes()).contains("时长约45分钟");
            verify(llmProviderRegistry, never()).getPlainChatClient(null);
        }
    }

    @Nested
    @DisplayName("AI兜底解析")
    class AiFallbackParse {

        @Test
        @DisplayName("规则信息不足时应回退到AI补齐必填字段")
        void shouldFallbackToAiWhenRuleParsingIncomplete() {
            mockAiResponse("""
                    {"companyName":"美团","position":"算法工程师","interviewTime":"2026-06-01T10:30:00",
                    "interviewType":"PHONE","meetingLink":"","roundNumber":3,"interviewer":"陈老师","notes":"HR电话初筛"}
                    """);
            String rawText = """
                    你好，邀请你参加后续面试，请尽快确认。
                    联系人：陈老师
                    """;

            ParseResponse result = interviewParseService.parse(rawText, "other");

            assertThat(result.getSuccess()).isTrue();
            assertThat(result.getParseMethod()).isEqualTo("ai");
            assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.80D);
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().getCompanyName()).isEqualTo("美团");
            assertThat(result.getData().getPosition()).isEqualTo("算法工程师");
            assertThat(result.getData().getInterviewTime())
                    .isEqualTo(LocalDateTime.of(2026, 6, 1, 10, 30, 0));
            assertThat(result.getData().getInterviewType()).isEqualTo("PHONE");
            assertThat(result.getData().getRoundNumber()).isEqualTo(1);
            assertThat(result.getData().getInterviewer()).isEqualTo("陈老师");
            assertThat(result.getData().getNotes()).contains("HR电话初筛", "面试官：陈老师");
            assertThat(result.getLog()).contains("aiResponse=", "parseMethod=ai");
            verify(llmProviderRegistry).getPlainChatClient(null);
        }

        @Test
        @DisplayName("AI解析失败时应返回失败响应而不是抛出运行时异常")
        void shouldReturnFailedResponseWhenAiFallbackFails() {
            mockAiResponse("");

            ParseResponse result = interviewParseService.parse("请安排下一轮沟通", "other");

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getParseMethod()).isEqualTo("ai");
            assertThat(result.getConfidence()).isZero();
            assertThat(result.getData()).isNull();
            assertThat(result.getLog()).contains("aiResponse=<empty>", "missingFields");
            verify(llmProviderRegistry).getPlainChatClient(null);
        }
    }

    @Nested
    @DisplayName("参数校验")
    class Validation {

        @Test
        @DisplayName("文本为空时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenRawTextBlank() {
            assertThatThrownBy(() -> interviewParseService.parse("   ", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
        }
    }

    private void mockAiResponse(String aiContent) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(llmProviderRegistry.getPlainChatClient(null)).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(aiContent);
    }
}
