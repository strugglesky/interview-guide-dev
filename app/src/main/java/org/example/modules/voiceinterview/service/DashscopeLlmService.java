package org.example.modules.voiceinterview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.config.VoiceInterviewProperties;
import org.example.modules.resume.repository.ResumeRepository;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashscopeLlmService {
    private static final String TERMINAL_PUNCTUATION = "。！？；!?;.";

    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewPromptService promptService;
    private final ResumeRepository resumeRepository;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final PromptSanitizer promptSanitizer;

    /**
     * 调用 LLM 生成回答，返回完整优化后的文本。
     * @param userInput 用户输入
     * @param session 语音面试会话
     * @param conversationHistory 会话历史
     * @return 模型输出
     */
    public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory){}

    /**
     * 流式调用 LLM，每检测到一个完整句子就回调 onSentence，同时推送实时文本给 onToken。
     * 返回完整优化后的文本。
     * @param userInput 用户输入
     * @param onToken: 每一个生成的 token
     * @param onSentence: 每一个完整句子
     * @param session: 语音面试会话
     * @param conversationHistory: 会话历史
     *
     */
    public String chatStreamSentences(String userInput,
                                      Consumer<String> onToken,
                                      Consumer<String> onSentence,
                                      VoiceInterviewSessionEntity session,
                                      List<String> conversationHistory){}



}
