package org.example.modules.knowledgebase.service;

import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.PromptSecurityConstants;
import org.example.common.config.KnowledgeBaseQueryProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.knowledgebase.model.QueryRequest;
import org.example.modules.knowledgebase.model.QueryResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库查询服务。
 * 基于向量检索提供 RAG 问答能力。
 */
@Service
@Slf4j
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE =
            "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final ChatClient chatClient;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final boolean historyEnabled;
    private final int maxHistoryMessages;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    public KnowledgeBaseQueryService(
            ChatClient.Builder chatClientBuilder,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader
    ) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = loadPrompt(resourceLoader, queryProperties.getSystemPromptPath());
        this.userPromptTemplate = loadPrompt(resourceLoader, queryProperties.getUserPromptPath());
        this.rewritePromptTemplate = loadPrompt(resourceLoader, queryProperties.getRewritePromptPath());
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.historyEnabled = queryProperties.getHistory().isEnabled();
        this.maxHistoryMessages = queryProperties.getHistory().getMaxMessages();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    /**
     * 基于单个知识库回答用户问题。
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题。
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        QueryContext queryContext = prepareQuery(knowledgeBaseIds, question, List.of());
        if (queryContext.documents().isEmpty()) {
            return NO_RESULT_RESPONSE;
        }
        return generateAnswer(queryContext, List.of());
    }

    /**
     * 查询知识库并返回完整响应。
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "查询请求不能为空");
        }
        QueryContext queryContext = prepareQuery(
                request.knowledgeBaseIds(),
                request.question(),
                List.of()
        );
        String answer = queryContext.documents().isEmpty()
                ? NO_RESULT_RESPONSE
                : generateAnswer(queryContext, List.of());
        return new QueryResponse(
                answer,
                resolveResponseKnowledgeBaseId(queryContext.knowledgeBaseIds()),
                resolveResponseKnowledgeBaseName(queryContext.knowledgeBaseIds())
        );
    }

    /**
     * 流式查询知识库（SSE，无上下文）。
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）。
     */
    public Flux<String> answerQuestionStream(
            List<Long> knowledgeBaseIds,
            String question,
            List<Message> history
    ) {
        List<Message> safeHistory = normalizeHistory(history);
        QueryContext queryContext = prepareQuery(knowledgeBaseIds, question, safeHistory);
        if (queryContext.documents().isEmpty()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }
        return buildPromptRequest(queryContext, safeHistory).stream().content();
    }

    private PromptTemplate loadPrompt(ResourceLoader resourceLoader, String path) throws IOException {
        return new PromptTemplate(
                resourceLoader.getResource(path).getContentAsString(StandardCharsets.UTF_8)
        );
    }

    private QueryContext prepareQuery(
            List<Long> knowledgeBaseIds,
            String question,
            List<Message> history
    ) {
        List<Long> validIds = normalizeKnowledgeBaseIds(knowledgeBaseIds);
        String normalizedQuestion = normalizeQuestion(question);
        countService.updateQuestionCounts(validIds);
        String effectiveQuestion = rewriteQuestionIfNeeded(normalizedQuestion, history);
        SearchConfig searchConfig = resolveSearchConfig(effectiveQuestion);
        List<Document> documents = vectorService.similaritySearch(
                effectiveQuestion,
                validIds,
                searchConfig.topK(),
                searchConfig.minScore()
        );
        log.info(
                "Knowledge base query prepared: kbIds={}, question={}, rewritten={}, resultCount={}",
                validIds,
                abbreviate(normalizedQuestion),
                abbreviate(effectiveQuestion),
                documents.size()
        );
        return new QueryContext(validIds, effectiveQuestion, documents);
    }

    private List<Long> normalizeKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个知识库");
        }
        List<Long> validIds = knowledgeBaseIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个合法的知识库");
        }
        return validIds;
    }

    private String normalizeQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        return question.strip();
    }

    private String rewriteQuestionIfNeeded(String question, List<Message> history) {
        if (!rewriteEnabled) {
            return question;
        }
        String historyText = buildHistoryText(history);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("question", question);
        params.put("history", historyText.isBlank() ? "" : "\n对话历史：\n" + historyText);
        String rewritePrompt = rewritePromptTemplate.render(params);
        String rewritten = chatClient.prompt().user(rewritePrompt).call().content();
        return StringUtils.hasText(rewritten) ? rewritten.strip() : question;
    }

    private SearchConfig resolveSearchConfig(String question) {
        int length = question.length();
        if (length <= shortQueryLength) {
            return new SearchConfig(topkShort, minScoreShort);
        }
        if (length <= shortQueryLength * 3) {
            return new SearchConfig(topkMedium, minScoreDefault);
        }
        return new SearchConfig(topkLong, minScoreDefault);
    }

    private String generateAnswer(QueryContext queryContext, List<Message> history) {
        return buildPromptRequest(queryContext, history).call().content();
    }

    private ChatClient.ChatClientRequestSpec buildPromptRequest(
            QueryContext queryContext,
            List<Message> history
    ) {
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                .system(renderSystemPrompt());
        if (!history.isEmpty()) {
            requestSpec = requestSpec.messages(history);
        }
        return requestSpec.user(renderUserPrompt(queryContext));
    }

    private String renderSystemPrompt() {
        return systemPromptTemplate.render() + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    private String renderUserPrompt(QueryContext queryContext) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("context", buildContext(queryContext.documents()));
        params.put("question", queryContext.rewrittenQuestion());
        return userPromptTemplate.render(params);
    }

    private String buildContext(List<Document> documents) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            if (document == null || !StringUtils.hasText(document.getText())) {
                continue;
            }
            // 为每个检索片段增加边界和序号，降低模型混淆不同来源内容的概率。
            context.append("### 片段 ").append(i + 1).append('\n')
                    .append(document.getText().strip())
                    .append("\n\n");
        }
        return context.toString().strip();
    }

    private List<Message> normalizeHistory(List<Message> history) {
        if (!historyEnabled || history == null || history.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, history.size() - Math.max(1, maxHistoryMessages));
        return new ArrayList<>(history.subList(start, history.size()));
    }

    private String buildHistoryText(List<Message> history) {
        List<Message> normalizedHistory = normalizeHistory(history);
        if (normalizedHistory.isEmpty()) {
            return "";
        }
        StringBuilder historyText = new StringBuilder();
        for (Message message : normalizedHistory) {
            if (message == null || !StringUtils.hasText(message.getText())) {
                continue;
            }
            historyText.append(message.getMessageType())
                    .append(": ")
                    .append(message.getText().strip())
                    .append('\n');
            if (historyText.length() >= MAX_REWRITE_HISTORY_CHAR) {
                break;
            }
        }
        if (historyText.length() > MAX_REWRITE_HISTORY_CHAR) {
            historyText.setLength(MAX_REWRITE_HISTORY_CHAR);
        }
        return historyText.toString().strip();
    }

    private Long resolveResponseKnowledgeBaseId(List<Long> knowledgeBaseIds) {
        return knowledgeBaseIds.size() == 1 ? knowledgeBaseIds.getFirst() : null;
    }

    private String resolveResponseKnowledgeBaseName(List<Long> knowledgeBaseIds) {
        List<String> names = listService.getKnowledgeBaseNames(knowledgeBaseIds);
        if (names.isEmpty()) {
            return null;
        }
        return names.size() == 1 ? names.getFirst() : String.join(", ", names);
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= STREAM_PROBE_CHARS
                ? normalized
                : normalized.substring(0, STREAM_PROBE_CHARS);
    }

    private record SearchConfig(int topK, double minScore) {
    }

    private record QueryContext(
            List<Long> knowledgeBaseIds,
            String rewrittenQuestion,
            List<Document> documents
    ) {
    }
}
