package org.example.ai;

import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.StructuredOutputInvoker;
import org.example.common.ai.StructuredOutputProperties;
import org.example.common.config.LlmProviderProperties;
import org.example.common.config.LlmProviderProperties.ProviderConfig;
import org.example.common.model.ErrorCode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.DefaultToolCallingManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredOutputInvokerTest {

    @Test
    void test01() {
        //构造chatClient
        ProviderConfig providerConfig = new ProviderConfig();
        providerConfig.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        providerConfig.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
        providerConfig.setModel("qwen-plus");

        LlmProviderProperties llmProperties = new LlmProviderProperties();
        llmProperties.setDefaultProvider("dashscope");
        llmProperties.setProviders(Map.of("dashscope", providerConfig));

        LlmProviderProperties.AdvisorConfig advisorConfig = new LlmProviderProperties.AdvisorConfig();
        advisorConfig.setEnabled(false);
        llmProperties.setAdvisors(advisorConfig);

        LlmProviderRegistry registry = new LlmProviderRegistry(
                llmProperties,
                DefaultToolCallingManager.builder().build(),
                null,
                null
        );
        ChatClient chatClient = registry.getDefaultChatClient();
        //构造StructuredOutput的invoker
        StructuredOutputProperties properties = new StructuredOutputProperties();
        properties.setStructuredMaxAttempts(1);
        properties.setStructuredMetricsEnabled(false);
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties, null);

        BeanOutputConverter<TestResult> converter = new BeanOutputConverter<>(TestResult.class);
        TestResult result = invoker.invoke(
                chatClient,
                "Return JSON only. Format: " + converter.getFormat(),
                "Return name as test and score as 100.",
                converter,
                ErrorCode.AI_SERVICE_ERROR,
                "structured output failed: ",
                "structuredOutputTest",
                LoggerFactory.getLogger(StructuredOutputInvokerTest.class)
        );

        assertThat(result.name()).isEqualTo("test");
        assertThat(result.score()).isEqualTo(100);
    }

    record TestResult(String name, int score) {
    }
}
