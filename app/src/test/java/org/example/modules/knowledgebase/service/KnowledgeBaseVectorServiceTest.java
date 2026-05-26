package org.example.modules.knowledgebase.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.example.common.exception.BusinessException;
import org.example.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库向量化服务测试")
class KnowledgeBaseVectorServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private VectorRepository vectorRepository;

    @InjectMocks
    private KnowledgeBaseVectorService knowledgeBaseVectorService;

    @Nested
    @DisplayName("向量化存储")
    class VectorizeAndStore {

        /**
         * 验证向量化时会为分块补齐知识库元数据并写入向量库。
         */
        @Test
        @DisplayName("应完成文档分块并写入向量库")
        void shouldVectorizeAndStoreDocuments() {
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

            knowledgeBaseVectorService.vectorizeAndStore(1L, "Java 面试知识点内容");

            verify(vectorStore).add(captor.capture());
            List<Document> chunks = captor.getValue();
            assertThat(chunks).isNotEmpty();
            assertThat(chunks.getFirst().getText()).isNotBlank();
            assertThat(chunks.getFirst().getMetadata())
                    .containsEntry("knowledgeBaseId", 1L)
                    .containsEntry("kb_id", "1")
                    .containsEntry("kb_id_long", 1L);
        }

        /**
         * 验证知识库ID非法时会直接抛出业务异常。
         */
        @Test
        @DisplayName("知识库ID非法时应抛出业务异常")
        void shouldThrowWhenKnowledgeBaseIdInvalid() {
            assertThatThrownBy(() -> knowledgeBaseVectorService.vectorizeAndStore(0L, "content"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库ID不合法");

            verify(vectorStore, never()).add(any());
        }

        /**
         * 验证文档内容为空时不会进入向量化流程。
         */
        @Test
        @DisplayName("文档内容为空时应抛出业务异常")
        void shouldThrowWhenContentBlank() {
            assertThatThrownBy(() -> knowledgeBaseVectorService.vectorizeAndStore(1L, " "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文档内容不能为空");

            verify(vectorStore, never()).add(any());
        }
    }

    @Nested
    @DisplayName("相似度检索")
    class SimilaritySearch {

        /**
         * 验证检索结果会按知识库过滤、按分数排序并按内容去重。
         */
        @Test
        @DisplayName("应返回过滤排序去重后的文档结果")
        void shouldFilterSortAndDeduplicateDocuments() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                    buildDocument("A", 1L, "0.80"),
                    buildDocument("A", 1L, "0.70"),
                    buildDocument("B", 1L, "0.95"),
                    buildDocument("C", 2L, "0.99")
            ));

            List<Document> result = knowledgeBaseVectorService.similaritySearch(
                    "Java",
                    List.of(1L),
                    2,
                    0.6
            );

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getText()).isEqualTo("B");
            assertThat(result.get(1).getText()).isEqualTo("A");
        }

        /**
         * 验证向量库无返回结果时应返回空列表。
         */
        @Test
        @DisplayName("无匹配结果时应返回空列表")
        void shouldReturnEmptyListWhenNoDocumentsFound() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            List<Document> result = knowledgeBaseVectorService.similaritySearch(
                    "Java",
                    List.of(1L),
                    3,
                    0.5
            );

            assertThat(result).isEmpty();
        }

        /**
         * 验证查询内容为空时会直接抛出业务异常。
         */
        @Test
        @DisplayName("查询内容为空时应抛出业务异常")
        void shouldThrowWhenQueryBlank() {
            assertThatThrownBy(() -> knowledgeBaseVectorService.similaritySearch(" ", List.of(1L), 3, 0.5))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("查询内容不能为空");
        }

        /**
         * 验证最小相似度阈值越界时会直接抛出业务异常。
         */
        @Test
        @DisplayName("最小相似度阈值非法时应抛出业务异常")
        void shouldThrowWhenMinScoreInvalid() {
            assertThatThrownBy(() -> knowledgeBaseVectorService.similaritySearch("Java", List.of(1L), 3, 1.2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("最小相似度阈值必须在 0 到 1 之间");
        }
    }

    @Nested
    @DisplayName("删除向量数据")
    class DeleteByKnowledgeBaseId {

        /**
         * 验证删除时会把合法知识库ID委托给仓储处理。
         */
        @Test
        @DisplayName("应委托仓储删除向量数据")
        void shouldDeleteByKnowledgeBaseId() {
            assertThatCode(() -> knowledgeBaseVectorService.deleteByKnowledgeBaseId(1L))
                    .doesNotThrowAnyException();

            verify(vectorRepository).deleteByKnowledgeBaseId(1L);
        }

        /**
         * 验证知识库ID非法时不会进入仓储删除逻辑。
         */
        @Test
        @DisplayName("知识库ID非法时应抛出业务异常")
        void shouldThrowWhenDeleteKnowledgeBaseIdInvalid() {
            assertThatThrownBy(() -> knowledgeBaseVectorService.deleteByKnowledgeBaseId(0L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库ID不合法");

            verify(vectorRepository, never()).deleteByKnowledgeBaseId(any());
        }

        /**
         * 验证仓储删除异常时当前实现会吞掉异常以便继续后续流程。
         */
        @Test
        @DisplayName("仓储删除异常时应不中断流程")
        void shouldSwallowRepositoryExceptionWhenDeleteFailed() {
            when(vectorRepository.deleteByKnowledgeBaseId(1L)).thenThrow(new RuntimeException("delete failed"));
            Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeBaseVectorService.class);
            Level originalLevel = logger.getLevel();
            boolean originalAdditive = logger.isAdditive();
            try {
                logger.setLevel(Level.OFF);
                logger.setAdditive(false);
                assertThatCode(() -> knowledgeBaseVectorService.deleteByKnowledgeBaseId(1L))
                        .doesNotThrowAnyException();
            } finally {
                logger.setLevel(originalLevel);
                logger.setAdditive(originalAdditive);
            }
        }
    }

    private Document buildDocument(String text, Long knowledgeBaseId, String score) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("knowledgeBaseId", knowledgeBaseId);
        metadata.put("kb_id", knowledgeBaseId.toString());
        metadata.put("kb_id_long", knowledgeBaseId);
        metadata.put("score", score);
        return new Document(text, metadata);
    }
}
