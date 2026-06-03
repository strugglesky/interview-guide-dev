package org.example.modules.knowledgebase.controller;

import org.example.common.constant.CommonConstants;
import org.example.common.exception.BusinessException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库控制器测试")
class KnowledgeBaseControllerTest {

    @Mock
    private KnowledgeBaseUploadService uploadService;

    @Mock
    private KnowledgeBaseQueryService queryService;

    @Mock
    private KnowledgeBaseListService listService;

    @Mock
    private KnowledgeBaseDeleteService deleteService;

    @InjectMocks
    private KnowledgeBaseController knowledgeBaseController;

    @Nested
    @DisplayName("列表与详情")
    class ListAndDetailTests {

        /** 验证列表接口会将 vectorStatus 参数转换为枚举后委托给 service。 */
        @Test
        @DisplayName("获取知识库列表时应按条件委托列表服务")
        void shouldGetKnowledgeBaseListWithParsedVectorStatus() {
            List<KnowledgeBaseListItemDTO> expected = List.of(buildListItem(1L, "Java Guide"));
            when(listService.list(VectorStatus.COMPLETED, "size")).thenReturn(expected);

            // 使用小写且带空白的状态值，验证控制器层的枚举转换逻辑。
            Result<List<KnowledgeBaseListItemDTO>> result =
                    knowledgeBaseController.getAllKnowledgeBases("size", " completed ");

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(listService).list(VectorStatus.COMPLETED, "size");
        }

        /** 验证非法 vectorStatus 会在控制器层转换失败并抛出业务异常。 */
        @Test
        @DisplayName("获取知识库列表时非法状态值应抛出业务异常")
        void shouldThrowWhenVectorStatusInvalid() {
            // 直接传入非法枚举值，验证不会继续调用 service。
            assertThatThrownBy(() -> knowledgeBaseController.getAllKnowledgeBases(null, "unknown"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("vectorStatus 参数不合法");
        }

        /** 验证详情接口会直接返回列表服务提供的 DTO。 */
        @Test
        @DisplayName("获取知识库详情时应返回列表服务结果")
        void shouldGetKnowledgeBaseDetail() {
            KnowledgeBaseListItemDTO expected = buildListItem(2L, "Spring Guide");
            when(listService.getKnowledgeBase(2L)).thenReturn(expected);

            // 直接调用控制器方法，验证返回体包装是否正确。
            Result<KnowledgeBaseListItemDTO> result = knowledgeBaseController.getKnowledgeBase(2L);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(listService).getKnowledgeBase(2L);
        }

        /** 验证删除接口会委托删除服务并返回统一成功响应。 */
        @Test
        @DisplayName("删除知识库时应调用删除服务")
        void shouldDeleteKnowledgeBase() {
            // 删除接口本身不返回数据，只校验 service 调用和成功状态。
            Result<Void> result = knowledgeBaseController.deleteKnowledgeBase(3L);

            assertSuccess(result);
            assertThat(result.getData()).isNull();
            verify(deleteService).deleteKnowledgeBase(3L);
        }
    }

    @Nested
    @DisplayName("问答接口")
    class QueryTests {

        /** 验证同步问答接口会将请求对象完整委托给查询服务。 */
        @Test
        @DisplayName("同步问答时应返回查询服务结果")
        void shouldQueryKnowledgeBase() {
            QueryRequest request = new QueryRequest(List.of(1L, 2L), "什么是 Spring Boot");
            QueryResponse expected = new QueryResponse("这是回答", null, "Java, Spring");
            when(queryService.queryKnowledgeBase(request)).thenReturn(expected);

            // 同步接口返回 Result 包装后的 QueryResponse。
            Result<QueryResponse> result = knowledgeBaseController.queryKnowledgeBase(request);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(queryService).queryKnowledgeBase(request);
        }

        /** 验证流式问答接口会将知识库 ID 和问题拆开后传递给 service。 */
        @Test
        @DisplayName("流式问答时应透传知识库ID和问题")
        void shouldStreamKnowledgeBaseAnswer() {
            QueryRequest request = new QueryRequest(List.of(5L, 6L), "请解释 IOC");
            when(queryService.answerQuestionStream(List.of(5L, 6L), "请解释 IOC"))
                    .thenReturn(Flux.just("片段1", "片段2"));

            // 收集 Flux 内容，验证控制器没有改变流式结果。
            List<String> result = knowledgeBaseController.queryKnowledgeBaseStream(request)
                    .collectList()
                    .block();

            assertThat(result).containsExactly("片段1", "片段2");
            verify(queryService).answerQuestionStream(List.of(5L, 6L), "请解释 IOC");
        }
    }

    @Nested
    @DisplayName("分类接口")
    class CategoryTests {

        /** 验证分类列表接口会直接返回 service 提供的分类集合。 */
        @Test
        @DisplayName("获取全部分类时应返回分类列表")
        void shouldGetAllCategories() {
            when(listService.getAllCategories()).thenReturn(List.of("Java", "Spring"));

            // 分类接口不做额外转换，应直接返回 service 结果。
            Result<List<String>> result = knowledgeBaseController.getAllCategories();

            assertSuccess(result);
            assertThat(result.getData()).containsExactly("Java", "Spring");
            verify(listService).getAllCategories();
        }

        /** 验证按分类查询时控制器会将路径参数直接透传给 service。 */
        @Test
        @DisplayName("按分类获取知识库时应委托列表服务")
        void shouldGetKnowledgeBasesByCategory() {
            List<KnowledgeBaseListItemDTO> expected = List.of(buildListItem(7L, "Backend"));
            when(listService.listKnowledgeBasesByCategory("Java")).thenReturn(expected);

            // 路径参数应原样传递给 service。
            Result<List<KnowledgeBaseListItemDTO>> result =
                    knowledgeBaseController.getKnowledgeBasesByCategory("Java");

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(listService).listKnowledgeBasesByCategory("Java");
        }

        /** 验证未分类接口会用 null 作为分类参数委托给 service。 */
        @Test
        @DisplayName("获取未分类知识库时应使用空分类调用服务")
        void shouldGetUncategorizedKnowledgeBases() {
            List<KnowledgeBaseListItemDTO> expected = List.of(buildListItem(8L, "Uncategorized"));
            when(listService.listKnowledgeBasesByCategory(null)).thenReturn(expected);

            // 未分类接口的关键行为是向 service 传入 null。
            Result<List<KnowledgeBaseListItemDTO>> result =
                    knowledgeBaseController.getUncategorizedKnowledgeBases();

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(listService).listKnowledgeBasesByCategory(isNull());
        }

        /** 验证更新分类接口会从请求体中读取 category 字段后委托 service。 */
        @Test
        @DisplayName("更新分类时应提取请求体中的 category 字段")
        void shouldUpdateCategory() {
            Map<String, String> body = Map.of("category", "Java面试");

            // 控制器只负责从 Map 中提取字段，不处理分类归一化逻辑。
            Result<Void> result = knowledgeBaseController.updateCategory(9L, body);

            assertSuccess(result);
            verify(listService).updateCategory(9L, "Java面试");
        }
    }

    @Nested
    @DisplayName("上传下载与搜索")
    class FileAndSearchTests {

        /** 验证上传接口会将 multipart 文件和附加参数完整委托给上传服务。 */
        @Test
        @DisplayName("上传知识库时应调用上传服务并返回上传结果")
        void shouldUploadKnowledgeBase() {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "guide.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    "pdf-content".getBytes()
            );
            Map<String, Object> expected = Map.of("duplicate", false, "knowledgeBaseId", 10L);
            when(uploadService.upload(file, "后端知识库", "Java")).thenReturn(expected);

            // 上传场景的关键是 multipart 文件对象和可选参数都要传递到 service。
            Result<Map<String, Object>> result = knowledgeBaseController.uploadKnowledgeBases(
                    file,
                    "后端知识库",
                    "Java"
            );

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(uploadService).upload(file, "后端知识库", "Java");
        }

        /** 验证下载接口会组合 DTO 元信息和文件字节生成下载响应。 */
        @Test
        @DisplayName("下载知识库时应返回带附件头的响应体")
        void shouldDownloadKnowledgeBase() {
            KnowledgeBaseListItemDTO item = buildListItem(11L, "Guide");
            byte[] content = new byte[]{1, 2, 3};
            when(listService.getKnowledgeBase(11L)).thenReturn(item);
            when(listService.downloadFile(11L)).thenReturn(content);

            // 下载接口需要同时验证文件内容、Content-Type 和附件响应头。
            ResponseEntity<byte[]> response = knowledgeBaseController.downloadKnowledgeBase(11L);

            assertThat(response.getBody()).containsExactly(1, 2, 3);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .contains("attachment")
                    .contains("Guide.pdf");
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
            verify(listService).getKnowledgeBase(11L);
            verify(listService).downloadFile(11L);
        }

        /** 验证搜索接口会直接把关键字委托给列表服务。 */
        @Test
        @DisplayName("搜索知识库时应返回搜索结果")
        void shouldSearchKnowledgeBases() {
            List<KnowledgeBaseListItemDTO> expected = List.of(buildListItem(12L, "Spring Boot"));
            when(listService.searchKnowledgeBases("Spring")).thenReturn(expected);

            // 搜索接口自身不做额外预处理，应直接透传关键字。
            Result<List<KnowledgeBaseListItemDTO>> result =
                    knowledgeBaseController.searchKnowledgeBases("Spring");

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(listService).searchKnowledgeBases("Spring");
        }
    }

