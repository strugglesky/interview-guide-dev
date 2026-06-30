package org.example.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeAudioFormat;
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.common.config.VoiceInterviewProperties;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Qwen3 实时 ASR 服务
 * <p>
 * 使用阿里云 DashScope 的 qwen3-asr-flash 实时模型提供实时语音识别。
 * 该服务可管理多个并发会话的 WebSocket 连接，并通过服务器端转录处理音频。
 * 使用服务器端语音活动检测（VAD）进行音频转录。
 * <p>
 * 主要功能：
 * 利用线程安全并发映射进行多会话管理
 * 服务器端 VAD 具有 400 毫秒静音持续时间，可自动检测句子
 * 基于回调的结果处理，用于实时转录更新
 * 会话终止时自动清理资源
 * <p>
 * 配置：
 * 模型：qwen3-asr-flash-realtime
 * 音频格式：PCM, 16kHz 采样率
 * 语言：中文（zh中文（zh）
 * - VAD：已启用 server_vad 类型
 */
@Service
@Slf4j
public class QwenAsrService {
    // Runtime configuration values (loaded from VoiceInterviewProperties; setters kept for tests)
    private String url;
    private String model;
    private String apiKey;
    private String language;
    private String format;
    private Integer sampleRate;
    private Boolean enableTurnDetection;
    private String turnDetectionType;
    private Float turnDetectionThreshold;
    private Integer turnDetectionSilenceDurationMs;

    public QwenAsrService(VoiceInterviewProperties voiceInterviewProperties) {
        VoiceInterviewProperties.AsrConfig asr = voiceInterviewProperties.getQwen().getAsr();
        this.url = asr.getUrl();
        this.model = asr.getModel();
        this.apiKey = asr.getApiKey();
        this.language = asr.getLanguage();
        this.format = asr.getFormat();
        this.sampleRate = asr.getSampleRate();
        this.enableTurnDetection = asr.isEnableTurnDetection();
        this.turnDetectionType = asr.getTurnDetectionType();
        this.turnDetectionThreshold = asr.getTurnDetectionThreshold();
        this.turnDetectionSilenceDurationMs = asr.getTurnDetectionSilenceDurationMs();
    }

    /**
     * 活动 ASR 会话映射。
     * 键：会话 ID（用户提供的标识符）
     * 值：包含 OmniRealtimeConversation 实例和回调的 AsrSession
     */
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

    /** 防止同一 interview sessionId 上并发 stop/start；并在重连时与 {@link #sessionLocks} 配合 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * Internal class to hold session data.
     */
    private static class AsrSession {
        private final OmniRealtimeConversation conversation;
        private final Consumer<String> onFinal;
        private final Consumer<String> onPartial;
        private final Consumer<Throwable> onError;

        AsrSession(
                OmniRealtimeConversation conversation,
                Consumer<String> onFinal,
                Consumer<String> onPartial,
                Consumer<Throwable> onError) {
            this.conversation = conversation;
            this.onFinal = onFinal;
            this.onPartial = onPartial;
            this.onError = onError;
        }

        public OmniRealtimeConversation getConversation() {
            return conversation;
        }

        public Consumer<Throwable> getOnError() {
            return onError;
        }
    }

