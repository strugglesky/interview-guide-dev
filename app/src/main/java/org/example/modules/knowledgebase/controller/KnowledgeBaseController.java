package org.example.modules.knowledgebase.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.annotation.RateLimit;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.common.result.Result;
import org.example.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import org.example.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import org.example.modules.knowledgebase.model.QueryRequest;
import org.example.modules.knowledgebase.model.QueryResponse;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import org.example.modules.knowledgebase.service.KnowledgeBaseListService;
import org.example.modules.knowledgebase.service.KnowledgeBaseQueryService;
import org.example.modules.knowledgebase.service.KnowledgeBaseUploadService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库上传、下载、查询、分类与向量化")
public class KnowledgeBaseController {
    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;

    /**
     * 获取所有知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus) {
        VectorStatus status = null;
        if (StringUtils.hasText(vectorStatus)) {
            try {
                // 控制器层负责将前端字符串参数转换为枚举，避免非法值直接泄漏为框架异常。
                status = VectorStatus.valueOf(vectorStatus.strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "vectorStatus 参数不合法");
            }
        }
        return Result.success(listService.list(status, sortBy));
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return Result.success(listService.getKnowledgeBase(id));
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        deleteService.deleteKnowledgeBase(id);
        return Result.success();
    }

    /**
     * 基于知识库回答问题（支持多知识库）
     */
    @PostMapping("/api/knowledgebase/query")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<QueryResponse> queryKnowledgeBase(
            @Valid @RequestBody QueryRequest request) {
        return Result.success(queryService.queryKnowledgeBase(request));
    }

    /**
     * 基于知识库回答问题（流式SSE，支持多知识库）
     */
    @PostMapping(value = "/api/knowledgebase/query/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<String> queryKnowledgeBaseStream(
            @Valid @RequestBody QueryRequest request) {
        // 流式接口直接透传给查询服务，保持 SSE 输出连续性。
        return queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question());
    }

    // ========== 分类管理 API ==========
    /**
     * 获取所有分类
     */
    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(listService.getAllCategories());
    }

    /**
     * 根据分类获取知识库列表
     */
    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getKnowledgeBasesByCategory(
            @PathVariable String category) {
        return Result.success(listService.listKnowledgeBasesByCategory(category));
    }

    /**
     * 获取未分类的知识库
     */
    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorizedKnowledgeBases() {
        return Result.success(listService.listKnowledgeBasesByCategory(null));
    }

    /**
     * 更新知识库分类
     */
    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        // 分类字段从请求体中读取，缺省时按 service 规则归一化为未分类。
        listService.updateCategory(id, body == null ? null : body.get("category"));
        return Result.success();
    }

    // ========== 上传下载 API ==========

    /**
     * 上传知识库文件
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> uploadKnowledgeBases(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        // 上传接口只做参数接收与委托，文件校验、去重、入库和向量化投递全部交给 service。
        return Result.success(uploadService.upload(file, name, category));
    }

    /**
     * 下载知识库文件
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        KnowledgeBaseListItemDTO knowledgeBase = listService.getKnowledgeBase(id);
        byte[] fileBytes = listService.downloadFile(id);
        MediaType mediaType = MediaTypeFactory.getMediaType(knowledgeBase.originalFilename())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        String contentDisposition = ContentDisposition.attachment()
                .filename(knowledgeBase.originalFilename(), StandardCharsets.UTF_8)
                .build()
                .toString();
        // 补齐下载响应头，确保浏览器按附件方式处理并保留原始文件名。
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(fileBytes);
    }

    // ========== 搜索 API ==========

    /**
     * 搜索知识库
     */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> searchKnowledgeBases(
            @RequestParam("keyword") String keyword) {
        return Result.success(listService.searchKnowledgeBases(keyword));
    }

    // ========== 统计 API ==========

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }

    // ========== 向量化管理 API ==========

    /**
     * 重新向量化知识库（手动重试）
     * 用于向量化失败后的重试
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> revectorize(@PathVariable Long id) {
        uploadService.retryVectorization(id);
        return Result.success();
    }
}
