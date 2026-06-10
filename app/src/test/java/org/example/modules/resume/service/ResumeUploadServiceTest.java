package org.example.modules.resume.service;

import org.example.common.config.AppConfigProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.file.FileValidationService;
import org.example.modules.resume.listener.AnalyzeStreamProducer;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历上传服务测试")
class ResumeUploadServiceTest {

    @Mock
    private ResumeParseService parseService;

    @Mock
    private FileStorageService storageService;

    @Mock
    private ResumePersistenceService persistenceService;

    @Mock
    private AppConfigProperties appConfig;

    @Mock
    private FileValidationService fileValidationService;

    @Mock
    private AnalyzeStreamProducer analyzeStreamProducer;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private MultipartFile multipartFile;

    @InjectMocks
    private ResumeUploadService resumeUploadService;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Nested
    @DisplayName("上传并分析简历")
    class UploadAndAnalyze {

        @Test
        @DisplayName("应上传新简历并返回待分析结果")
        void shouldUploadNewResumeAndReturnPendingResult() {
            activateTransactionSynchronization();
            ResumeEntity savedResume = buildResume(1L, "resume.pdf", AsyncTaskStatus.PENDING);
            savedResume.setStorageKey("resume/resume.pdf");
            savedResume.setStorageUrl("http://rustfs/resume/resume.pdf");
            when(multipartFile.getOriginalFilename()).thenReturn("resume.pdf");
            when(appConfig.getAllowedTypes()).thenReturn(List.of("application/pdf"));
            when(parseService.detectContentType(multipartFile)).thenReturn("application/pdf");
            when(persistenceService.findExistingResume(multipartFile)).thenReturn(Optional.empty());
            when(parseService.parseResume(multipartFile)).thenReturn("resume text");
            when(storageService.generateObjectKey("resume", "resume.pdf"))
                    .thenReturn("resume/resume.pdf");
            when(storageService.upload(multipartFile, "resume/resume.pdf"))
                    .thenReturn("http://rustfs/resume/resume.pdf");
            when(persistenceService.saveResume(
                    multipartFile,
                    "resume text",
                    "resume/resume.pdf",
                    "http://rustfs/resume/resume.pdf"
            )).thenReturn(savedResume);

            Map<String, Object> result = resumeUploadService.uploadAndAnalyze(multipartFile);

            assertThat(getSection(result, "resume"))
                    .containsEntry("id", 1L)
                    .containsEntry("filename", "resume.pdf")
                    .containsEntry("analyzeStatus", AsyncTaskStatus.PENDING.name());
            assertThat(getSection(result, "storage"))
                    .containsEntry("fileKey", "resume/resume.pdf")
                    .containsEntry("fileUrl", "http://rustfs/resume/resume.pdf")
                    .containsEntry("resumeId", 1L);
            assertThat(result).containsEntry("duplicate", false);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            verify(fileValidationService).validateResumeFile(multipartFile);
            verify(parseService).parseResume(multipartFile);
            verify(storageService).upload(multipartFile, "resume/resume.pdf");
            verify(persistenceService).saveResume(
                    multipartFile,
                    "resume text",
                    "resume/resume.pdf",
                    "http://rustfs/resume/resume.pdf"
            );
        }

        @Test
        @DisplayName("简历重复时应返回重复结果")
        void shouldReturnDuplicateResultWhenResumeExists() {
            ResumeEntity existingResume = buildResume(2L, "resume.pdf", AsyncTaskStatus.COMPLETED);
            existingResume.setStorageKey("resume/existing.pdf");
            existingResume.setStorageUrl("http://rustfs/resume/existing.pdf");
            existingResume.setAccessCount(1);
            when(appConfig.getAllowedTypes()).thenReturn(List.of("application/pdf"));
            when(parseService.detectContentType(multipartFile)).thenReturn("application/pdf");
            when(persistenceService.findExistingResume(multipartFile))
                    .thenReturn(Optional.of(existingResume));
            when(resumeRepository.save(existingResume)).thenReturn(existingResume);

            Map<String, Object> result = resumeUploadService.uploadAndAnalyze(multipartFile);

            assertThat(result).containsEntry("duplicate", true);
            assertThat(getSection(result, "resume"))
                    .containsEntry("id", 2L)
                    .containsEntry("analyzeStatus", AsyncTaskStatus.COMPLETED.name());
            assertThat(getSection(result, "storage"))
                    .containsEntry("fileKey", "resume/existing.pdf")
                    .containsEntry("fileUrl", "http://rustfs/resume/existing.pdf");
            assertThat(existingResume.getAccessCount()).isEqualTo(2);
            verify(resumeRepository).save(existingResume);
            verify(parseService, never()).parseResume(multipartFile);
            verify(storageService, never()).upload(multipartFile, "resume/existing.pdf");
            verify(persistenceService, never()).saveResume(
                    multipartFile,
                    "resume text",
                    "resume/existing.pdf",
                    "http://rustfs/resume/existing.pdf"
            );
        }

