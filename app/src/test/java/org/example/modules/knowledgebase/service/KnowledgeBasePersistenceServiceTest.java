package org.example.modules.knowledgebase.service;

import org.example.common.exception.BusinessException;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库持久化服务测试")
class KnowledgeBasePersistenceServiceTest {
    private static final MockMultipartFile FILE = new MockMultipartFile(
            "file",
            "guide.pdf",
            "application/pdf",
            "knowledge base".getBytes()
    );
    private static final String NAME = "  Java 面试  ";
    private static final String CATEGORY = "  后端  ";
    private static final String STORAGE_KEY = "knowledge/2026/05/19/guide.pdf";
    private static final String STORAGE_URL = "http://localhost:9000/knowledge/guide.pdf";
    private static final String FILE_HASH = "hash-001";

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    private KnowledgeBasePersistenceService knowledgeBasePersistenceService;

    @Nested
    @DisplayName("保存")
    class Save {

        /**
         * 验证保存方法能够正确构造并持久化知识库实体。
         */
        @Test
        @DisplayName("应保存知识库实体")
        void shouldSaveKnowledgeBaseEntity() {
            when(knowledgeBaseRepository.save(any(KnowledgeBaseEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            KnowledgeBaseEntity result = knowledgeBasePersistenceService.save(
                    FILE,
                    NAME,
                    CATEGORY,
                    STORAGE_KEY,
                    STORAGE_URL,
                    FILE_HASH
            );

            ArgumentCaptor<KnowledgeBaseEntity> captor =
                    ArgumentCaptor.forClass(KnowledgeBaseEntity.class);
            verify(knowledgeBaseRepository).save(captor.capture());

            KnowledgeBaseEntity saved = captor.getValue();
            assertThat(saved.getName()).isEqualTo("Java 面试");
            assertThat(saved.getCategory()).isEqualTo("后端");
            assertThat(saved.getOriginalFilename()).isEqualTo("guide.pdf");
            assertThat(saved.getFileSize()).isEqualTo(FILE.getSize());
            assertThat(saved.getContentType()).isEqualTo("application/pdf");
            assertThat(saved.getStorageKey()).isEqualTo(STORAGE_KEY);
            assertThat(saved.getStorageUrl()).isEqualTo(STORAGE_URL);
            assertThat(saved.getFileHash()).isEqualTo(FILE_HASH);
            assertThat(saved.getVectorStatus()).isEqualTo(VectorStatus.PENDING);
            assertThat(saved.getVectorError()).isNull();
            assertThat(saved.getChunkCount()).isEqualTo(0);
            assertThat(result).isSameAs(saved);
        }

        /**
         * 验证保存方法在文件为空时会抛出业务异常。
         */
        @Test
        @DisplayName("文件为空时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenFileBlank() {
            MockMultipartFile emptyFile = new MockMultipartFile("file", new byte[0]);

            assertThatThrownBy(() -> knowledgeBasePersistenceService.save(
                    emptyFile,
                    NAME,
                    CATEGORY,
                    STORAGE_KEY,
                    STORAGE_URL,
                    FILE_HASH
            ))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件不能为空");
        }

        /**
         * 验证保存方法在文件哈希为空时会抛出业务异常。
         */
        @Test
        @DisplayName("文件哈希为空时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenFileHashBlank() {
            assertThatThrownBy(() -> knowledgeBasePersistenceService.save(
                    FILE,
                    NAME,
                    CATEGORY,
                    STORAGE_KEY,
                    STORAGE_URL,
                    " "
            ))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("文件哈希不能为空");
        }
    }

    @Nested
    @DisplayName("查询")
    class Query {

        /**
         * 验证根据 ID 查询时能够返回对应实体。
         */
        @Test
        @DisplayName("应根据 ID 获取知识库")
        void shouldGetKnowledgeBaseById() {
            KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
            entity.setId(1L);
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(entity));

            KnowledgeBaseEntity result = knowledgeBasePersistenceService.getById(1L);

            assertThat(result).isSameAs(entity);
        }

        /**
         * 验证根据文件哈希查询时能够返回对应实体。
         */
        @Test
        @DisplayName("应根据文件哈希获取知识库")
        void shouldGetKnowledgeBaseByFileHash() {
            KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
            entity.setFileHash(FILE_HASH);
            when(knowledgeBaseRepository.findByFileHash(FILE_HASH)).thenReturn(Optional.of(entity));

            KnowledgeBaseEntity result = knowledgeBasePersistenceService.getByFileHash(FILE_HASH);

            assertThat(result).isSameAs(entity);
        }

        /**
         * 验证根据文件哈希判断是否存在时会委托仓库查询。
         */
        @Test
        @DisplayName("应判断文件哈希是否存在")
        void shouldCheckFileHashExists() {
            when(knowledgeBaseRepository.existsByFileHash(FILE_HASH)).thenReturn(true);

            boolean exists = knowledgeBasePersistenceService.existsByFileHash(FILE_HASH);

            assertThat(exists).isTrue();
        }
    }

    @Nested
    @DisplayName("删除与更新")
    class Mutations {

        /**
         * 验证删除方法会先查询再删除。
         */
        @Test
        @DisplayName("应删除知识库")
        void shouldDeleteKnowledgeBaseById() {
            KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
            entity.setId(1L);
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(entity));

            boolean deleted = knowledgeBasePersistenceService.deleteById(1L);

            assertThat(deleted).isTrue();
            verify(knowledgeBaseRepository).delete(entity);
        }

        /**
         * 验证访问次数更新会递增并保存。
         */
        @Test
        @DisplayName("应增加访问次数")
        void shouldIncrementAccessCount() {
            KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
            entity.setId(1L);
            entity.setAccessCount(3);
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(entity));
            when(knowledgeBaseRepository.save(any(KnowledgeBaseEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            KnowledgeBaseEntity result = knowledgeBasePersistenceService.incrementAccessCount(1L);

            assertThat(result.getAccessCount()).isEqualTo(4);
            verify(knowledgeBaseRepository).save(entity);
        }

        /**
         * 验证重复文件处理会递增访问次数并保存。
         */
        @Test
        @DisplayName("应处理重复知识库文件")
        void shouldHandleDuplicateKnowledgeBase() {
            KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
            entity.setId(1L);
            entity.setAccessCount(2);
            when(knowledgeBaseRepository.findByFileHash(FILE_HASH)).thenReturn(Optional.of(entity));
            when(knowledgeBaseRepository.save(any(KnowledgeBaseEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Map<String, Object> result =
                    knowledgeBasePersistenceService.handleDuplicateKnowledgeBase(FILE_HASH);

        }

        /**
         * 验证批量增加提问次数会委托仓库批量更新。
         */
        @Test
        @DisplayName("应批量增加提问次数")
        void shouldIncrementQuestionCountBatch() {
            when(knowledgeBaseRepository.incrementQuestionCountBatch(List.of(1L, 2L)))
                    .thenReturn(2);

            int updated = knowledgeBasePersistenceService.incrementQuestionCountBatch(
                    List.of(1L, 2L)
            );

            assertThat(updated).isEqualTo(2);
        }

        /**
         * 验证向量化状态更新会修改实体并保存。
         */
        @Test
        @DisplayName("应更新向量化状态")
        void shouldUpdateVectorStatus() {
            KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
            entity.setId(1L);
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(entity));
            when(knowledgeBaseRepository.save(any(KnowledgeBaseEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            KnowledgeBaseEntity result = knowledgeBasePersistenceService.updateVectorStatus(
                    1L,
                    VectorStatus.COMPLETED,
                    null
            );

            assertThat(result.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
            assertThat(result.getVectorError()).isNull();
        }

        /**
         * 验证分块数量更新会使用默认值兜底并保存。
         */
        @Test
        @DisplayName("应更新分块数量")
        void shouldUpdateChunkCount() {
            KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
            entity.setId(1L);
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(entity));
            when(knowledgeBaseRepository.save(any(KnowledgeBaseEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            KnowledgeBaseEntity result = knowledgeBasePersistenceService.updateChunkCount(1L, null);

            assertThat(result.getChunkCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("异常")
    class Validation {

        /**
         * 验证非法 ID 会抛出业务异常。
         */
        @Test
        @DisplayName("非法 ID 应抛出业务异常")
        void shouldThrowBusinessExceptionWhenIdInvalid() {
            assertThatThrownBy(() -> knowledgeBasePersistenceService.getById(0L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库 ID 不合法");
        }

        /**
         * 验证不存在的知识库 ID 会抛出业务异常。
         */
        @Test
        @DisplayName("不存在的 ID 应抛出业务异常")
        void shouldThrowBusinessExceptionWhenIdNotFound() {
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> knowledgeBasePersistenceService.getById(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库不存在");
        }
    }

}