    /**
     * 初始化 ASR 服务。
     * 服务构建完成后，Spring 会自动调用此方法。
     * 所有配置值都已从 VoiceInterviewProperties 中加载。
     *
     * 如果未配置 apiKey，则抛出 IllegalStateException。
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenAsrService");
        }
        log.info("QwenAsrService initialized with model: {}, url: {}", model, url);
    }

    /**
     * 开始新的转录会话。
     *
     * 该方法将创建一个新的 WebSocket 连接到 DashScope ASR 服务
     * 并为处理转录结果和错误设置回调。
     *
     * 会话使用服务器端 VAD（语音活动检测）来自动
     * 检测句子边界。语音检测和转录完成后，
     * onResult 回调将调用转录文本。
     *
     * @param sessionId 此会话的唯一标识符
     * @param onFinal 当句子/片段最终完成时的回调（{@code completed} 事件）
     * @param onError 发生错误时调用的回调
     * @throws IllegalStateException 如果会话已存在或服务未初始化，则抛出 IllegalStateException。
     */
    public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, null, onError);
    }

    /**
     * 与 {@link #startTranscription(String, Consumer, Consumer)} 相同，但会转发部分字幕。
     * ({@code conversation.item.input_audio_transcription.text}) 的实时字幕。
     *
     * @param onPartial 如果不需要部分转录，可以为空
     */
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            startTranscriptionLocked(sessionId, onFinal, onPartial, onError);
        }
    }

    private void startTranscriptionLocked(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        validateStartArguments(sessionId, onFinal, onError);
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("ASR session already exists: " + sessionId);
        }

        try {
            OmniRealtimeConversation conversation = createConversation(sessionId, onFinal, onPartial, onError);
            conversation.connect();
            conversation.updateSession(buildRealtimeConfig());
            sessions.put(sessionId, new AsrSession(conversation, onFinal, onPartial, onError));
            log.info("[Session: {}] ASR transcription started", sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Session: {}] Interrupted while starting ASR transcription", sessionId, e);
            throw new IllegalStateException("Interrupted while starting ASR session: " + sessionId, e);
        } catch (Exception e) {
            log.error("[Session: {}] Failed to start ASR transcription", sessionId, e);
            throw new IllegalStateException("Failed to start ASR session: " + sessionId, e);
        }
    }

    /**
     * 停止旧连接并重新建立（用于 ASR WebSocket 被服务端关闭后恢复识别）。
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            log.info("[Session: {}] Restarting DashScope ASR (stop + start)", sessionId);
            stopTranscription(sessionId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startTranscriptionLocked(sessionId, onFinal, onPartial, onError);

            // Verify reconnection succeeded
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                    AsrSession newSession = sessions.get(sessionId);
                    if (newSession != null && newSession.getConversation() != null) {
                        log.info("[Session: {}] ASR reconnection verified successfully", sessionId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Session: {}] ASR reconnection verification interrupted", sessionId);
                    return;
                }
            }

            log.warn("[Session: {}] ASR reconnection may not be fully ready after 1 second", sessionId);
        }
    }


    /**
     * 将音频数据发送到 ASR 服务以进行转录。
     *
     * 音频数据应为 PCM 格式，采样率为 16kHz。
     * 数据在发送到 DashScope 服务之前会进行 Base64 编码。
     *
     * 启用服务器端 VAD 后，服务将自动检测
     * 语音片段，并在检测到静音时触发转录。
     *
     * @param sessionId 会话标识符
     * @param audioData 原始 PCM 音频字节
     * 如果会话不存在，则抛出 IllegalStateException。
     */
    public void sendAudio(String sessionId, byte[] audioData) {
        AsrSession session = requireSession(sessionId);
        if (audioData == null || audioData.length == 0) {
            return;
        }

        try {
            session.getConversation().appendAudio(Base64.getEncoder().encodeToString(audioData));
        } catch (Exception e) {
            log.error("[Session: {}] Failed to send audio to ASR service, bytes={}",
                    sessionId, audioData.length, e);
            handleSessionError(sessionId, session, e);
            throw new IllegalStateException("Failed to send audio for ASR session: " + sessionId, e);
        }
    }

    /**
     * 停止转录并关闭会话。
     *
     * 此方法会通知 ASR 服务完成任何待处理的转录、
     * 等待最终结果，然后关闭 WebSocket 连接。
     *
     * @param sessionId 会话标识符
     */
    public void stopTranscription(String sessionId) {
        synchronized (lockForSession(sessionId)) {
            AsrSession session = sessions.remove(sessionId);
            if (session == null) {
                sessionLocks.remove(sessionId);
                return;
            }

            try {
                session.getConversation().endSession();
                log.info("[Session: {}] ASR transcription stopped", sessionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[Session: {}] Interrupted while stopping ASR transcription", sessionId, e);
                handleCallbackError(session.getOnError(), e);
            } catch (Exception e) {
                log.error("[Session: {}] Failed to stop ASR transcription cleanly", sessionId, e);
                handleCallbackError(session.getOnError(), e);
            } finally {
                closeConversationQuietly(sessionId, session.getConversation());
                sessionLocks.remove(sessionId);
            }
        }
    }

    private void validateStartArguments(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<Throwable> onError) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("Session ID must not be blank");
        }
        if (onFinal == null) {
            throw new IllegalStateException("Final transcription callback must not be null");
        }
        if (onError == null) {
            throw new IllegalStateException("Error callback must not be null");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Qwen ASR service is not initialized with API key");
        }
    }

    private OmniRealtimeConversation createConversation(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(model)
                .apikey(apiKey)
                .url(url)
                .build();
        return new OmniRealtimeConversation(param, buildCallback(sessionId, onFinal, onPartial, onError));
    }

    private OmniRealtimeCallback buildCallback(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        return new OmniRealtimeCallback() {
            @Override
            public void onOpen() {
                log.info("[Session: {}] ASR websocket opened", sessionId);
            }

            @Override
            public void onEvent(JsonObject event) {
                handleRealtimeEvent(sessionId, event, onFinal, onPartial, onError);
            }

            @Override
            public void onClose(int code, String message) {
                log.info("[Session: {}] ASR websocket closed, code={}, message={}",
                        sessionId, code, message);
            }
        };
    }

    private OmniRealtimeConfig buildRealtimeConfig() {
        OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
        transcriptionParam.setLanguage(language);
        transcriptionParam.setInputAudioFormat(format);
        transcriptionParam.setInputSampleRate(sampleRate);

        return OmniRealtimeConfig.builder()
                .modalities(List.of(OmniRealtimeModality.TEXT))
                .inputAudioFormat(resolveAudioFormat())
                .enableInputAudioTranscription(true)
                .transcriptionConfig(transcriptionParam)
                .enableTurnDetection(Boolean.TRUE.equals(enableTurnDetection))
                .turnDetectionType(turnDetectionType)
                .turnDetectionThreshold(turnDetectionThreshold == null ? 0.0f : turnDetectionThreshold)
                .turnDetectionSilenceDurationMs(
                        turnDetectionSilenceDurationMs == null ? 1000 : turnDetectionSilenceDurationMs)
                .build();
    }

    private OmniRealtimeAudioFormat resolveAudioFormat() {
        if (sampleRate != null && sampleRate == 24000) {
            return OmniRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
        }
        return OmniRealtimeAudioFormat.PCM_16000HZ_MONO_16BIT;
    }

    private void handleRealtimeEvent(
            String sessionId,
            JsonObject event,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        try {
            String eventType = getString(event, "type");
            if (eventType == null || eventType.isBlank()) {
                return;
            }

            switch (eventType) {
                case "conversation.item.input_audio_transcription.completed" ->
                        dispatchTranscript(event, onFinal);
                case "conversation.item.input_audio_transcription.text",
                        "conversation.item.input_audio_transcription.delta" ->
                        dispatchTranscript(event, onPartial);
                case "error" -> handleRemoteError(sessionId, event, onError);
                default -> log.debug("[Session: {}] Ignored ASR event type={}", sessionId, eventType);
            }
        } catch (Exception e) {
            log.error("[Session: {}] Failed to handle ASR event: {}", sessionId, event, e);
            handleCallbackError(onError, e);
        }
    }

    private void dispatchTranscript(JsonObject event, Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        String transcript = extractTranscript(event);
        if (transcript == null || transcript.isBlank()) {
            return;
        }
        callback.accept(transcript);
    }

    private String extractTranscript(JsonObject event) {
        String transcript = getNestedString(event, "transcript");
        if (transcript != null && !transcript.isBlank()) {
            return transcript;
        }
        transcript = getNestedString(event, "text");
        if (transcript != null && !transcript.isBlank()) {
            return transcript;
        }
        return getNestedString(event, "delta");
    }

    private String getNestedString(JsonObject event, String key) {
        JsonElement itemElement = event.get("item");
        if (itemElement != null && itemElement.isJsonObject()) {
            String itemValue = getString(itemElement.getAsJsonObject(), key);
            if (itemValue != null) {
                return itemValue;
            }
        }
        JsonElement transcriptionElement = event.get("transcription");
        if (transcriptionElement != null && transcriptionElement.isJsonObject()) {
            String transcriptionValue = getString(transcriptionElement.getAsJsonObject(), key);
            if (transcriptionValue != null) {
                return transcriptionValue;
            }
        }
        return getString(event, key);
    }

    private void handleRemoteError(
            String sessionId,
            JsonObject event,
            Consumer<Throwable> onError) {
        String message = getString(event, "message");
        if (message == null || message.isBlank()) {
            message = event.toString();
        }
        IllegalStateException exception =
                new IllegalStateException("ASR remote error for session " + sessionId + ": " + message);
        log.error("[Session: {}] Remote ASR error: {}", sessionId, event);
        handleCallbackError(onError, exception);
    }

    private String getString(JsonObject jsonObject, String key) {
        JsonElement element = jsonObject.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    private AsrSession requireSession(String sessionId) {
        AsrSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("ASR session does not exist: " + sessionId);
        }
        return session;
    }

    private void handleSessionError(String sessionId, AsrSession session, Throwable throwable) {
        handleCallbackError(session.getOnError(), throwable);
        synchronized (lockForSession(sessionId)) {
            AsrSession removed = sessions.remove(sessionId);
            if (removed != null) {
                closeConversationQuietly(sessionId, removed.getConversation());
            }
            sessionLocks.remove(sessionId);
        }
    }

    private void handleCallbackError(Consumer<Throwable> onError, Throwable throwable) {
        try {
            onError.accept(throwable);
        } catch (Exception callbackException) {
            log.error("ASR error callback execution failed", callbackException);
        }
    }

    private void closeConversationQuietly(String sessionId, OmniRealtimeConversation conversation) {
        try {
            conversation.close();
        } catch (Exception e) {
            log.error("[Session: {}] Failed to close ASR websocket", sessionId, e);
        }
    }

    /**
     * 销毁服务并清理所有活动会话。
     *
     * Spring 容器关闭时会自动调用此方法。
     * 它会停止所有活动会话并释放资源。
     */
    @PreDestroy
    public void destroy() {
        log.info("Destroying QwenAsrService with {} active sessions", sessions.size());

        // Stop all active sessions
        sessions.keySet().forEach(sessionId -> {
            try {
                stopTranscription(sessionId);
            } catch (Exception e) {
                log.error("[Session: {}] Error during cleanup", sessionId, e);
            }
        });

        sessions.clear();
        log.info("QwenAsrService destroyed successfully");
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setEnableTurnDetection(Boolean enableTurnDetection) {
        this.enableTurnDetection = enableTurnDetection;
    }

    public void setTurnDetectionType(String turnDetectionType) {
        this.turnDetectionType = turnDetectionType;
    }

    public void setTurnDetectionThreshold(Float turnDetectionThreshold) {
        this.turnDetectionThreshold = turnDetectionThreshold;
    }

    public void setTurnDetectionSilenceDurationMs(Integer turnDetectionSilenceDurationMs) {
        this.turnDetectionSilenceDurationMs = turnDetectionSilenceDurationMs;
    }
}
