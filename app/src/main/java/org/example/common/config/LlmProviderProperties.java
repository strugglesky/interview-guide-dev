package org.example.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 服务提供者配置
 */
@Component
@Data
public class LlmProviderProperties {
    private String defaultProvider = "dashscope";
    private Map<String, ProviderConfig> providers;
    private AdvisorConfig advisors = new AdvisorConfig();

    /**
     * 静态内置配置类 包含baseUrl、apiKey、model
     */
    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
    }
    /**
     * 静态内置Advisor配置类
     */
    @Data
    public static class AdvisorConfig {
        private boolean enabled = true;

        // ToolCallAdvisor
        private boolean toolCallEnabled = true;
        private boolean toolCallConversationHistoryEnabled = false;
        private boolean streamToolCallResponses = false;

        // MessageChatMemoryAdvisor（默认关闭，避免会话串扰）
        private boolean messageChatMemoryEnabled = false;
        private int messageChatMemoryMaxMessages = 120;

        // SimpleLoggerAdvisor（默认关闭）
        private boolean simpleLoggerEnabled = false;

        // SafeGuardAdvisor
        private boolean safeguardEnabled = true;
        private List<String> safeguardWords = List.of(
                "I'll now act as",
                "Sure, I'll ignore",
                "我已经忽略",
                "新的角色是",
                "忽略之前的指令",
                "forget all previous instructions"
        );

        // PromptSanitizer
        private boolean promptSanitizerEnabled = true;
    }
}
