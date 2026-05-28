package org.example.modules.knowledgebase.service;

import org.example.common.exception.BusinessException;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.mapper.KnowledgeBaseMapper;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import org.example.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import org.example.modules.knowledgebase.model.RagChatMessageEntity;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.example.modules.knowledgebase.repository.RagChatMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库列表服务测试")
class KnowledgeBaseListServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private RagChatMessageRepository ragChatMessageRepository;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private KnowledgeBaseListService knowledgeBaseListService;

    @Nested
    @DisplayName("列表查询")
    class ListKnowledgeBases {

        @Test
        @DisplayName("未指定状态和排序时应按上传时间倒序查询")
        void shouldListByUploadedTimeWhenNoStatusAndSortProvided() {
            KnowledgeBaseEntity first = buildEntity(1L, "Java", 100L, 2, 5, 1);
            KnowledgeBaseEntity second = buildEntity(2L, "Spring", 200L, 4, 3, 2);
            List<KnowledgeBaseEntity> entities = List.of(first, second);
            List<KnowledgeBaseListItemDTO> dtos = toDtos(entities);
            when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(entities);
            when(knowledgeBaseMapper.toListItemList(entities)).thenReturn(dtos);

            List<KnowledgeBaseListItemDTO> result = knowledgeBaseListService.list(null, null);

            assertThat(result).extracting(KnowledgeBaseListItemDTO::id).containsExactly(1L, 2L);
            verify(knowledgeBaseRepository).findAllByOrderByUploadedAtDesc();
            verify(knowledgeBaseMapper).toListItemList(entities);
        }

        @Test
        @DisplayName("指定状态和访问量排序时应在内存中按访问量倒序")
        void shouldSortFilteredEntitiesByAccessCountDesc() {
            KnowledgeBaseEntity low = buildEntity(1L, "Low", 100L, 1, 2, 1);
            KnowledgeBaseEntity high = buildEntity(2L, "High", 200L, 9, 1, 2);
            KnowledgeBaseEntity middle = buildEntity(3L, "Middle", 150L, 5, 3, 3);
            List<KnowledgeBaseEntity> entities = List.of(low, high, middle);
            when(knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(VectorStatus.COMPLETED))
                    .thenReturn(entities);
            stubMapperListToDtos();

            List<KnowledgeBaseListItemDTO> result = knowledgeBaseListService.list(
                    VectorStatus.COMPLETED,
                    " access "
            );

            assertThat(result).extracting(KnowledgeBaseListItemDTO::id).containsExactly(2L, 3L, 1L);
        }

        @Test
        @DisplayName("按状态列表查询应复用统一列表能力")
        void shouldListByStatusThroughCompatibleMethod() {
            List<KnowledgeBaseEntity> entities = List.of(buildEntity(6L, "KB", 50L, 1, 1, 1));
            List<KnowledgeBaseListItemDTO> dtos = toDtos(entities);
            when(knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(VectorStatus.PROCESSING))
                    .thenReturn(entities);
            when(knowledgeBaseMapper.toListItemList(entities)).thenReturn(dtos);

            List<KnowledgeBaseListItemDTO> result =
                    knowledgeBaseListService.listKnowledgeBasesByStatus(VectorStatus.PROCESSING);

            assertThat(result).hasSize(1);
            verify(knowledgeBaseRepository).findByVectorStatusOrderByUploadedAtDesc(
                    VectorStatus.PROCESSING
            );
        }

        @Test
        @DisplayName("按分类查询时应去除前后空白")
        void shouldListByTrimmedCategory() {
            KnowledgeBaseEntity entity = buildEntity(7L, "Category", 60L, 2, 3, 1);
            entity.setCategory("Java");
            List<KnowledgeBaseEntity> entities = List.of(entity);
            List<KnowledgeBaseListItemDTO> dtos = toDtos(entities);
            when(knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc("Java"))
                    .thenReturn(entities);
            when(knowledgeBaseMapper.toListItemList(entities)).thenReturn(dtos);

            List<KnowledgeBaseListItemDTO> result =
                    knowledgeBaseListService.listKnowledgeBasesByCategory("  Java  ");

            assertThat(result).extracting(KnowledgeBaseListItemDTO::category).containsExactly("Java");
            verify(knowledgeBaseRepository).findByCategoryOrderByUploadedAtDesc("Java");
        }

        @Test
        @DisplayName("分类为空白时应查询未分类知识库")
        void shouldListUncategorizedWhenCategoryBlank() {
            List<KnowledgeBaseEntity> entities = List.of(buildEntity(8L, "Uncategorized", 70L, 2, 1, 1));
            List<KnowledgeBaseListItemDTO> dtos = toDtos(entities);
            when(knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc()).thenReturn(entities);
            when(knowledgeBaseMapper.toListItemList(entities)).thenReturn(dtos);

            List<KnowledgeBaseListItemDTO> result =
                    knowledgeBaseListService.listKnowledgeBasesByCategory("   ");

            assertThat(result).hasSize(1);
            verify(knowledgeBaseRepository).findByCategoryIsNullOrderByUploadedAtDesc();
        }
    }

    @Nested
    @DisplayName("详情与名称")
    class GetKnowledgeBases {

        @Test
        @DisplayName("根据ID获取知识库详情时应返回映射后的DTO")
        void shouldGetKnowledgeBaseDetail() {
            KnowledgeBaseEntity entity = buildEntity(9L, "Guide", 128L, 3, 2, 1);
            KnowledgeBaseListItemDTO dto = toDto(entity);
            when(knowledgeBaseRepository.findById(9L)).thenReturn(Optional.of(entity));
            when(knowledgeBaseMapper.toListItemDTO(entity)).thenReturn(dto);

            KnowledgeBaseListItemDTO result = knowledgeBaseListService.getKnowledgeBase(9L);

            assertThat(result.id()).isEqualTo(9L);
            assertThat(result.name()).isEqualTo("Guide");
        }

        @Test
        @DisplayName("知识库ID非法时应抛出业务异常")
        void shouldThrowWhenIdInvalid() {
            assertThatThrownBy(() -> knowledgeBaseListService.getKnowledgeBaseEntity(0L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库ID不合法");

            verify(knowledgeBaseRepository, never()).findById(any());
        }

        @Test
        @DisplayName("知识库不存在时应抛出业务异常")
        void shouldThrowWhenKnowledgeBaseNotFound() {
            when(knowledgeBaseRepository.findById(10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> knowledgeBaseListService.getKnowledgeBaseEntity(10L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库不存在");
        }

        @Test
        @DisplayName("根据ID列表获取知识库名称时应返回名称列表")
        void shouldGetKnowledgeBaseNames() {
            List<KnowledgeBaseEntity> entities = List.of(
                    buildEntity(11L, "Java", 100L, 1, 1, 1),
                    buildEntity(12L, "Spring", 200L, 2, 2, 2)
            );
            when(knowledgeBaseRepository.findAllById(List.of(11L, 12L))).thenReturn(entities);

            List<String> result = knowledgeBaseListService.getKnowledgeBaseNames(List.of(11L, 12L));

            assertThat(result).containsExactly("Java", "Spring");
        }

        @Test
        @DisplayName("名称查询入参为空时应直接返回空列表")
        void shouldReturnEmptyNamesWhenIdsEmpty() {
            assertThat(knowledgeBaseListService.getKnowledgeBaseNames(List.of())).isEmpty();
            verify(knowledgeBaseRepository, never()).findAllById(anyList());
        }
    }

    @Nested
    @DisplayName("分类与搜索")
    class CategoryAndSearch {

        @Test
        @DisplayName("获取全部分类时应直接返回仓储结果")
        void shouldGetAllCategories() {
            when(knowledgeBaseRepository.findAllCategories()).thenReturn(List.of("Java", "Spring"));

            List<String> result = knowledgeBaseListService.getAllCategories();

            assertThat(result).containsExactly("Java", "Spring");
        }

        @Test
        @DisplayName("更新分类时应保存去除空白后的分类")
        void shouldUpdateCategoryWithTrimmedValue() {
            KnowledgeBaseEntity entity = buildEntity(13L, "Update", 88L, 2, 1, 1);
            when(knowledgeBaseRepository.findById(13L)).thenReturn(Optional.of(entity));
            when(knowledgeBaseRepository.save(entity)).thenReturn(entity);

            KnowledgeBaseEntity result = knowledgeBaseListService.updateCategory(13L, "  Java  ");

            assertThat(result.getCategory()).isEqualTo("Java");
            verify(knowledgeBaseRepository).save(entity);
        }

        @Test
        @DisplayName("更新分类为空白时应保存为null")
        void shouldUpdateCategoryToNullWhenBlank() {
            KnowledgeBaseEntity entity = buildEntity(14L, "Blank", 66L, 2, 1, 1);
            entity.setCategory("Old");
            when(knowledgeBaseRepository.findById(14L)).thenReturn(Optional.of(entity));
            when(knowledgeBaseRepository.save(entity)).thenReturn(entity);

            KnowledgeBaseEntity result = knowledgeBaseListService.updateCategory(14L, "   ");

            assertThat(result.getCategory()).isNull();
        }

        @Test
        @DisplayName("搜索关键字为空白时应退化为全量列表查询")
        void shouldFallbackToListWhenKeywordBlank() {
            List<KnowledgeBaseEntity> entities = List.of(buildEntity(15L, "All", 10L, 1, 1, 1));
            List<KnowledgeBaseListItemDTO> dtos = toDtos(entities);
            when(knowledgeBaseRepository.findAllByOrderByUploadedAtDesc()).thenReturn(entities);
            when(knowledgeBaseMapper.toListItemList(entities)).thenReturn(dtos);

            List<KnowledgeBaseListItemDTO> result =
                    knowledgeBaseListService.searchKnowledgeBases("   ");

            assertThat(result).hasSize(1);
            verify(knowledgeBaseRepository).findAllByOrderByUploadedAtDesc();
            verify(knowledgeBaseRepository, never()).searchByKeyword(any());
        }

        @Test
        @DisplayName("搜索关键字时应去除空白后查询并映射结果")
        void shouldSearchByTrimmedKeyword() {
            List<KnowledgeBaseEntity> entities = List.of(buildEntity(16L, "Spring Boot", 20L, 1, 1, 1));
            List<KnowledgeBaseListItemDTO> dtos = toDtos(entities);
            when(knowledgeBaseRepository.searchByKeyword("Spring")).thenReturn(entities);
            when(knowledgeBaseMapper.toListItemList(entities)).thenReturn(dtos);

            List<KnowledgeBaseListItemDTO> result =
                    knowledgeBaseListService.searchKnowledgeBases("  Spring  ");

            assertThat(result).extracting(KnowledgeBaseListItemDTO::name).containsExactly("Spring Boot");
            verify(knowledgeBaseRepository).searchByKeyword("Spring");
        }
    }

    @Nested
    @DisplayName("统计与下载")
    class StatisticsAndDownload {

        @Test
        @DisplayName("获取统计信息时应聚合各项仓储统计值")
        void shouldGetStatistics() {
            when(knowledgeBaseRepository.count()).thenReturn(5L);
            when(ragChatMessageRepository.countByType(RagChatMessageEntity.MessageType.USER))
                    .thenReturn(9L);
            when(knowledgeBaseRepository.sumAccessCount()).thenReturn(21L);
            when(knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED)).thenReturn(3L);
            when(knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING)).thenReturn(1L);

            KnowledgeBaseStatsDTO result = knowledgeBaseListService.getStatistics();

            assertThat(result.totalCount()).isEqualTo(5L);
            assertThat(result.totalQuestionCount()).isEqualTo(9L);
            assertThat(result.totalAccessCount()).isEqualTo(21L);
            assertThat(result.completedCount()).isEqualTo(3L);
            assertThat(result.processingCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("下载文件时应根据storageKey调用文件存储服务")
        void shouldDownloadFileByStorageKey() {
            KnowledgeBaseEntity entity = buildEntity(17L, "Download", 30L, 1, 1, 1);
            entity.setStorageKey("kb/download.pdf");
            byte[] content = new byte[]{1, 2, 3};
            when(knowledgeBaseRepository.findById(17L)).thenReturn(Optional.of(entity));
            when(fileStorageService.download("kb/download.pdf")).thenReturn(content);

            byte[] result = knowledgeBaseListService.downloadFile(17L);

            assertThat(result).containsExactly(1, 2, 3);
            verify(fileStorageService).download("kb/download.pdf");
        }

        @Test
        @DisplayName("下载文件时storageKey为空应抛出业务异常")
        void shouldThrowWhenStorageKeyMissing() {
            KnowledgeBaseEntity entity = buildEntity(18L, "Missing", 40L, 1, 1, 1);
            entity.setStorageKey(" ");
            when(knowledgeBaseRepository.findById(18L)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> knowledgeBaseListService.getEntityForDownload(18L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库文件不存在");

            verify(fileStorageService, never()).download(any());
        }
    }

    @Test
    @DisplayName("按文件大小排序兼容方法应调用对应仓储查询")
    void shouldListKnowledgeBasesBySortUsingSizeOrder() {
        List<KnowledgeBaseEntity> entities = List.of(buildEntity(19L, "Big", 300L, 1, 1, 1));
        List<KnowledgeBaseListItemDTO> dtos = toDtos(entities);
        when(knowledgeBaseRepository.findAllByOrderByFileSizeDesc()).thenReturn(entities);
        when(knowledgeBaseMapper.toListItemList(entities)).thenReturn(dtos);

        List<KnowledgeBaseListItemDTO> result =
                knowledgeBaseListService.listKnowledgeBasesBySort(" size ");

        assertThat(result).extracting(KnowledgeBaseListItemDTO::id).containsExactly(19L);
        verify(knowledgeBaseRepository).findAllByOrderByFileSizeDesc();
    }

    @Test
    @DisplayName("按问题数排序时应按问题数倒序返回结果")
    void shouldListByQuestionCountDesc() {
        KnowledgeBaseEntity low = buildEntity(21L, "Low", 10L, 1, 1, 1);
        KnowledgeBaseEntity high = buildEntity(22L, "High", 10L, 1, 9, 2);
        List<KnowledgeBaseEntity> entities = List.of(low, high);
        when(knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(VectorStatus.FAILED))
                .thenReturn(entities);
        stubMapperListToDtos();

        List<KnowledgeBaseListItemDTO> result =
                knowledgeBaseListService.list(VectorStatus.FAILED, "question");

        assertThat(result).extracting(KnowledgeBaseListItemDTO::id).containsExactly(22L, 21L);
    }

    @Test
    @DisplayName("更新分类时应将保存实体传给仓储")
    void shouldSaveUpdatedCategoryEntity() {
        KnowledgeBaseEntity entity = buildEntity(23L, "Captor", 10L, 1, 1, 1);
        when(knowledgeBaseRepository.findById(23L)).thenReturn(Optional.of(entity));
        when(knowledgeBaseRepository.save(any(KnowledgeBaseEntity.class))).thenReturn(entity);

        knowledgeBaseListService.updateCategory(23L, "  Backend ");

        ArgumentCaptor<KnowledgeBaseEntity> captor =
                ArgumentCaptor.forClass(KnowledgeBaseEntity.class);
        verify(knowledgeBaseRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("Backend");
    }

    private void stubMapperListToDtos() {
        when(knowledgeBaseMapper.toListItemList(anyList())).thenAnswer(invocation -> {
            List<KnowledgeBaseEntity> entities = invocation.getArgument(0);
            return toDtos(entities);
        });
    }

    private List<KnowledgeBaseListItemDTO> toDtos(List<KnowledgeBaseEntity> entities) {
        return entities.stream().map(this::toDto).toList();
    }

    private KnowledgeBaseListItemDTO toDto(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseListItemDTO(
                entity.getId(),
                entity.getName(),
                entity.getCategory(),
                entity.getOriginalFilename(),
                entity.getFileSize(),
                entity.getContentType(),
                entity.getUploadedAt(),
                entity.getLastAccessedAt(),
                entity.getAccessCount(),
                entity.getQuestionCount(),
                entity.getVectorStatus(),
                entity.getVectorError(),
                entity.getChunkCount()
        );
    }

    private KnowledgeBaseEntity buildEntity(
            Long id,
            String name,
            Long fileSize,
            Integer accessCount,
            Integer questionCount,
            int timeOffset
    ) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setCategory("分类-" + name);
        entity.setOriginalFilename(name + ".pdf");
        entity.setFileSize(fileSize);
        entity.setContentType("application/pdf");
        entity.setUploadedAt(LocalDateTime.of(2026, 5, 28, 10, 0).plusHours(timeOffset));
        entity.setLastAccessedAt(LocalDateTime.of(2026, 5, 28, 11, 0).plusHours(timeOffset));
        entity.setAccessCount(accessCount);
        entity.setQuestionCount(questionCount);
        entity.setVectorStatus(VectorStatus.COMPLETED);
        entity.setVectorError(null);
        entity.setChunkCount(3);
        return entity;
    }
}
