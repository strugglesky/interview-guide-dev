package org.example.modules.knowledgebase.service;

import org.example.common.exception.BusinessException;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库计数服务测试")
class KnowledgeBaseCountServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @InjectMocks
    private KnowledgeBaseCountService knowledgeBaseCountService;

    @Nested
    @DisplayName("更新提问计数")
    class UpdateQuestionCounts {

        /**
         * 验证知识库ID列表为空时会直接跳过更新。
         */
        @Test
        @DisplayName("知识库ID列表为空时应直接跳过")
        void shouldSkipWhenKnowledgeBaseIdsEmpty() {
            assertThatCode(() -> knowledgeBaseCountService.updateQuestionCounts(List.of()))
                    .doesNotThrowAnyException();

            verify(knowledgeBaseRepository, never()).findAllById(List.of());
            verify(knowledgeBaseRepository, never()).incrementQuestionCountBatch(List.of());
        }

        /**
         * 验证只有非法ID时不会执行存在性校验和批量更新。
         */
        @Test
        @DisplayName("只有非法知识库ID时应直接跳过")
        void shouldSkipWhenAllKnowledgeBaseIdsInvalid() {
            assertThatCode(() -> knowledgeBaseCountService.updateQuestionCounts(
                    Arrays.asList(null, 0L, -1L)
            ))
                    .doesNotThrowAnyException();

            verify(knowledgeBaseRepository, never()).findAllById(anyList());
            verify(knowledgeBaseRepository, never()).incrementQuestionCountBatch(anyList());
        }

        /**
         * 验证更新前会先校验所有合法知识库都存在。
         */
        @Test
        @DisplayName("存在缺失知识库时应抛出业务异常并列出缺失ID")
        void shouldThrowWhenKnowledgeBaseMissing() {
            KnowledgeBaseEntity existing = buildKnowledgeBaseEntity(1L);
            when(knowledgeBaseRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(existing));

            assertThatThrownBy(() -> knowledgeBaseCountService.updateQuestionCounts(List.of(1L, 2L)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("部分知识库不存在，缺失ID: [2]");

            verify(knowledgeBaseRepository, never()).incrementQuestionCountBatch(List.of(1L, 2L));
        }

        /**
         * 验证更新时会过滤非法值、去重并批量更新合法知识库。
         */
        @Test
        @DisplayName("应过滤非法和重复ID后批量更新提问计数")
        void shouldUpdateQuestionCountsWithValidDistinctIds() {
            KnowledgeBaseEntity first = buildKnowledgeBaseEntity(1L);
            KnowledgeBaseEntity second = buildKnowledgeBaseEntity(2L);
            when(knowledgeBaseRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(first, second));
            when(knowledgeBaseRepository.incrementQuestionCountBatch(List.of(1L, 2L))).thenReturn(2);

            assertThatCode(() -> knowledgeBaseCountService.updateQuestionCounts(
                    Arrays.asList(1L, 2L, 2L, null, 0L, -1L)
            )).doesNotThrowAnyException();

            verify(knowledgeBaseRepository).findAllById(List.of(1L, 2L));
            verify(knowledgeBaseRepository).incrementQuestionCountBatch(List.of(1L, 2L));
        }
    }

    /**
     * 构造可复用的知识库实体，避免测试中重复拼装。
     */
    private KnowledgeBaseEntity buildKnowledgeBaseEntity(Long id) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName("知识库-" + id);
        entity.setOriginalFilename("guide-" + id + ".pdf");
        return entity;
    }
}
