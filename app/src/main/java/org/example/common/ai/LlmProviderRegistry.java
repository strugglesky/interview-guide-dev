package org.example.common.ai;

import org.example.common.config.LlmProviderProperties;
import org.example.common.config.LlmProviderProperties.AdvisorConfig;
import org.example.common.config.LlmProviderProperties.ProviderConfig;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 服务提供者注册中心
 * 负责按配置创建并缓存不同用途的 ChatClient。
 * 当前统一基于 OpenAI 兼容接口构建，支持默认客户端、纯对话客户端、语音面试客户端。
 */
@Component
@Slf4j
public class LlmProviderRegistry {
    /**
     * 多 Provider 配置。
     */
    private final LlmProviderProperties properties;

    /**
     * ChatClient 缓存。
     * Key 既可能是 providerId，也可能是 providerId:plain / providerId:voice。
     */
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    /**
     * Spring AI 工具调用管理器。
     */
    private final ToolCallingManager toolCallingManager;

    /**
     * 观测与埋点注册表。
     */
    private final ObservationRegistry observationRegistry;

    /**
     * 面试技能工具回调，存在时会注入到支持工具调用的 ChatClient 中。
     */
    private final ToolCallback interviewSkillsToolCallback;

    /**
     * 构造注册中心并注入可选依赖。
     */
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.properties = properties;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
    }

    /**
     * 获取指定 Provider 对应的默认 ChatClient。
     * 若缓存中不存在，则按配置动态创建并放入缓存。
     *
     * @param providerId Provider 标识，例如 dashscope、lmstudio
     * @return 对应的 ChatClient
     * @throws IllegalArgumentException 当 providerId 未配置时抛出
     */
    public ChatClient getChatClient(String providerId) {
        log.info("[LlmProviderRegistry] Requesting client for provider: {}", providerId);
        return clientCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Cache miss. Creating new client for: {}", id);
            return createChatClient(id);
        });
    }

    /**
     * 获取默认 Provider 对应的 ChatClient。
     *
     * @return 默认 ChatClient
     */
    public ChatClient getDefaultChatClient() {
        return getChatClient(properties.getDefaultProvider());
    }

    /**
     * 获取指定 Provider 的 ChatClient。
     * 当 providerId 为空时，自动回退到默认 Provider。
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        return (providerId != null && !providerId.isBlank())
                ? getChatClient(providerId)
                : getDefaultChatClient();
    }

    /**
     * 获取不带 SkillsTool 的纯对话 ChatClient。
     * 适用于简历题生成等不需要工具调用的场景。
     */
    public ChatClient getPlainChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":plain", key -> createPlainChatClient(id));
    }

    /**
     * 获取语音面试专用 ChatClient。
     * 默认启用 SkillsTool 和流式 ToolCallAdvisor，不启用 Memory Advisor，
     * 因为语音面试场景通常由上层显式维护对话历史。
     */
    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":voice", key -> createVoiceChatClient(id));
    }

    /**
     * 创建标准 ChatClient。
     * 包含默认工具回调和按配置启用的 Advisors。
     */
    private ChatClient createChatClient(String providerId) {
        // 先构建底层模型，再在客户端层面挂载工具回调和默认 Advisor。
        OpenAiChatModel chatModel = buildChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }
        // 标准客户端的增强能力统一由配置驱动，便于集中控制。
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        return builder.build();
    }

    /**
     * 创建纯对话 ChatClient。
     * 不挂载 SkillsTool，仅保留必要的安全拦截能力。
     */
    private ChatClient createPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = buildChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        // 纯对话场景不接入工具调用，仅保留安全拦截能力。
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(advisor));
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    /**
     * 创建语音面试专用 ChatClient。
     * 仅启用语音场景需要的工具调用与安全拦截，避免默认 Memory Advisor 干扰会话控制。
     */
    private ChatClient createVoiceChatClient(String providerId) {
        // 语音场景复用统一模型配置，但客户端装配策略单独定制。
        OpenAiChatModel chatModel = buildChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            // 语音面试依赖工具调用的即时反馈，因此固定启用流式工具调用。
            advisors.add(buildToolCallAdvisor(true, true));
        }
        // 语音场景同样需要经过统一的安全边界拦截。
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
        }
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    /**
     * 根据 Provider 配置构建底层 OpenAI 兼容 ChatModel。
     * 这里统一设置超时时间、模型名、温度和重试能力。
     */
    private OpenAiChatModel buildChatModel(String providerId) {
        LlmProviderProperties.ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }

        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                providerId, config.getBaseUrl(), config.getModel());

        // 统一设置 HTTP 超时，避免模型调用长时间阻塞。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10000);
        requestFactory.setReadTimeout(300000);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        // 统一按 OpenAI 兼容协议构建 API 客户端，便于适配不同后端。
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getModel())
                .temperature(0.2)
                .build();

        // 重试、工具调用和观测能力都在模型层统一接入。
        return new OpenAiChatModel(
                openAiApi,
                options,
                toolCallingManager,
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP
        );
    }

    /**
     * 按全局 Advisor 配置组装默认 Advisors。
     * 该列表会用于标准 ChatClient，不同场景的专用客户端可单独裁剪。
     */
    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(
                        config.isToolCallConversationHistoryEnabled(),
                        config.isStreamToolCallResponses()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            // 保护下限，避免配置过小导致上下文窗口几乎不可用。
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                    MessageWindowChatMemory.builder()
                            .maxMessages(maxMessages)
                            .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    /**
     * 创建工具调用 Advisor。
     *
     * @param conversationHistoryEnabled 是否让工具调用链路保留内部会话历史
     * @param streamToolCallResponses 是否流式返回工具调用结果
     * @return ToolCallAdvisor 实例
     */
    private ToolCallAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled,
                                                 boolean streamToolCallResponses) {
        return ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .conversationHistoryEnabled(conversationHistoryEnabled)
                .streamToolCallResponses(streamToolCallResponses)
                .build();
    }

    /**
     * 按配置创建安全拦截 Advisor。
     * 当命中敏感词时，直接返回固定提示，避免模型偏离面试业务边界。
     */
    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
                .sensitiveWords(config.getSafeguardWords())
                .failureResponse("抱歉，我只能协助面试相关的任务。")
                .order(100)
                .build();
        return Optional.of(advisor);
    }

    /**
     * 解析最终使用的 Provider 标识。
     * 当入参为空时回退到默认 Provider。
     */
    private String resolveProviderId(String providerId) {
        return (providerId != null && !providerId.isBlank())
                ? providerId : properties.getDefaultProvider();
    }


}
