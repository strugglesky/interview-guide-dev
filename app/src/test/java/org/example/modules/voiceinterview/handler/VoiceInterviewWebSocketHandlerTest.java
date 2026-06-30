package org.example.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.example.common.config.VoiceInterviewProperties;
import org.example.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import org.example.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.example.modules.voiceinterview.service.DashscopeLlmService;
import org.example.modules.voiceinterview.service.QwenAsrService;
import org.example.modules.voiceinterview.service.QwenTtsService;
import org.example.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试 WebSocket 处理器测试")
public class VoiceInterviewWebSocketHandlerTest {

    @Mock
    private QwenAsrService sttService;

    @Mock
    private QwenTtsService ttsService;

    @Mock
    private DashscopeLlmService llmService;

    @Mock
    private VoiceInterviewService interviewService;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VoiceInterviewWebSocketHandler handler;

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.destroy();
        }
    }

    @Test
    @DisplayName("建立连接时应启动识别并发送欢迎消息")
    void shouldStartAsrAndSendWelcomeMessage() throws Exception {
        handler = newHandler(buildProperties());
        WebSocketSession session = mockSession("101");
        when(interviewService.getConversationHistory("101"))
                .thenReturn(List.of(buildHistoryMessage("你好", null)));

        handler.afterConnectionEstablished(session);

        verify(session).setTextMessageSizeLimit(256 * 1024);
        verify(session).setBinaryMessageSizeLimit(256 * 1024);
        verify(sttService).startTranscription(eq("101"), any(), any(), any());
        List<String> payloads = capturePayloads(session, 1);
        assertThat(hasControlAction(payloads, "welcome")).isTrue();
    }

    @Test
    @DisplayName("首次连接且无历史时应发送开场问题文本与语音")
    void shouldSendOpeningQuestionWhenHistoryIsEmpty() throws Exception {
        VoiceInterviewProperties properties = buildProperties();
        String openingQuestion = "请介绍你最熟悉的项目。";
        properties.getOpening().getSkillQuestions().put("java-backend", openingQuestion);
        handler = newHandler(properties);
        WebSocketSession session = mockSession("102");
        when(interviewService.getConversationHistory("102")).thenReturn(List.of());
        when(interviewService.getSession(102L)).thenReturn(buildSessionEntity(102L));
        when(ttsService.synthesize(openingQuestion)).thenReturn(new byte[]{1, 2, 3, 4});

        handler.afterConnectionEstablished(session);

        verify(interviewService, timeout(1000)).saveMessage("102", null, openingQuestion);
        verify(ttsService, timeout(1000)).synthesize(openingQuestion);
        List<String> payloads = capturePayloads(session, 3);
        assertThat(hasControlAction(payloads, "welcome")).isTrue();
        assertThat(hasTextMessage(payloads, openingQuestion)).isTrue();
        assertThat(hasAudioMessage(payloads, openingQuestion)).isTrue();
    }

    @Test
    @DisplayName("提交控制消息时应触发 LLM 回复并下发字幕文本和语音")
    void shouldHandleSubmitControlMessage() throws Exception {
        VoiceInterviewProperties properties = buildProperties();
        properties.setLlmStreamingEnabled(false);
        handler = newHandler(properties);
        WebSocketSession session = mockSession("103");
        VoiceInterviewSessionEntity sessionEntity = buildSessionEntity(103L);
        String userText = "我负责库存系统。";
        String aiReply = "那你具体怎么保证幂等？";
        when(interviewService.getConversationHistory("103"))
                .thenReturn(List.of(buildHistoryMessage(null, "上一轮问题")));
        when(interviewService.getSession(103L)).thenReturn(sessionEntity);
        when(llmService.chat(eq(userText), eq(sessionEntity), anyList())).thenReturn(aiReply);
        when(ttsService.synthesize(aiReply)).thenReturn(new byte[]{5, 6, 7, 8});
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(submitMessage(userText)));

        verify(llmService, timeout(1000)).chat(eq(userText), eq(sessionEntity), anyList());
        verify(interviewService, timeout(1000)).saveMessage("103", userText, aiReply);
        List<String> payloads = capturePayloads(session, 4);
        assertThat(hasSubtitle(payloads, userText, true)).isTrue();
        assertThat(hasTextMessage(payloads, aiReply)).isTrue();
        assertThat(hasAudioMessage(payloads, aiReply)).isTrue();
    }

    @Test
    @DisplayName("接近超时时应发送暂停预警但不暂停会话")
    void shouldSendPauseWarningBeforeTimeout() throws Exception {
        handler = newHandler(buildProperties());
        WebSocketSession session = mockSession("104");
        when(interviewService.getConversationHistory("104"))
                .thenReturn(List.of(buildHistoryMessage("历史消息", null)));
        handler.afterConnectionEstablished(session);
        setLastActivity("104", System.currentTimeMillis() - 280_000);

        handler.checkPauseTimeout();

        verify(interviewService, never()).pauseSession("104", "timeout");
        List<String> payloads = capturePayloads(session, 2);
        assertThat(hasControlAction(payloads, "pause_timeout_warning")).isTrue();
    }

    @Test
    @DisplayName("超时后应暂停会话关闭连接并清理状态")
    void shouldPauseSessionWhenTimeoutReached() throws Exception {
        handler = newHandler(buildProperties());
        WebSocketSession session = mockSession("105");
        when(interviewService.getConversationHistory("105"))
                .thenReturn(List.of(buildHistoryMessage("历史消息", null)));
        handler.afterConnectionEstablished(session);
        setLastActivity("105", System.currentTimeMillis() - 310_000);

        handler.checkPauseTimeout();

        verify(interviewService).pauseSession("105", "timeout");
        verify(sttService).stopTranscription("105");
        verify(session).close(CloseStatus.GOING_AWAY);
        assertThat(currentSessions().containsKey("105")).isFalse();
        assertThat(hasControlAction(capturePayloads(session, 2), "pause_timeout")).isTrue();
    }

    @Test
    @DisplayName("连接关闭时应停止识别并触发会话兜底结束")
    void shouldCleanupWhenConnectionClosed() throws Exception {
        handler = newHandler(buildProperties());
        WebSocketSession session = mockSession("106");
        when(interviewService.getConversationHistory("106"))
                .thenReturn(List.of(buildHistoryMessage("历史消息", null)));
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(sttService).stopTranscription("106");
        verify(interviewService).endSessionIfInProgress("106");
        assertThat(currentSessions().containsKey("106")).isFalse();
        assertThat(currentSessionStates().containsKey("106")).isFalse();
    }

    private VoiceInterviewWebSocketHandler newHandler(VoiceInterviewProperties properties) {
        return new VoiceInterviewWebSocketHandler(
                objectMapper,
                sttService,
                ttsService,
                llmService,
                interviewService,
                properties,
                meterRegistryProvider
        );
    }

    private VoiceInterviewProperties buildProperties() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        properties.setLlmProvider("dashscope");
        properties.setLlmStreamingEnabled(false);
        properties.setChunkedAudioEnabled(false);
        properties.setAiQuestionMaxChars(120);
        return properties;
    }

    private WebSocketSession mockSession(String sessionId) {
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/voice-interview/" + sessionId));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private String submitMessage(String text) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "type", "control",
                "action", "submit",
                "data", Map.of("text", text)
        ));
    }

    private VoiceInterviewSessionEntity buildSessionEntity(Long sessionId) {
        return VoiceInterviewSessionEntity.builder()
                .id(sessionId)
                .userId("default")
                .roleType("java-backend")
                .skillId("java-backend")
                .difficulty("mid")
                .llmProvider("dashscope")
                .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(true)
                .hrEnabled(false)
                .startTime(LocalDateTime.of(2026, 6, 30, 10, 0))
                .build();
    }

    private VoiceInterviewMessageEntity buildHistoryMessage(String userText, String aiText) {
        return VoiceInterviewMessageEntity.builder()
                .sessionId(1L)
                .userRecognizedText(userText)
                .aiGeneratedText(aiText)
                .build();
    }

    private List<String> capturePayloads(WebSocketSession session, int minCount) throws IOException {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(1000).atLeast(minCount)).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(TextMessage::getPayload)
                .toList();
    }

    private boolean hasControlAction(List<String> payloads, String action) throws Exception {
        for (String payload : payloads) {
            JsonNode node = objectMapper.readTree(payload);
            if (isType(node, "control") && action.equals(textValue(node, "action"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTextMessage(List<String> payloads, String content) throws Exception {
        for (String payload : payloads) {
            JsonNode node = objectMapper.readTree(payload);
            if (isType(node, "text") && content.equals(textValue(node, "content"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAudioMessage(List<String> payloads, String text) throws Exception {
        for (String payload : payloads) {
            JsonNode node = objectMapper.readTree(payload);
            if (isType(node, "audio") && text.equals(textValue(node, "text"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSubtitle(List<String> payloads, String text, boolean isFinal) throws Exception {
        for (String payload : payloads) {
            JsonNode node = objectMapper.readTree(payload);
            if (!isType(node, "subtitle")) {
                continue;
            }
            if (text.equals(textValue(node, "text")) && node.path("final").asBoolean() == isFinal) {
                return true;
            }
            if (text.equals(textValue(node, "text")) && node.path("isFinal").asBoolean() == isFinal) {
                return true;
            }
        }
        return false;
    }

    private boolean isType(JsonNode node, String type) {
        return type.equals(textValue(node, "type"));
    }

    private String textValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private void setLastActivity(String sessionId, long timestamp) throws Exception {
        lastActivityTimes().put(sessionId, timestamp);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> lastActivityTimes() throws Exception {
        return (Map<String, Long>) getFieldValue("lastActivityTime");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> currentSessions() throws Exception {
        return (Map<String, Object>) getFieldValue("sessions");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> currentSessionStates() throws Exception {
        return (Map<String, Object>) getFieldValue("sessionStates");
    }

    private Object getFieldValue(String fieldName) throws Exception {
        Field field = VoiceInterviewWebSocketHandler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(handler);
    }
}
