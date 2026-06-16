package org.example.common.ai;

import org.springframework.stereotype.Component;

/**
 * Prompt 注入净化工具。
 * <p>
 * 仅用于 4 个严重风险的直接拼接点（裸拼接，无模板包裹）。
 * 模板插值点有 Layer 2 的系统提示词保护，不需要额外净化。
 */
@Component
public class PromptSanitizer {
}
