package org.example.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.google.gson.JsonObject;
import org.example.common.config.VoiceInterviewProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("Qwen TTS 服务测试")
public class QwenTtsServiceTest {

    @Nested
    @DisplayName("初始化")
    class InitTests {

        @Test
        @DisplayName("apiKey 为空时应抛出异常")
        void shouldThrowWhenApiKeyBlank() {
            VoiceInterviewProperties properties = buildProperties();
            properties.getQwen().getTts().setApiKey("   ");
            QwenTtsService service = new QwenTtsService(properties);

            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("API key must be configured");
        }
    }

    @Nested
    @DisplayName("同步合成")
    class SynthesizeTests {

        @Test
        @DisplayName("文本为空时应直接返回空数组")
        void shouldReturnEmptyArrayWhenTextBlank() {
            QwenTtsService service = new QwenTtsService(buildProperties());

            try (MockedConstruction<QwenTtsRealtime> mockedConstruction =
                         mockConstruction(QwenTtsRealtime.class)) {
                byte[] result = service.synthesize("   ");

                assertThat(result).isEmpty();
                assertThat(mockedConstruction.constructed()).isEmpty();
            }
        }

        @Test
        @DisplayName("应建立连接、提交文本并返回拼接后的音频数据")
        void shouldConnectCommitAndReturnCombinedAudio() throws Exception {
            QwenTtsService service = new QwenTtsService(buildProperties());
            AtomicReference<QwenTtsRealtimeCallback> callbackRef = new AtomicReference<>();
            byte[] firstChunk = "audio-1".getBytes(StandardCharsets.UTF_8);
            byte[] secondChunk = "audio-2".getBytes(StandardCharsets.UTF_8);

            try (MockedConstruction<QwenTtsRealtime> mockedConstruction =
                         mockConstruction(QwenTtsRealtime.class, (mock, context) -> {
                             callbackRef.set((QwenTtsRealtimeCallback) context.arguments().get(1));
                             doAnswer(invocation -> {
                                 callbackRef.get().onEvent(buildAudioDeltaEvent(firstChunk));
                                 callbackRef.get().onEvent(buildAudioDeltaEvent(secondChunk));
                                 callbackRef.get().onEvent(buildDoneEvent());
                                 return null;
                             }).when(mock).appendText("你好，请介绍一下项目经验");
                         })) {
                byte[] result = service.synthesize("你好，请介绍一下项目经验");

                QwenTtsRealtime realtime = mockedConstruction.constructed().getFirst();
                ArgumentCaptor<QwenTtsRealtimeConfig> configCaptor =
                        ArgumentCaptor.forClass(QwenTtsRealtimeConfig.class);

                verify(realtime).connect();
                verify(realtime).updateSession(configCaptor.capture());
                verify(realtime).appendText("你好，请介绍一下项目经验");
                verify(realtime).commit();
                verify(realtime).finish();
                verify(realtime).close();
                assertThat(result)
                        .containsExactly(concat(firstChunk, secondChunk));

                QwenTtsRealtimeConfig config = configCaptor.getValue();
                assertThat(config.getVoice()).isEqualTo("Cherry");
                assertThat(config.getMode()).isEqualTo("commit");
                assertThat(config.getLanguageType()).isEqualTo("Chinese");
                assertThat(config.getSampleRate()).isEqualTo(24000);
                assertThat(config.getSpeechRate()).isEqualTo(1.0f);
                assertThat(config.getVolume()).isEqualTo(60);
                assertThat(config.getFormat()).isEqualTo("pcm");
                assertThat(config.getResponseFormat())
                        .isEqualTo(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT);
            }
        }

