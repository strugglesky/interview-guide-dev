package org.example.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonObject;
import org.example.common.config.VoiceInterviewProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("Qwen ASR 服务测试")
public class QwenAsrServiceTest {

    @Nested
    @DisplayName("初始化")
    class InitTests {

        @Test
        @DisplayName("apiKey 为空时应抛出异常")
        void shouldThrowWhenApiKeyBlank() {
            VoiceInterviewProperties properties = buildProperties();
            properties.getQwen().getAsr().setApiKey("   ");
            QwenAsrService service = new QwenAsrService(properties);

            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("API key must be configured");
        }
    }

    @Nested
    @DisplayName("会话管理")
    class SessionLifecycleTests {

        @Test
        @DisplayName("启动转录时应建立连接并下发实时配置")
        void shouldConnectAndUpdateSessionWhenStartTranscription() throws NoApiKeyException, InterruptedException {
            QwenAsrService service = new QwenAsrService(buildProperties());
            AtomicReference<OmniRealtimeCallback> callbackRef = new AtomicReference<>();

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class, (mock, context) ->
                                 callbackRef.set((OmniRealtimeCallback) context.arguments().get(1)))) {
                service.startTranscription("session-1", text -> {
                }, error -> {
                });

                OmniRealtimeConversation conversation = mockedConstruction.constructed().getFirst();
                ArgumentCaptor<OmniRealtimeConfig> configCaptor =
                        ArgumentCaptor.forClass(OmniRealtimeConfig.class);
                verify(conversation).connect();
                verify(conversation).updateSession(configCaptor.capture());
                assertThat(callbackRef.get()).isNotNull();

                OmniRealtimeConfig config = configCaptor.getValue();
                assertThat(config.isEnableInputAudioTranscription()).isTrue();
                assertThat(config.isEnableTurnDetection()).isTrue();
                assertThat(config.getTurnDetectionType()).isEqualTo("server_vad");
                assertThat(config.getTurnDetectionSilenceDurationMs()).isEqualTo(1000);
                assertThat(config.getTranscriptionConfig().getLanguage()).isEqualTo("zh");
                assertThat(config.getTranscriptionConfig().getInputAudioFormat()).isEqualTo("pcm");
                assertThat(config.getTranscriptionConfig().getInputSampleRate()).isEqualTo(16000);
            }
        }

        @Test
        @DisplayName("重复启动同一会话时应抛出异常")
        void shouldThrowWhenStartingDuplicatedSession() {
            QwenAsrService service = new QwenAsrService(buildProperties());

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class)) {
                service.startTranscription("session-1", text -> {
                }, error -> {
                });

                assertThatThrownBy(() -> service.startTranscription("session-1", text -> {
                }, error -> {
                }))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ASR session already exists");

                assertThat(mockedConstruction.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("发送音频时应进行 Base64 编码并透传给会话")
        void shouldEncodeAudioBeforeSending() {
            QwenAsrService service = new QwenAsrService(buildProperties());
            byte[] audioData = "pcm-audio".getBytes(StandardCharsets.UTF_8);

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class)) {
                service.startTranscription("session-1", text -> {
                }, error -> {
                });
                service.sendAudio("session-1", audioData);

                OmniRealtimeConversation conversation = mockedConstruction.constructed().getFirst();
                verify(conversation).appendAudio(Base64.getEncoder().encodeToString(audioData));
            }
        }

        @Test
        @DisplayName("空音频不应发送到底层会话")
        void shouldIgnoreEmptyAudio() {
            QwenAsrService service = new QwenAsrService(buildProperties());

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class)) {
                service.startTranscription("session-1", text -> {
                }, error -> {
                });
                service.sendAudio("session-1", new byte[0]);

                OmniRealtimeConversation conversation = mockedConstruction.constructed().getFirst();
                verifyNoInteractionsOnAudio(conversation);
            }
        }

        @Test
        @DisplayName("会话不存在时发送音频应抛出异常")
        void shouldThrowWhenSendingAudioWithoutSession() {
            QwenAsrService service = new QwenAsrService(buildProperties());

            assertThatThrownBy(() -> service.sendAudio("missing-session", new byte[]{1, 2, 3}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ASR session does not exist");
        }

        @Test
        @DisplayName("停止转录时应结束会话并关闭连接")
        void shouldEndSessionAndCloseWhenStopTranscription() throws InterruptedException {
            QwenAsrService service = new QwenAsrService(buildProperties());

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class)) {
                service.startTranscription("session-1", text -> {
                }, error -> {
                });
                service.stopTranscription("session-1");

                OmniRealtimeConversation conversation = mockedConstruction.constructed().getFirst();
                verify(conversation).endSession();
                verify(conversation).close();

                assertThatThrownBy(() -> service.sendAudio("session-1", new byte[]{1}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ASR session does not exist");
            }
        }
    }

    @Nested
    @DisplayName("事件回调")
    class CallbackTests {

        @Test
        @DisplayName("收到最终转录事件时应回调 onFinal")
        void shouldDispatchFinalTranscript() {
            QwenAsrService service = new QwenAsrService(buildProperties());
            AtomicReference<OmniRealtimeCallback> callbackRef = new AtomicReference<>();
            List<String> finals = new ArrayList<>();

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class, (mock, context) ->
                                 callbackRef.set((OmniRealtimeCallback) context.arguments().get(1)))) {
                service.startTranscription("session-1", finals::add, error -> {
                });

                callbackRef.get().onEvent(buildItemEvent(
                        "conversation.item.input_audio_transcription.completed",
                        "transcript",
                        "你好，请介绍一下你的项目经验"
                ));

                assertThat(finals).containsExactly("你好，请介绍一下你的项目经验");
                assertThat(mockedConstruction.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("收到部分转录事件时应回调 onPartial")
        void shouldDispatchPartialTranscript() {
            QwenAsrService service = new QwenAsrService(buildProperties());
            AtomicReference<OmniRealtimeCallback> callbackRef = new AtomicReference<>();
            List<String> partials = new ArrayList<>();

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class, (mock, context) ->
                                 callbackRef.set((OmniRealtimeCallback) context.arguments().get(1)))) {
                service.startTranscription("session-1", text -> {
                }, partials::add, error -> {
                });

                callbackRef.get().onEvent(buildItemEvent(
                        "conversation.item.input_audio_transcription.delta",
                        "delta",
                        "我主要负责订单"
                ));
                callbackRef.get().onEvent(buildItemEvent(
                        "conversation.item.input_audio_transcription.text",
                        "text",
                        "我主要负责订单系统"
                ));

                assertThat(partials).containsExactly("我主要负责订单", "我主要负责订单系统");
            }
        }

        @Test
        @DisplayName("收到远端错误事件时应回调 onError")
        void shouldDispatchRemoteError() {
            QwenAsrService service = new QwenAsrService(buildProperties());
            AtomicReference<OmniRealtimeCallback> callbackRef = new AtomicReference<>();
            List<Throwable> errors = new ArrayList<>();

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class, (mock, context) ->
                                 callbackRef.set((OmniRealtimeCallback) context.arguments().get(1)))) {
                service.startTranscription("session-1", text -> {
                }, errors::add);

                JsonObject errorEvent = new JsonObject();
                errorEvent.addProperty("type", "error");
                errorEvent.addProperty("message", "remote asr failed");
                callbackRef.get().onEvent(errorEvent);

                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("remote asr failed");
            }
        }

        @Test
        @DisplayName("发送音频异常时应回调 onError 并关闭会话")
        void shouldCallbackErrorAndCloseSessionWhenAppendAudioFails() {
            QwenAsrService service = new QwenAsrService(buildProperties());
            List<Throwable> errors = new ArrayList<>();

            try (MockedConstruction<OmniRealtimeConversation> mockedConstruction =
                         mockConstruction(OmniRealtimeConversation.class, (mock, context) -> {
                             org.mockito.Mockito.doThrow(new IllegalStateException("append failed"))
                                     .when(mock).appendAudio(org.mockito.ArgumentMatchers.anyString());
                         })) {
                service.startTranscription("session-1", text -> {
                }, errors::add);

                assertThatThrownBy(() -> service.sendAudio("session-1", new byte[]{1, 2, 3}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Failed to send audio");

                OmniRealtimeConversation conversation = mockedConstruction.constructed().getFirst();
                verify(conversation).close();
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst()).hasMessageContaining("append failed");
                assertThatThrownBy(() -> service.sendAudio("session-1", new byte[]{1}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ASR session does not exist");
            }
        }
    }

    private static VoiceInterviewProperties buildProperties() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        VoiceInterviewProperties.AsrConfig asr = properties.getQwen().getAsr();
        asr.setUrl("wss://dashscope.aliyuncs.com/api-ws/v1/realtime");
        asr.setModel("qwen3-asr-flash-realtime");
        asr.setApiKey("test-api-key");
        asr.setLanguage("zh");
        asr.setFormat("pcm");
        asr.setSampleRate(16000);
        asr.setEnableTurnDetection(true);
        asr.setTurnDetectionType("server_vad");
        asr.setTurnDetectionThreshold(0.0f);
        asr.setTurnDetectionSilenceDurationMs(1000);
        return properties;
    }

    private static JsonObject buildItemEvent(String type, String fieldName, String text) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        JsonObject item = new JsonObject();
        item.addProperty(fieldName, text);
        event.add("item", item);
        return event;
    }

    private static void verifyNoInteractionsOnAudio(OmniRealtimeConversation conversation) {
        verify(conversation, times(0)).appendAudio(org.mockito.ArgumentMatchers.anyString());
    }
}
