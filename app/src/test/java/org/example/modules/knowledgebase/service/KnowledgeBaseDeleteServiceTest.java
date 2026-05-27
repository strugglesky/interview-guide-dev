package org.example.modules.knowledgebase.service;

import org.example.common.exception.BusinessException;
import org.example.infrastructure.file.FileStorageService;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.RagChatSessionEntity;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.example.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库删除服务测试")
class KnowledgeBaseDeleteServiceTest {

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private RagChatSessionRepository ragChatSessionRepository;

    @Mock
    private KnowledgeBaseVectorService knowledgeBaseVectorService;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private KnowledgeBaseDeleteService knowledgeBaseDeleteService;

    @Nested
    @DisplayName("删除知识库")
    class DeleteKnowledgeBase {

        @Test
        @DisplayName("应删除会话关联、向量数据、数据库记录，并在提交后删除文件")
        void shouldDeleteKnowledgeBaseAndScheduleFileDeletionAfterCommit() {
            Long knowledgeBaseId = 1L;
            KnowledgeBaseEntity knowledgeBase = buildKnowledgeBaseEntity(knowledgeBaseId, "kb/test.pdf");
            RagChatSessionEntity session = buildSessionEntity(knowledgeBase);
            when(knowledgeBaseRepository.findById(knowledgeBaseId)).thenReturn(java.util.Optional.of(knowledgeBase));
            when(ragChatSessionRepository.findByKnowledgeBaseIds(List.of(knowledgeBaseId)))
                    .thenReturn(List.of(session));

            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThatCode(() -> knowledgeBaseDeleteService.deleteKnowledgeBase(knowledgeBaseId))
                        .doesNotThrowAnyException();

                assertThat(session.getKnowledgeBases()).isEmpty();
                verify(ragChatSessionRepository).saveAll(List.of(session));
                verify(knowledgeBaseVectorService).deleteByKnowledgeBaseId(knowledgeBaseId);
                verify(knowledgeBaseRepository).delete(knowledgeBase);
                verify(fileStorageService, never()).delete("kb/test.pdf");

                List<TransactionSynchronization> synchronizations =
                        TransactionSynchronizationManager.getSynchronizations();
                assertThat(synchronizations).hasSize(1);
                synchronizations.forEach(TransactionSynchronization::afterCommit);

                verify(fileStorageService).delete("kb/test.pdf");
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("知识库ID非法时应抛出业务异常")
        void shouldThrowWhenKnowledgeBaseIdInvalid() {
            assertThatThrownBy(() -> knowledgeBaseDeleteService.deleteKnowledgeBase(0L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库ID不合法");

            verify(knowledgeBaseRepository, never()).findById(0L);
            verify(ragChatSessionRepository, never()).findByKnowledgeBaseIds(anyList());
            verify(knowledgeBaseVectorService, never()).deleteByKnowledgeBaseId(0L);
        }

        @Test
        @DisplayName("知识库不存在时应抛出业务异常")
        void shouldThrowWhenKnowledgeBaseNotFound() {
            when(knowledgeBaseRepository.findById(1L)).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> knowledgeBaseDeleteService.deleteKnowledgeBase(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库不存在");

            verify(ragChatSessionRepository, never()).findByKnowledgeBaseIds(anyList());
            verify(knowledgeBaseVectorService, never()).deleteByKnowledgeBaseId(1L);
            verify(fileStorageService, never()).delete("kb/test.pdf");
        }
    }

    private KnowledgeBaseEntity buildKnowledgeBaseEntity(Long id, String storageKey) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName("知识库" + id);
        entity.setOriginalFilename("guide-" + id + ".pdf");
        entity.setStorageKey(storageKey);
        return entity;
    }

    private RagChatSessionEntity buildSessionEntity(KnowledgeBaseEntity knowledgeBase) {
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setKnowledgeBases(new HashSet<>(List.of(knowledgeBase)));
        session.setTitle("会话");
        return session;
    }
}
