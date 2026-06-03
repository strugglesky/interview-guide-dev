package org.example.modules.knowledgebase.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.result.Result;
import org.example.modules.knowledgebase.model.RagChatDTO;
import org.example.modules.knowledgebase.service.KnowledgeBaseQueryService;
import org.example.modules.knowledgebase.service.RagChatSessionService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 聊天控制器
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
public class RagChatController {
    private final RagChatSessionService sessionService;
    /**
     * 创建新会话
     */
    @PostMapping("/api/rag-chat/sessions")
    public Result<RagChatDTO.SessionDTO> createSession(@Valid @RequestBody RagChatDTO.CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<RagChatDTO.SessionListItemDTO>> listSession() {
        return Result.success(sessionService.getSessionList());
    }

    /**
     * 获取会话详情（包含消息历史）
     * GET /api/rag-chat/sessions/{sessionId}
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<RagChatDTO.SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(@PathVariable Long sessionId, @Valid @RequestBody RagChatDTO.UpdateTitleRequest request) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success();
    }

    /**
     * 切换会话置顶状态
     * PUT /api/rag-chat/sessions/{sessionId}/pin
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.toggleSessionPinned(sessionId);
        return Result.success();
    }

    /**
     * 更新会话知识库
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(@PathVariable Long sessionId, @Valid @RequestBody RagChatDTO.UpdateKnowledgeBasesRequest request) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return Result.success();
    }

    /**
     * 删除会话
     * DELETE /api/rag-chat/sessions/{sessionId}
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * 发送消息（流式SSE）
     * 流式响应设计：
     * 1. 先同步保存用户消息和创建 AI 消息占位
     * 2. 返回流式响应
     * 3. 流式完成后通过回调更新消息
     */
    @PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody RagChatDTO.SendMessageRequest request){
        log.info("收到 RAG 聊天流式请求: sessionId={}, question={}, 线程: {} (虚拟线程: {})",
                sessionId, request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());
        //1.准备消息（保存用户信息，创建Ai消息占位）
        long aiMessageId = sessionService.prepareStreamMessage(sessionId, request.question());
        StringBuilder answerBuilder = new StringBuilder();

        //2.获取流式响应
        return sessionService.getStreamAnswer(sessionId, request.question())
                .doOnNext(chunk -> {
                    // 在流式返回过程中持续累积回答内容，供流结束后回写数据库使用。
                    answerBuilder.append(chunk);
                })
                .map(chunk ->
                        // 将每个文本分片包装为 SSE 事件，前端可按到达顺序实时渲染。
                        ServerSentEvent.<String>builder()
                                .event("message")
                                .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                                .build()
                )
                .doOnComplete(() -> {
                    // 流式输出正常结束后，将完整回答回填到之前创建的 AI 消息占位记录中。
                    sessionService.completeStreamMessage(aiMessageId, answerBuilder.toString());
                    log.info("RAG 聊天流式响应完成: sessionId={}, messageId={}", sessionId, aiMessageId);
                })
                .doOnError(e ->
                        log.error("RAG 聊天流式响应失败: sessionId={}, messageId={}", sessionId, aiMessageId, e)
                );
    }
}
