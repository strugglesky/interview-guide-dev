package org.example.modules.knowledgebase.controller;

import org.example.common.constant.CommonConstants;
import org.example.common.result.Result;
import org.example.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import org.example.modules.knowledgebase.model.RagChatDTO;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.service.RagChatSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RAG聊天控制器测试")
class RagChatControllerTest {

    @Mock
    private RagChatSessionService sessionService;

    @InjectMocks
    private RagChatController ragChatController;

    @Nested
    @DisplayName("会话管理")
    class SessionManagementTests {

        @Test
        @DisplayName("创建会话时应返回会话DTO")
        void shouldCreateSession() {
            RagChatDTO.CreateSessionRequest request =
                    new RagChatDTO.CreateSessionRequest(List.of(1L, 2L), "后端面试");
            RagChatDTO.SessionDTO expected = new RagChatDTO.SessionDTO(
                    10L,
                    "后端面试",
                    List.of(1L, 2L),
                    LocalDateTime.of(2026, 6, 3, 10, 0)
            );
            when(sessionService.createSession(request)).thenReturn(expected);

            // 控制器层只负责委托 service 并包装 Result。
            Result<RagChatDTO.SessionDTO> result = ragChatController.createSession(request);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(sessionService).createSession(request);
        }

        @Test
        @DisplayName("获取会话列表时应返回列表服务结果")
        void shouldListSessions() {
            List<RagChatDTO.SessionListItemDTO> expected = List.of(
                    new RagChatDTO.SessionListItemDTO(
                            11L,
                            "Java会话",
                            2,
                            List.of("Java基础", "Spring实战"),
                            LocalDateTime.of(2026, 6, 3, 11, 0),
                            true
                    )
            );
            when(sessionService.getSessionList()).thenReturn(expected);

            Result<List<RagChatDTO.SessionListItemDTO>> result = ragChatController.listSession();

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(sessionService).getSessionList();
        }

        @Test
        @DisplayName("获取会话详情时应返回详情DTO")
        void shouldGetSessionDetail() {
            RagChatDTO.SessionDetailDTO expected = new RagChatDTO.SessionDetailDTO(
                    12L,
                    "详情会话",
                    List.of(buildKnowledgeBase(1L, "JVM手册")),
                    List.of(new RagChatDTO.MessageDTO(
                            100L,
                            "user",
                            "什么是JVM",
                            LocalDateTime.of(2026, 6, 3, 12, 0)
                    )),
                    LocalDateTime.of(2026, 6, 3, 9, 0),
                    LocalDateTime.of(2026, 6, 3, 12, 30)
            );
            when(sessionService.getSessionDetail(12L)).thenReturn(expected);

            Result<RagChatDTO.SessionDetailDTO> result = ragChatController.getSessionDetail(12L);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(sessionService).getSessionDetail(12L);
        }

        @Test
        @DisplayName("更新会话标题时应提取标题并委托服务")
        void shouldUpdateSessionTitle() {
            RagChatDTO.UpdateTitleRequest request = new RagChatDTO.UpdateTitleRequest("新的标题");

            Result<Void> result = ragChatController.updateSessionTitle(13L, request);

            assertSuccess(result);
            verify(sessionService).updateSessionTitle(13L, "新的标题");
        }

        @Test
        @DisplayName("切换置顶时应调用置顶服务")
        void shouldTogglePin() {
            Result<Void> result = ragChatController.togglePin(14L);

            assertSuccess(result);
            verify(sessionService).toggleSessionPinned(14L);
        }

        @Test
        @DisplayName("更新会话知识库时应提取知识库ID列表")
        void shouldUpdateSessionKnowledgeBases() {
            RagChatDTO.UpdateKnowledgeBasesRequest request =
                    new RagChatDTO.UpdateKnowledgeBasesRequest(List.of(3L, 4L));

            Result<Void> result = ragChatController.updateSessionKnowledgeBases(15L, request);

            assertSuccess(result);
            verify(sessionService).updateSessionKnowledgeBases(15L, List.of(3L, 4L));
        }

        @Test
        @DisplayName("删除会话时应调用删除服务")
        void shouldDeleteSession() {
            Result<Void> result = ragChatController.deleteSession(16L);

            assertSuccess(result);
            verify(sessionService).deleteSession(16L);
        }
    }

    @Nested
    @DisplayName("流式消息")
    class StreamMessageTests {

        @Test
        @DisplayName("发送流式消息时应先准备消息再返回SSE事件并在完成后回写结果")
        void shouldSendStreamMessageAndCompleteAssistantMessage() {
            RagChatDTO.SendMessageRequest request = new RagChatDTO.SendMessageRequest("解释一下 IOC");
            when(sessionService.prepareStreamMessage(20L, "解释一下 IOC")).thenReturn(888L);
            when(sessionService.getStreamAnswer(20L, "解释一下 IOC"))
                    .thenReturn(Flux.just("第一段", "第二段"));

            // 订阅流后触发 doOnComplete，从而校验完整回答回写逻辑。
            List<ServerSentEvent<String>> events = ragChatController.sendMessageStream(20L, request)
                    .collectList()
                    .block();

            assertThat(events).hasSize(2);
            assertThat(events.getFirst().event()).isEqualTo("message");
            assertThat(events.getFirst().data()).isEqualTo("第一段");
            assertThat(events.get(1).data()).isEqualTo("第二段");

            verify(sessionService).prepareStreamMessage(20L, "解释一下 IOC");
            verify(sessionService).getStreamAnswer(20L, "解释一下 IOC");
            verify(sessionService).completeStreamMessage(888L, "第一段第二段");
        }

        @Test
        @DisplayName("发送流式消息时应转义换行和回车字符")
        void shouldEscapeLineBreaksInSsePayload() {
            RagChatDTO.SendMessageRequest request = new RagChatDTO.SendMessageRequest("格式化输出");
            when(sessionService.prepareStreamMessage(21L, "格式化输出")).thenReturn(889L);
            when(sessionService.getStreamAnswer(21L, "格式化输出"))
                    .thenReturn(Flux.just("第一行\n第二行\r第三行"));

            // 这里重点验证 SSE data 中的换行会被显式转义，避免事件格式被破坏。
            ServerSentEvent<String> event = ragChatController.sendMessageStream(21L, request)
                    .blockFirst();

            assertThat(event).isNotNull();
            assertThat(event.data()).isEqualTo("第一行\\n第二行\\r第三行");
        }
    }

    private void assertSuccess(Result<?> result) {
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(CommonConstants.StatusCode.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
    }

    private KnowledgeBaseListItemDTO buildKnowledgeBase(Long id, String name) {
        return new KnowledgeBaseListItemDTO(
                id,
                name,
                "后端",
                name + ".pdf",
                1024L,
                "application/pdf",
                LocalDateTime.of(2026, 6, 3, 8, 0),
                LocalDateTime.of(2026, 6, 3, 8, 30),
                2,
                3,
                VectorStatus.COMPLETED,
                null,
                5
        );
    }
}
