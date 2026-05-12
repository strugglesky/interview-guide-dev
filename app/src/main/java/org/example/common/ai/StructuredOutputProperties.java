package org.example.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 结构化输出调用配置。
 * 用于控制结构化解析的重试策略、错误提示拼接方式以及指标上报开关。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    /**
     * 结构化输出最大重试次数。
     * 当模型返回结果无法被转换为目标对象时，会在此次数内继续尝试。
     */
    private int structuredMaxAttempts = 3;

    /**
     * 重试时是否把上一次失败原因拼接到提示词中。
     * 开启后有助于模型理解解析失败原因，但会增加提示词长度。
     */
    private boolean structuredIncludeLastError = true;

    /**
     * 重试时是否使用修复式提示词。
     * 开启后会明确告诉模型上一次输出解析失败，要求重新返回合法结果。
     */
    private boolean structuredRetryUseRepairPrompt = true;

    /**
     * 重试时是否追加严格 JSON 输出约束。
     * 适合要求模型只返回纯 JSON 的场景。
     */
    private boolean structuredRetryAppendStrictJsonInstruction = true;

    /**
     * 失败原因写入重试提示词时的最大长度。
     * 用于避免异常消息过长，影响提示词质量。
     */
    private int structuredErrorMessageMaxLength = 200;

    /**
     * 是否启用结构化输出指标上报。
     * 开启后会记录调用次数、重试次数和耗时等指标。
     */
    private boolean structuredMetricsEnabled = true;
}
