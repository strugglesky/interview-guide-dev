package org.example.ai;

import org.example.common.ai.LlmProviderRegistry;
import org.example.common.config.LlmProviderProperties;
import org.example.common.config.LlmProviderProperties.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderRegistryTest {

    @Test
    @DisplayName("get LLM provider with fallback and cache")
    void testGetLlmProvider() {
        ProviderConfig providerConfig = new ProviderConfig();
        providerConfig.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        providerConfig.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        providerConfig.setModel("qwen-plus");

        LlmProviderProperties properties = new LlmProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.setProviders(Map.of("dashscope", providerConfig));

        LlmProviderRegistry registry = new LlmProviderRegistry(
                properties,
                DefaultToolCallingManager.builder().build(),
                null,
                null
        );

        ChatClient chatClient = registry.getDefaultChatClient();
        String content = chatClient.prompt()
                .user("你的大模型是什么？")
                .call()
                .content();

        assertThat(content).isNotBlank();
    }
}