        @Test
        @DisplayName("非 commit 模式下不应调用 commit")
        void shouldNotCommitWhenModeIsNotCommit() throws Exception {
            VoiceInterviewProperties properties = buildProperties();
            properties.getQwen().getTts().setMode("stream");
            QwenTtsService service = new QwenTtsService(properties);
            AtomicReference<QwenTtsRealtimeCallback> callbackRef = new AtomicReference<>();

            try (MockedConstruction<QwenTtsRealtime> mockedConstruction =
                         mockConstruction(QwenTtsRealtime.class, (mock, context) -> {
                             callbackRef.set((QwenTtsRealtimeCallback) context.arguments().get(1));
                             doAnswer(invocation -> {
                                 callbackRef.get().onEvent(buildAudioDeltaEvent("ok".getBytes(StandardCharsets.UTF_8)));
                                 callbackRef.get().onEvent(buildDoneEvent());
                                 return null;
                             }).when(mock).appendText("继续追问一下");
                         })) {
                byte[] result = service.synthesize("继续追问一下");

                QwenTtsRealtime realtime = mockedConstruction.constructed().getFirst();
                verify(realtime, never()).commit();
                verify(realtime).finish();
                verify(realtime).close();
                assertThat(result).isNotEmpty();
            }
        }

        @Test
        @DisplayName("收到远端错误事件时应返回空数组并关闭连接")
        void shouldReturnEmptyArrayWhenRemoteErrorOccurs() throws Exception {
            QwenTtsService service = new QwenTtsService(buildProperties());
            AtomicReference<QwenTtsRealtimeCallback> callbackRef = new AtomicReference<>();

            try (MockedConstruction<QwenTtsRealtime> mockedConstruction =
                         mockConstruction(QwenTtsRealtime.class, (mock, context) -> {
                             callbackRef.set((QwenTtsRealtimeCallback) context.arguments().get(1));
                             doAnswer(invocation -> {
                                 callbackRef.get().onEvent(buildErrorEvent("remote tts failed"));
                                 return null;
                             }).when(mock).appendText("这是一段失败的文本");
                         })) {
                byte[] result = service.synthesize("这是一段失败的文本");

                QwenTtsRealtime realtime = mockedConstruction.constructed().getFirst();
                assertThat(result).isEmpty();
                verify(realtime).connect();
                verify(realtime).appendText("这是一段失败的文本");
                verify(realtime).commit();
                verify(realtime).finish();
                verify(realtime).close();
            }
        }

        @Test
        @DisplayName("文本发送异常时应返回空数组并关闭连接")
        void shouldReturnEmptyArrayWhenAppendTextFails() throws Exception {
            QwenTtsService service = new QwenTtsService(buildProperties());

            try (MockedConstruction<QwenTtsRealtime> mockedConstruction =
                         mockConstruction(QwenTtsRealtime.class, (mock, context) ->
                                 org.mockito.Mockito.doThrow(new IllegalStateException("append failed"))
                                         .when(mock).appendText(anyString()))) {
                byte[] result = service.synthesize("请继续");

                QwenTtsRealtime realtime = mockedConstruction.constructed().getFirst();
                assertThat(result).isEmpty();
                verify(realtime).connect();
                verify(realtime).updateSession(org.mockito.ArgumentMatchers.any(QwenTtsRealtimeConfig.class));
                verify(realtime).appendText("请继续");
                verify(realtime).finish();
                verify(realtime).close();
            }
        }
    }

    private static VoiceInterviewProperties buildProperties() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        VoiceInterviewProperties.QwenTtsConfig tts = properties.getQwen().getTts();
        tts.setModel("qwen3-tts-flash-realtime");
        tts.setApiKey("test-api-key");
        tts.setVoice("Cherry");
        tts.setFormat("pcm");
        tts.setSampleRate(24000);
        tts.setMode("commit");
        tts.setLanguageType("Chinese");
        tts.setSpeechRate(1.0f);
        tts.setVolume(60);
        return properties;
    }

    private static JsonObject buildAudioDeltaEvent(byte[] chunk) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.audio.delta");
        event.addProperty("delta", Base64.getEncoder().encodeToString(chunk));
        return event;
    }

    private static JsonObject buildDoneEvent() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.done");
        return event;
    }

    private static JsonObject buildErrorEvent(String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "error");
        event.addProperty("message", message);
        return event;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] merged = new byte[first.length + second.length];
        System.arraycopy(first, 0, merged, 0, first.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }
}
