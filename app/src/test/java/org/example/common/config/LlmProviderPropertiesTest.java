package org.example.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LlmProviderProperties.class)
@DisplayName("LLM provider properties tests")
class LlmProviderPropertiesTest {

    @Autowired
    private LlmProviderProperties llmProviderProperties;

    @Test
    @DisplayName("should bind providers from application yaml")
    void shouldBindProvidersFromApplicationYaml() {
        assertThat(llmProviderProperties.getDefaultProvider()).isEqualTo("dashscope");
        assertThat(llmProviderProperties.getProviders()).isNotNull();
        assertThat(llmProviderProperties.getProviders()).containsKey("dashscope");
        assertThat(llmProviderProperties.getProviders().get("dashscope")).isNotNull();
        assertThat(llmProviderProperties.getProviders().get("dashscope").getBaseUrl())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
        assertThat(llmProviderProperties.getProviders().get("dashscope").getModel())
                .isEqualTo("qwen-plus");
    }
}
