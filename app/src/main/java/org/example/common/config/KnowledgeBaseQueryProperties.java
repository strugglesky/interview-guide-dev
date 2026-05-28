package org.example.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库问答相关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    /**
     * 查询重写配置。
     */
    private Rewrite rewrite = new Rewrite();

    /**
     * 检索参数配置。
     */
    private Search search = new Search();

    /**
     * 对话历史配置。
     */
    private History history = new History();

    /**
     * 系统提示词模板路径。
     */
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";

    /**
     * 用户提示词模板路径。
     */
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";

    /**
     * 查询重写提示词模板路径。
     */
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    /**
     * 查询重写配置。
     */
    @Data
    public static class Rewrite {

        /**
         * 是否启用查询重写。
         */
        private boolean enabled = true;
    }

    /**
     * 检索参数配置。
     */
    @Data
    public static class Search {

        /**
         * 短查询长度阈值。
         */
        private int shortQueryLength = 4;

        /**
         * 短查询召回数量。
         */
        private int topkShort = 20;

        /**
         * 中等长度查询召回数量。
         */
        private int topkMedium = 12;

        /**
         * 长查询召回数量。
         */
        private int topkLong = 8;

        /**
         * 短查询最低相似度阈值。
         */
        private double minScoreShort = 0.25;

        /**
         * 默认最低相似度阈值。
         */
        private double minScoreDefault = 0.28;
    }

    /**
     * 对话历史配置。
     */
    @Data
    public static class History {

        /**
         * 是否启用历史消息参与问答。
         */
        private boolean enabled = true;

        /**
         * 参与问答的最大历史消息数。
         */
        private int maxMessages = 10;
    }
}
