package org.example.modules.voiceinterview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.config.VoiceInterviewProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeRepository;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashscopeLlmService {
    private static final String TERMINAL_PUNCTUATION = "。！？；!?;.";
    private static final int MAX_HISTORY_ENTRIES = 8;
    private static final int MAX_RESUME_CHARS = 5000;
    private static final int MIN_RESPONSE_CHARS = 60;

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
    public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        return doChat(userInput, session, conversationHistory, false, null, null);
    }

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
                                      List<String> conversationHistory) {
        return doChat(userInput, session, conversationHistory, true, onToken, onSentence);
    }

    private String doChat(String userInput,
                          VoiceInterviewSessionEntity session,
                          List<String> conversationHistory,
                          boolean streaming,
                          Consumer<String> onToken,
                          Consumer<String> onSentence) {
        VoiceInterviewSessionEntity safeSession = requireSession(session);
        String normalizedInput = normalizeUserInput(userInput);
        String systemPrompt = buildSystemPrompt(safeSession);
        String userPrompt = buildUserPrompt(normalizedInput, safeSession, conversationHistory);
        ChatClient chatClient = resolveChatClient(safeSession);
        try {
            return streaming
                    ? streamResponse(chatClient, systemPrompt, userPrompt, safeSession, onToken, onSentence)
                    : callResponse(chatClient, systemPrompt, userPrompt, safeSession);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("语音面试 LLM 调用失败: sessionId={}, provider={}",
                    safeSession.getId(), safeSession.getLlmProvider(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "语音面试 LLM 调用失败", e);
        }
    }

    private String callResponse(ChatClient chatClient,
                                String systemPrompt,
                                String userPrompt,
                                VoiceInterviewSessionEntity session) {
        String content = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        String normalized = normalizeResponse(content);
        log.info("语音面试回复完成: sessionId={}, chars={}", session.getId(), normalized.length());
        return normalized;
    }

    private String streamResponse(ChatClient chatClient,
                                  String systemPrompt,
                                  String userPrompt,
                                  VoiceInterviewSessionEntity session,
                                  Consumer<String> onToken,
                                  Consumer<String> onSentence) {
        StreamState state = new StreamState();
        Consumer<String> safeToken = onToken != null ? onToken : token -> {};
        Consumer<String> safeSentence = onSentence != null ? onSentence : sentence -> {};
        chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(chunk -> handleChunk(chunk, state, safeToken, safeSentence))
                .blockLast();
        flushRemainingSentence(state, safeSentence);
        String normalized = normalizeResponse(state.fullText.toString());
        log.info("语音面试流式回复完成: sessionId={}, chars={}", session.getId(), normalized.length());
        return normalized;
    }

    private void handleChunk(String chunk,
                             StreamState state,
                             Consumer<String> onToken,
                             Consumer<String> onSentence) {
        if (!StringUtils.hasText(chunk) || state.truncated) {
            return;
        }
        onToken.accept(chunk);
        state.fullText.append(chunk);
        state.sentenceBuffer.append(chunk);
        emitCompleteSentences(state, onSentence);
        enforceResponseLimit(state);
    }

    private void emitCompleteSentences(StreamState state, Consumer<String> onSentence) {
        int terminalIndex = findTerminalIndex(state.sentenceBuffer);
        while (terminalIndex >= 0) {
            String sentence = state.sentenceBuffer.substring(0, terminalIndex + 1).strip();
            if (StringUtils.hasText(sentence)) {
                onSentence.accept(sentence);
            }
            state.sentenceBuffer.delete(0, terminalIndex + 1);
            trimLeadingWhitespace(state.sentenceBuffer);
            terminalIndex = findTerminalIndex(state.sentenceBuffer);
        }
    }

    private void flushRemainingSentence(StreamState state, Consumer<String> onSentence) {
        String remaining = state.sentenceBuffer.toString().strip();
        if (StringUtils.hasText(remaining)) {
            onSentence.accept(remaining);
        }
    }

    private void enforceResponseLimit(StreamState state) {
        int maxChars = resolveMaxResponseChars();
        if (state.fullText.length() > maxChars) {
            state.truncated = true;
            state.fullText.setLength(maxChars);
        }
        if (state.sentenceBuffer.length() > maxChars) {
            state.sentenceBuffer.setLength(maxChars);
        }
    }

    private VoiceInterviewSessionEntity requireSession(VoiceInterviewSessionEntity session) {
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "语音面试会话不能为空");
        }
        return session;
    }

    private String normalizeUserInput(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户输入不能为空");
        }
        return clipToSentenceBoundary(promptSanitizer.sanitize(userInput), resolveMaxResponseChars());
    }

    private ChatClient resolveChatClient(VoiceInterviewSessionEntity session) {
        String providerId = StringUtils.hasText(session.getLlmProvider())
                ? session.getLlmProvider()
                : voiceInterviewProperties.getLlmProvider();
        return llmProviderRegistry.getChatClientOrDefault(providerId);
    }

    private String buildSystemPrompt(VoiceInterviewSessionEntity session) {
        String basePrompt = promptService.generateSystemPromptWithContext(
                session.getSkillId(),
                loadResumeTextForPrompt(session)
        );
        return basePrompt + """
                
                【当前会话约束】
                1. 当前面试阶段：%s
                2. 回复长度尽量控制在 %d 个汉字以内，并以完整句子结束。
                3. 结合当前阶段推进问题或追问，不要偏题。
                4. 如果信息不足，只追问一个最关键的问题。
                5. 只输出最终回复文本，不要附带额外说明。
                """.formatted(resolvePhaseLabel(session), resolveMaxResponseChars());
    }

    private String buildUserPrompt(String userInput,
                                   VoiceInterviewSessionEntity session,
                                   List<String> conversationHistory) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "会话信息", buildSessionContext(session));
        appendSection(builder, "简历信息", loadResumeContext(session));
        appendSection(builder, "会话历史", buildHistoryContext(conversationHistory));
        appendSection(builder, "当前输入", promptSanitizer.wrapWithDelimiters("voice_user_input", userInput));
        builder.append("请直接返回最终回复文本，不要附带任何说明。");
        return builder.toString().strip();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append("【").append(title).append("】\n").append(content.strip());
    }

    private String buildSessionContext(VoiceInterviewSessionEntity session) {
        StringBuilder builder = new StringBuilder();
        appendContextLine(builder, "sessionId", session.getId() != null ? session.getId().toString() : null);
        appendContextLine(builder, "roleType", session.getRoleType());
        appendContextLine(builder, "skillId", session.getSkillId());
        appendContextLine(builder, "difficulty", session.getDifficulty());
        appendContextLine(builder, "provider", session.getLlmProvider());
        appendContextLine(builder, "currentPhase", resolvePhaseLabel(session));
        appendContextLine(builder, "enabledPhases", resolveEnabledPhases(session));
        appendContextLine(builder, "customJdText", session.getCustomJdText());
        return promptSanitizer.wrapWithDelimiters("voice_session", builder.toString().strip());
    }

    private String loadResumeContext(VoiceInterviewSessionEntity session) {
        if (session.getResumeId() == null) {
            return null;
        }
        return resumeRepository.findById(session.getResumeId())
                .map(this::buildResumeContext)
                .orElseGet(() -> {
                    log.warn("语音面试关联简历不存在: sessionId={}, resumeId={}",
                            session.getId(), session.getResumeId());
                    return null;
                });
    }

    private String buildResumeContext(ResumeEntity resume) {
        StringBuilder builder = new StringBuilder();
        appendContextLine(builder, "resumeId", resume.getId() != null ? resume.getId().toString() : null);
        appendContextLine(builder, "originalFilename", resume.getOriginalFilename());
        appendContextLine(builder, "resumeText", clipToSentenceBoundary(resume.getResumeText(), MAX_RESUME_CHARS));
        return promptSanitizer.wrapWithDelimiters("voice_resume", builder.toString().strip());
    }

    private String loadResumeTextForPrompt(VoiceInterviewSessionEntity session) {
        if (session.getResumeId() == null) {
            return null;
        }
        return resumeRepository.findById(session.getResumeId())
                .map(ResumeEntity::getResumeText)
                .map(text -> clipToSentenceBoundary(text, MAX_RESUME_CHARS))
                .orElse(null);
    }

    private String buildHistoryContext(List<String> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return null;
        }
        List<String> normalizedHistory = normalizeHistory(conversationHistory);
        if (normalizedHistory.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < normalizedHistory.size(); i++) {
            appendHistoryLine(builder, i + 1, normalizedHistory.get(i));
        }
        return promptSanitizer.wrapWithDelimiters("voice_history", builder.toString().strip());
    }

    private List<String> normalizeHistory(List<String> conversationHistory) {
        int start = Math.max(0, conversationHistory.size() - MAX_HISTORY_ENTRIES);
        List<String> normalized = new ArrayList<>();
        for (int i = start; i < conversationHistory.size(); i++) {
            String item = conversationHistory.get(i);
            if (!StringUtils.hasText(item)) {
                continue;
            }
            normalized.add(clipToSentenceBoundary(promptSanitizer.sanitize(item), MAX_RESUME_CHARS));
        }
        return normalized;
    }

    private String normalizeResponse(String response) {
        if (!StringUtils.hasText(response)) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "语音面试回复为空");
        }
        return clipToSentenceBoundary(response, resolveMaxResponseChars());
    }

    private String clipToSentenceBoundary(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.strip();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        int boundaryIndex = findLastTerminalIndex(normalized, maxChars);
        int endIndex = boundaryIndex >= 0 ? boundaryIndex + 1 : maxChars;
        return normalized.substring(0, Math.min(endIndex, normalized.length())).strip();
    }

    private int findTerminalIndex(StringBuilder text) {
        for (int i = 0; i < text.length(); i++) {
            if (TERMINAL_PUNCTUATION.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private int findLastTerminalIndex(String text, int maxChars) {
        int limit = Math.min(text.length(), Math.max(1, maxChars));
        for (int i = limit - 1; i >= 0; i--) {
            if (TERMINAL_PUNCTUATION.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private void trimLeadingWhitespace(StringBuilder buffer) {
        while (buffer.length() > 0 && Character.isWhitespace(buffer.charAt(0))) {
            buffer.deleteCharAt(0);
        }
    }

    private void appendContextLine(StringBuilder builder, String key, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(key).append('=').append(promptSanitizer.sanitize(value.strip()));
    }

    private void appendHistoryLine(StringBuilder builder, int index, String value) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(index).append(". ").append(value.strip());
    }

    private String resolvePhaseLabel(VoiceInterviewSessionEntity session) {
        return session.getCurrentPhase() != null ? session.getCurrentPhase().name() : "UNKNOWN";
    }

    private String resolveEnabledPhases(VoiceInterviewSessionEntity session) {
        List<String> enabled = new ArrayList<>();
        if (Boolean.TRUE.equals(session.getIntroEnabled())) {
            enabled.add("intro");
        }
        if (Boolean.TRUE.equals(session.getTechEnabled())) {
            enabled.add("tech");
        }
        if (Boolean.TRUE.equals(session.getProjectEnabled())) {
            enabled.add("project");
        }
        if (Boolean.TRUE.equals(session.getHrEnabled())) {
            enabled.add("hr");
        }
        return enabled.isEmpty() ? "none" : String.join(",", enabled);
    }

    private int resolveMaxResponseChars() {
        return Math.max(MIN_RESPONSE_CHARS, voiceInterviewProperties.getAiQuestionMaxChars());
    }

    private static final class StreamState {
        private final StringBuilder fullText = new StringBuilder();
        private final StringBuilder sentenceBuffer = new StringBuilder();
        private boolean truncated;
    }
}
