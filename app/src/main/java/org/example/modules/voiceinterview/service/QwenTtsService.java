package org.example.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.common.config.VoiceInterviewProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Qwen TTS 实时服务（基于 WebSocket）
 *
 * 使用阿里云 DashScope 的 qwen-tts-realtime 模型，通过 WebSocket API 提供实时文本到语音合成。
 * 通过 WebSocket API 使用 qwen-tts-realtime 模型提供实时文本到语音合成。
 *
 * 主要功能：
 * 基于 WebSocket 的实时 TTS 合成
 * 用于手动控制的用户承诺模式
 * 具有 30 秒超时保护的同步合成 API
 * 通过 response.audio.delta 事件自动收集音频块
 * 支持中文，可配置语音、语速和音量
 *
 * 配置：
 * 模型：qwen-tts-realtime
 * 语音：可配置（Cherry、Serena、Ethan 等）
 * - 音频格式：PCM, 24kHz 采样率
 * - 模式：提交（用户控制）
 *
 */
@Service
@Slf4j
public class QwenTtsService {
    // Runtime configuration values (loaded from VoiceInterviewProperties; setters kept for tests)
    private String model;

    private String apiKey;

    private String voice;

    private String format;

    private Integer sampleRate;

    private String mode;

    private String languageType;

    private Float speechRate;

    private Integer volume;

