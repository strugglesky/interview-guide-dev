package org.example.modules.resume.service;

import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.FileStorageService;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.example.modules.resume.model.ResumeEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历删除服务测试")
class ResumeDeleteServiceTest {

    @Mock
    private ResumePersistenceService persistenceService;

    @Mock
    private InterviewPersistenceService interviewPersistenceService;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private ResumeDeleteService resumeDeleteService;

    @Nested
    @DisplayName("删除简历")
    class DeleteResumeTest {

        /**
         * 验证存在存储文件时，会依次删除对象存储文件、面试记录和简历数据。
         */
        @Test
        @DisplayName("存在存储文件时应完成完整删除流程")
        void shouldDeleteResumeWithStorageFile() {
            ResumeEntity resume = buildResumeEntity(1L, "resume.pdf", "resume/2026/06/12/test.pdf");
            when(persistenceService.findById(1L)).thenReturn(Optional.of(resume));

            // 删除流程应先处理外部文件，再清理关联会话和简历数据。
            resumeDeleteService.deleteResume(1L);

            verify(fileStorageService).delete("resume/2026/06/12/test.pdf");
            verify(interviewPersistenceService).deleteSessionsByResumeId(1L);
            verify(persistenceService).deleteResume(1L);
        }

        /**
         * 验证存储路径为空时，会跳过对象存储删除，仅清理数据库关联数据。
         */
        @Test
        @DisplayName("存储路径为空时应跳过文件删除")
        void shouldSkipStorageDeletionWhenStorageKeyBlank() {
            ResumeEntity resume = buildResumeEntity(2L, "resume.pdf", " ");
            when(persistenceService.findById(2L)).thenReturn(Optional.of(resume));

            // 当前实现下空白 storageKey 不会触发对象存储删除。
            resumeDeleteService.deleteResume(2L);

            verify(fileStorageService, never()).delete(" ");
            verify(interviewPersistenceService).deleteSessionsByResumeId(2L);
            verify(persistenceService).deleteResume(2L);
        }

        /**
         * 验证简历不存在时会直接抛出业务异常，且不会执行后续删除动作。
         */
        @Test
        @DisplayName("简历不存在时应抛出业务异常")
        void shouldThrowWhenResumeNotFound() {
            when(persistenceService.findById(3L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> resumeDeleteService.deleteResume(3L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));

            verify(fileStorageService, never()).delete(org.mockito.ArgumentMatchers.anyString());
            verify(interviewPersistenceService, never()).deleteSessionsByResumeId(3L);
            verify(persistenceService, never()).deleteResume(3L);
        }
    }

    private ResumeEntity buildResumeEntity(Long id, String filename, String storageKey) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(id);
        resume.setOriginalFilename(filename);
        resume.setStorageKey(storageKey);
        return resume;
    }
}
