package org.example.modules.knowledgebase.service;

import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.knowledgebase.repository.VectorRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;
    public KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        // 使用 TokenTextSplitter 默认配置，每个 chunk 约 800 tokens，基于标点边界切分（无重叠）
        this.textSplitter = TokenTextSplitter.builder().build();
    }
    /**
     * 向量化并保存文档
     *
     * @param knowledgeBaseId 知识库ID
     * @param content         文档内容
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content){
        // 先校验基础入参，避免无效数据进入向量化流程
        if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不合法");
        }
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档内容不能为空");
        }
        try {
            // 为原文档和后续分块统一补充知识库标识元数据
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("knowledgeBaseId", knowledgeBaseId);
            metadata.put("kb_id", knowledgeBaseId.toString());
            metadata.put("kb_id_long", knowledgeBaseId);

            // 先切分文档，再为每个有效分块补齐元数据
            Document source = new Document(content, metadata);
            List<Document> chunks = textSplitter.apply(List.of(source)).stream()
                    .filter(document -> document != null && StringUtils.hasText(document.getText()))
                    .map(document -> {
                        Map<String, Object> chunkMetadata = new LinkedHashMap<>(document.getMetadata());
                        chunkMetadata.putAll(metadata);
                        return new Document(document.getText(), chunkMetadata);
                    })
                    .toList();
            // 没有可入库分块时直接结束，避免写入空数据
            if (chunks.isEmpty()) {
                log.warn("知识库内容分块结果为空，跳过向量化: kbId={}", knowledgeBaseId);
                return;
            }
            // 将分块结果批量写入向量库
            vectorStore.add(chunks);
            log.info("知识库向量化完成: kbId={}, chunkCount={}", knowledgeBaseId, chunks.size());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("知识库向量化失败: kbId={}", knowledgeBaseId, e);
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "知识库向量化失败",
                    e
            );
        }
    }
    /**
     * 搜索相似文档
     *
     * @param query               查询内容
     * @param knowledgeBaseIds    知识库ID列表
     * @param topK                返回相似度最高的 topK 个文档
     * @param minScore            最小相似度阈值
     * @return                    相似文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        // 检索前先校验查询参数，避免无意义的向量查询
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "查询内容不能为空");
        }
        if (topK <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "topK 必须大于 0");
        }
        if (minScore < 0 || minScore > 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "最小相似度阈值必须在 0 到 1 之间");
        }
        try {
            // 多知识库检索时适当放大召回数量，给后续本地过滤留出空间
            int candidateTopK = topK;
            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                candidateTopK = Math.min(topK * Math.max(knowledgeBaseIds.size(), 3), 100);
            }
            // 先向向量库发起相似度检索
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(candidateTopK)
                    .similarityThreshold(minScore)
                    .build();
            List<Document> documents = vectorStore.similaritySearch(request);
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            // 将知识库ID列表转为集合，便于后续做结果过滤
            Set<Long> knowledgeBaseIdSet = knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                    ? Set.of()
                    : knowledgeBaseIds.stream()
                    .filter(id -> id != null && id > 0)
                    .collect(Collectors.toSet());

            // 基于知识库范围、分数排序和去重规则裁剪最终返回结果
            List<Document> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            documents.stream()
                    .filter(document -> knowledgeBaseIdSet.isEmpty()
                            || knowledgeBaseIdSet.contains(extractKnowledgeBaseId(document)))
                    .sorted(Comparator.comparingDouble(this::extractScore).reversed())
                    .forEach(document -> {
                        if (results.size() >= topK) {
                            return;
                        }
                        String key = extractKnowledgeBaseId(document) + ":" + document.getText();
                        if (seen.add(key)) {
                            results.add(document);
                        }
                    });
            return results;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("知识库相似度检索失败: query={}, kbIds={}", query, knowledgeBaseIds, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败", e);
        }
    }
    /**
     * 删除指定知识库的所有向量数据
     *
     * @param knowledgeBaseId 知识库ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        // 删除前校验知识库ID，避免误删或无效操作
        if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
            log.warn("知识库ID不合法，跳过删除: kbId={}", knowledgeBaseId);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不合法");
        }
        try {
            // 通过仓储直接删除该知识库关联的全部向量数据
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
//             throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    private Long extractKnowledgeBaseId(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return parseLong(metadata.get("knowledgeBaseId"), metadata.get("kb_id_long"), metadata.get("kb_id"));
    }

    private double extractScore(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Double score = parseDouble(metadata.get("score"), metadata.get("distance"), metadata.get("similarity"));
        return score == null ? 0D : score;
    }

    private Long parseLong(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof Number number) {
                return number.longValue();
            }
            if (candidate instanceof String value && StringUtils.hasText(value)) {
                try {
                    return Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    log.debug("无法解析知识库ID: value={}", value);
                }
            }
        }
        return null;
    }

    private Double parseDouble(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof Number number) {
                return number.doubleValue();
            }
            if (candidate instanceof String value && StringUtils.hasText(value)) {
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    log.debug("无法解析相似度分数: value={}", value);
                }
            }
        }
        return null;
    }
}