        @Test
        @DisplayName("文件类型不支持时应抛出业务异常")
        void shouldThrowWhenContentTypeNotSupported() {
            when(appConfig.getAllowedTypes()).thenReturn(List.of("application/pdf"));
            when(parseService.detectContentType(multipartFile)).thenReturn("image/png");

            assertThatThrownBy(() -> resumeUploadService.uploadAndAnalyze(multipartFile))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED.getCode()));

            verify(persistenceService, never()).findExistingResume(multipartFile);
            verify(storageService, never()).generateObjectKey("resume", null);
        }
    }

    @Nested
    @DisplayName("重新分析简历")
    class Reanalyze {

        @Test
        @DisplayName("存在缓存文本时应重置状态并登记异步任务")
        void shouldResetStatusAndRegisterTaskWhenResumeTextExists() {
            activateTransactionSynchronization();
            ResumeEntity resume = buildResume(3L, "resume.pdf", AsyncTaskStatus.FAILED);
            resume.setResumeText("cached text");
            resume.setAnalyzeError("old error");
            when(resumeRepository.findById(3L)).thenReturn(Optional.of(resume));
            when(resumeRepository.save(resume)).thenReturn(resume);

            resumeUploadService.reanalyze(3L);

            assertThat(resume.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.PENDING);
            assertThat(resume.getAnalyzeError()).isNull();
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            verify(parseService, never()).downloadAndParseContent("resume/3.pdf", "resume.pdf");
            verify(resumeRepository).save(resume);
        }

        @Test
        @DisplayName("缓存文本为空时应重新解析后再发起分析")
        void shouldDownloadAndParseWhenResumeTextMissing() {
            activateTransactionSynchronization();
            ResumeEntity resume = buildResume(4L, "resume.pdf", AsyncTaskStatus.FAILED);
            resume.setStorageKey("resume/4.pdf");
            resume.setResumeText(" ");
            when(resumeRepository.findById(4L)).thenReturn(Optional.of(resume));
            when(parseService.downloadAndParseContent("resume/4.pdf", "resume.pdf"))
                    .thenReturn("parsed text");
            when(resumeRepository.save(resume)).thenReturn(resume);

            resumeUploadService.reanalyze(4L);

            assertThat(resume.getResumeText()).isEqualTo("parsed text");
            assertThat(resume.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.PENDING);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            verify(parseService).downloadAndParseContent("resume/4.pdf", "resume.pdf");
            verify(resumeRepository).save(resume);
        }

        @Test
        @DisplayName("重新解析后仍无文本时应抛出业务异常")
        void shouldThrowWhenDownloadedResumeTextBlank() {
            ResumeEntity resume = buildResume(5L, "resume.pdf", AsyncTaskStatus.FAILED);
            resume.setStorageKey("resume/5.pdf");
            resume.setResumeText(null);
            when(resumeRepository.findById(5L)).thenReturn(Optional.of(resume));
            when(parseService.downloadAndParseContent("resume/5.pdf", "resume.pdf"))
                    .thenReturn(" ");

            assertThatThrownBy(() -> resumeUploadService.reanalyze(5L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_PARSE_FAILED.getCode()));

            verify(resumeRepository, never()).save(resume);
        }
    }

    private void activateTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSection(Map<String, Object> result, String key) {
        return (Map<String, Object>) result.get(key);
    }

    private ResumeEntity buildResume(Long id, String filename, AsyncTaskStatus status) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(id);
        resume.setOriginalFilename(filename);
        resume.setAnalyzeStatus(status);
        return resume;
    }
}