    @Nested
    @DisplayName("统计与向量化")
    class StatisticsAndVectorizationTests {

        /** 验证统计接口会返回列表服务聚合后的统计数据。 */
        @Test
        @DisplayName("获取统计信息时应返回统计 DTO")
        void shouldGetStatistics() {
            KnowledgeBaseStatsDTO expected = new KnowledgeBaseStatsDTO(5, 9, 21, 3, 1);
            when(listService.getStatistics()).thenReturn(expected);

            // 统计接口只负责包装返回值，不应改写 DTO 内容。
            Result<KnowledgeBaseStatsDTO> result = knowledgeBaseController.getStatistics();

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(listService).getStatistics();
        }

        /** 验证重新向量化接口会委托上传服务发起重试。 */
        @Test
        @DisplayName("重新向量化时应调用上传服务重试方法")
        void shouldRetryVectorization() {
            // 重试接口没有响应体，只校验 service 是否被正确调用。
            Result<Void> result = knowledgeBaseController.revectorize(13L);

            assertSuccess(result);
            verify(uploadService).retryVectorization(13L);
        }
    }

    private void assertSuccess(Result<?> result) {
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(CommonConstants.StatusCode.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
    }

    private KnowledgeBaseListItemDTO buildListItem(Long id, String name) {
        return new KnowledgeBaseListItemDTO(
                id,
                name,
                "Java",
                name + ".pdf",
                1024L,
                MediaType.APPLICATION_PDF_VALUE,
                LocalDateTime.of(2026, 6, 1, 10, 0),
                LocalDateTime.of(2026, 6, 1, 11, 0),
                3,
                5,
                VectorStatus.COMPLETED,
                null,
                8
        );
    }
}
