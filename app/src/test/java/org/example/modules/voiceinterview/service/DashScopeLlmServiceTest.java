package org.example.modules.voiceinterview.service;

import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.config.VoiceInterviewProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeRepository;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Dashscope 语音面试服务测试")
public class DashScopeLlmServiceTest {

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private VoiceInterviewPromptService promptService;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private PromptSanitizer promptSanitizer;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    private final VoiceInterviewProperties voiceInterviewProperties = buildProperties();

    private DashscopeLlmService dashscopeLlmService;

    @BeforeEach
    void setUp() {
        dashscopeLlmService = new DashscopeLlmService(
                llmProviderRegistry,
                promptService,
                resumeRepository,
                voiceInterviewProperties,
                promptSanitizer
        );
    }

    @Nested
    @DisplayName("普通对话")
    class ChatTests {

        @Test
        @DisplayName("应生成完整回复并使用 promptService 组装系统提示")
        void shouldChatWithPromptServiceAndResumeContext() {
            VoiceInterviewSessionEntity session = buildSession();
            session.setResumeId(88L);
            ResumeEntity resume = buildResume();
            List<String> history = List.of("候选人：我主要做过订单系统。", "面试官：那你说说库存一致性。");

            when(promptSanitizer.sanitize(anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(0, String.class));
            when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
                    .thenAnswer(invocation -> "<" + invocation.getArgument(0, String.class) + ">\n"
                            + invocation.getArgument(1, String.class) + "\n</"
                            + invocation.getArgument(0, String.class) + ">");
            when(resumeRepository.findById(88L)).thenReturn(Optional.of(resume));
            when(promptService.generateSystemPromptWithContext(eq("java-backend"),
                    eq("候选人负责订单系统与库存服务。")))
                    .thenReturn("基础系统提示");
            when(llmProviderRegistry.getChatClientOrDefault("dashscope")).thenReturn(chatClient);
            when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                    .thenReturn("请你详细说一下库存扣减的幂等设计。");

            String result = dashscopeLlmService.chat("我用 Redis 做了防重。", session, history);

            assertThat(result).isEqualTo("请你详细说一下库存扣减的幂等设计。");
            verify(promptService).generateSystemPromptWithContext(
                    "java-backend",
                    "候选人负责订单系统与库存服务。"
            );
            verify(llmProviderRegistry).getChatClientOrDefault("dashscope");
            ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
            verify(chatClient.prompt(), atLeastOnce()).system(systemPromptCaptor.capture());
            assertThat(systemPromptCaptor.getAllValues()).contains(
                    "基础系统提示\n【当前会话约束】\n1. 当前面试阶段：TECH\n2. 回复长度尽量控制在 120 个汉字以内，并以完整句子结束。\n3. 结合当前阶段推进问题或追问，不要偏题。\n4. 如果信息不足，只追问一个最关键的问题。\n5. 只输出最终回复文本，不要附带额外说明。\n"
            );
        }

        @Test
        @DisplayName("用户输入为空时应抛出业务异常")
        void shouldThrowWhenUserInputBlank() {
            VoiceInterviewSessionEntity session = buildSession();

            assertThatThrownBy(() -> dashscopeLlmService.chat("   ", session, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));

            verify(promptService, never()).generateSystemPromptWithContext(anyString(), anyString());
        }

        @Test
        @DisplayName("会话为空时应抛出业务异常")
        void shouldThrowWhenSessionNull() {
            assertThatThrownBy(() -> dashscopeLlmService.chat("你好", null, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.VOICE_SESSION_NOT_FOUND.getCode()));
        }
    }

    @Nested
    @DisplayName("流式对话")
    class StreamTests {

        @Test
        @DisplayName("应按 token 和完整句子回调并返回完整文本")
        void shouldStreamTokensAndSentences() {
            VoiceInterviewSessionEntity session = buildSession();
            List<String> tokens = new ArrayList<>();
            List<String> sentences = new ArrayList<>();

            when(promptSanitizer.sanitize(anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(0, String.class));
            when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1, String.class));
            when(promptService.generateSystemPromptWithContext(eq("java-backend"), eq(null)))
                    .thenReturn("流式系统提示");
            when(llmProviderRegistry.getChatClientOrDefault("dashscope")).thenReturn(chatClient);
            when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                    .thenReturn(Flux.just("先介绍一下项目背景。", "然后说说你怎么做容量评估？"));

            String result = dashscopeLlmService.chatStreamSentences(
                    "可以。",
                    tokens::add,
                    sentences::add,
                    session,
                    List.of("面试官：介绍一下你的项目。")
            );

            assertThat(result).isEqualTo("先介绍一下项目背景。然后说说你怎么做容量评估？");
            assertThat(tokens).containsExactly("先介绍一下项目背景。", "然后说说你怎么做容量评估？");
            assertThat(sentences).containsExactly("先介绍一下项目背景。", "然后说说你怎么做容量评估？");
        }

        @Test
        @DisplayName("流式末尾无终止标点时应在结束时补发剩余句子")
        void shouldFlushRemainingSentenceWhenNoTerminalPunctuation() {
            VoiceInterviewSessionEntity session = buildSession();
            List<String> sentences = new ArrayList<>();

            when(promptSanitizer.sanitize(anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(0, String.class));
            when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1, String.class));
            when(promptService.generateSystemPromptWithContext(eq("java-backend"), eq(null)))
                    .thenReturn("流式系统提示");
            when(llmProviderRegistry.getChatClientOrDefault("dashscope")).thenReturn(chatClient);
            when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                    .thenReturn(Flux.just("你提到了消息队列", "那具体怎么保证顺序消费"));

            String result = dashscopeLlmService.chatStreamSentences(
                    "我用了 Kafka",
                    token -> {
                    },
                    sentences::add,
                    session,
                    List.of()
            );

            assertThat(result).isEqualTo("你提到了消息队列那具体怎么保证顺序消费");
            assertThat(sentences).containsExactly("你提到了消息队列那具体怎么保证顺序消费");
        }
    }

    private static VoiceInterviewProperties buildProperties() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        properties.setLlmProvider("dashscope");
        properties.setAiQuestionMaxChars(120);
        return properties;
    }

    private VoiceInterviewSessionEntity buildSession() {
        VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
        session.setId(12L);
        session.setRoleType("backend");
        session.setSkillId("java-backend");
        session.setDifficulty("mid");
        session.setLlmProvider("dashscope");
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);
        session.setIntroEnabled(true);
        session.setTechEnabled(true);
        session.setProjectEnabled(true);
        session.setHrEnabled(false);
        session.setResumeId(null);
        return session;
    }

    private ResumeEntity buildResume() {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(88L);
        resume.setOriginalFilename("resume.pdf");
        resume.setResumeText("候选人负责订单系统与库存服务。");
        return resume;
    }
}