    public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
        VoiceInterviewProperties.QwenTtsConfig tts = voiceInterviewProperties.getQwen().getTts();
        this.model = tts.getModel();
        this.apiKey = tts.getApiKey();
        this.voice = tts.getVoice();
        this.format = tts.getFormat();
        this.sampleRate = tts.getSampleRate();
        this.mode = tts.getMode();
        this.languageType = tts.getLanguageType();
        this.speechRate = tts.getSpeechRate();
        this.volume = tts.getVolume();
    }

    /**
     * Initialize the TTS service.
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenTtsService");
        }
        log.info("QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
                model, voice, sampleRate);
    }

    /**
     * 将文本合成为语音音频。
     *
     * 此方法使用 DashScope 的基于 WebSocket 的 TTS API 将文本同步转换为 PCM 音频数据。
     * 基于 WebSocket 的 TTS API 将文本同步转换为 PCM 音频数据。它建立一个 WebSocket 连接，发送文本进行
     * 合成，收集音频块，并返回完整的音频数据。
     *
     * 该方法使用 CountDownLatch 等待合成完成，超时时间为 30 秒。
     * 超时以防止无限阻塞。
     *
     * 参数 文本 要合成的文本（空、空或仅有空白的文本将返回空数组）
     * 以配置的采样率返回 PCM 音频数据，如果合成失败则返回空数组
     */
    public byte[] synthesize(String text) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }

        CountDownLatch completionLatch = new CountDownLatch(1);
        ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        QwenTtsRealtime ttsRealtime = null;

        try {
            ttsRealtime = createRealtimeClient(text, audioBuffer, completionLatch, completed, errorRef);
            ttsRealtime.connect();
            ttsRealtime.updateSession(buildRealtimeConfig());
            ttsRealtime.appendText(text);
            commitIfNecessary(ttsRealtime);

            if (!awaitCompletion(text, completionLatch)) {
                return new byte[0];
            }
            if (errorRef.get() != null) {
                log.error("Qwen TTS synthesis failed, textLength={}", text.length(), errorRef.get());
                return new byte[0];
            }
            return audioBuffer.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Qwen TTS synthesis interrupted, textLength={}", text.length(), e);
            return new byte[0];
        } catch (Exception e) {
            log.error("Qwen TTS synthesis request failed, textLength={}", text.length(), e);
            return new byte[0];
        } finally {
            closeRealtimeClient(text, ttsRealtime);
        }
    }

    private QwenTtsRealtime createRealtimeClient(
            String text,
            ByteArrayOutputStream audioBuffer,
            CountDownLatch completionLatch,
            AtomicBoolean completed,
            AtomicReference<Throwable> errorRef) {
        QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                .model(model)
                .apikey(apiKey)
                .build();
        return new QwenTtsRealtime(param,
                buildCallback(text, audioBuffer, completionLatch, completed, errorRef));
    }

    private QwenTtsRealtimeCallback buildCallback(
            String text,
            ByteArrayOutputStream audioBuffer,
            CountDownLatch completionLatch,
            AtomicBoolean completed,
            AtomicReference<Throwable> errorRef) {
        return new QwenTtsRealtimeCallback() {
            @Override
            public void onOpen() {
                log.info("Qwen TTS websocket opened, textLength={}", text.length());
            }

            @Override
            public void onEvent(JsonObject event) {
                handleRealtimeEvent(text, event, audioBuffer, completionLatch, completed, errorRef);
            }

            @Override
            public void onClose(int code, String message) {
                log.info("Qwen TTS websocket closed, code={}, message={}, textLength={}",
                        code, message, text.length());
                if (!completed.get()) {
                    completionLatch.countDown();
                }
            }
        };
    }

    private void handleRealtimeEvent(
            String text,
            JsonObject event,
            ByteArrayOutputStream audioBuffer,
            CountDownLatch completionLatch,
            AtomicBoolean completed,
            AtomicReference<Throwable> errorRef) {
        try {
            String eventType = getString(event, "type");
            if (eventType == null || eventType.isBlank()) {
                return;
            }

            switch (eventType) {
                case "response.audio.delta" -> appendAudioChunk(text, event, audioBuffer);
                case "response.done" -> markCompleted(completionLatch, completed);
                case "error" -> handleRemoteError(text, event, completionLatch, completed, errorRef);
                default -> log.debug("Ignored Qwen TTS event, type={}, textLength={}", eventType, text.length());
            }
        } catch (Exception e) {
            log.error("Failed to handle Qwen TTS event, textLength={}, event={}", text.length(), event, e);
            errorRef.compareAndSet(null, e);
            markCompleted(completionLatch, completed);
        }
    }

    private void appendAudioChunk(String text, JsonObject event, ByteArrayOutputStream audioBuffer) {
        String delta = getString(event, "delta");
        if (delta == null || delta.isBlank()) {
            return;
        }

        byte[] chunk = Base64.getDecoder().decode(delta);
        audioBuffer.writeBytes(chunk);
        log.debug("Received Qwen TTS audio chunk, textLength={}, bytes={}", text.length(), chunk.length);
    }

    private void handleRemoteError(
            String text,
            JsonObject event,
            CountDownLatch completionLatch,
            AtomicBoolean completed,
            AtomicReference<Throwable> errorRef) {
        String message = getString(event, "message");
        if (message == null || message.isBlank()) {
            message = event.toString();
        }

        IllegalStateException exception = new IllegalStateException("Qwen TTS remote error: " + message);
        log.error("Qwen TTS remote error, textLength={}, event={}", text.length(), event);
        errorRef.compareAndSet(null, exception);
        markCompleted(completionLatch, completed);
    }

    private QwenTtsRealtimeConfig buildRealtimeConfig() {
        return QwenTtsRealtimeConfig.builder()
                .voice(voice)
                .responseFormat(resolveAudioFormat())
                .mode(mode)
                .languageType(languageType)
                .sampleRate(sampleRate)
                .speechRate(speechRate)
                .volume(volume)
                .format(format)
                .build();
    }

    private QwenTtsRealtimeAudioFormat resolveAudioFormat() {
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    }

    private void commitIfNecessary(QwenTtsRealtime ttsRealtime) {
        if (mode != null && mode.equalsIgnoreCase("commit")) {
            ttsRealtime.commit();
        }
    }

    private boolean awaitCompletion(String text, CountDownLatch completionLatch) throws InterruptedException {
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        if (!completed) {
            log.error("Qwen TTS synthesis timed out after 30 seconds, textLength={}", text.length());
        }
        return completed;
    }

    private void markCompleted(CountDownLatch completionLatch, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            completionLatch.countDown();
        }
    }

    private String getString(JsonObject jsonObject, String key) {
        JsonElement element = jsonObject.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    private void closeRealtimeClient(String text, QwenTtsRealtime ttsRealtime) {
        if (ttsRealtime == null) {
            return;
        }

        try {
            ttsRealtime.finish();
        } catch (Exception e) {
            log.error("Failed to finish Qwen TTS session, textLength={}", text.length(), e);
        }

        try {
            ttsRealtime.close();
        } catch (Exception e) {
            log.error("Failed to close Qwen TTS websocket, textLength={}", text.length(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("QwenTtsService destroyed successfully");
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setLanguageType(String languageType) {
        this.languageType = languageType;
    }

    public void setSpeechRate(Float speechRate) {
        this.speechRate = speechRate;
    }

    public void setVolume(Integer volume) {
        this.volume = volume;
    }
}
