package org.example.modules.resume.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.FileHashService;
import org.example.infrastructure.mapper.ResumeMapper;
import org.example.modules.resume.model.ResumeAnalysisEntity;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeAnalysisRepository;
import org.example.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历持久化服务测试")
class ResumePersistenceServiceTest {

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ResumeAnalysisRepository resumeAnalysisRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ResumeMapper resumeMapper;

    @Mock
    private FileHashService fileHashService;

    @Mock
    private MultipartFile multipartFile;

    @InjectMocks
    private ResumePersistenceService resumePersistenceService;

    @Nested
    @DisplayName("查询重复简历")
    class FindExistingResume {

        @Test
        @DisplayName("文件为空时应抛出业务异常")
        void shouldThrowWhenFileEmpty() {
            when(multipartFile.isEmpty()).thenReturn(true);

            assertThatThrownBy(() -> resumePersistenceService.findExistingResume(multipartFile))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
        }

        @Test
        @DisplayName("简历不重复时应返回空")
        void shouldReturnEmptyWhenResumeNotExists() {
            when(multipartFile.isEmpty()).thenReturn(false);
            when(fileHashService.calculateHash(multipartFile)).thenReturn("hash-1");
            when(resumeRepository.existsByFileHash("hash-1")).thenReturn(false);

            Optional<ResumeEntity> result = resumePersistenceService.findExistingResume(multipartFile);

            assertThat(result).isEmpty();
            verify(resumeRepository, never()).findByFileHash("hash-1");
        }

        @Test
        @DisplayName("简历重复时应返回已有记录")
        void shouldReturnExistingResumeWhenDuplicate() {
            ResumeEntity resume = buildResumeEntity(1L);
            when(multipartFile.isEmpty()).thenReturn(false);
            when(fileHashService.calculateHash(multipartFile)).thenReturn("hash-1");
            when(resumeRepository.existsByFileHash("hash-1")).thenReturn(true);
            when(resumeRepository.findByFileHash("hash-1")).thenReturn(Optional.of(resume));

            Optional<ResumeEntity> result = resumePersistenceService.findExistingResume(multipartFile);

            assertThat(result).contains(resume);
            verify(resumeRepository).findByFileHash("hash-1");
        }
    }

    @Nested
    @DisplayName("保存简历")
    class SaveResume {

        @Test
        @DisplayName("应保存新的简历实体")
        void shouldSaveResume() {
            when(multipartFile.isEmpty()).thenReturn(false);
            when(multipartFile.getOriginalFilename()).thenReturn("resume.pdf");
            when(multipartFile.getSize()).thenReturn(2048L);
            when(multipartFile.getContentType()).thenReturn("application/pdf");
            when(fileHashService.calculateHash(multipartFile)).thenReturn("hash-1");
            when(resumeRepository.save(any(ResumeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ResumeEntity result = resumePersistenceService.saveResume(
                    multipartFile,
                    "resume text",
                    "resume/1.pdf",
                    "http://localhost/resume/1.pdf"
            );

            assertThat(result.getFileHash()).isEqualTo("hash-1");
            assertThat(result.getOriginalFilename()).isEqualTo("resume.pdf");
            assertThat(result.getResumeText()).isEqualTo("resume text");
            assertThat(result.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("保存评测结果")
    class SaveAnalysis {

        @Test
        @DisplayName("应保存评测结果并更新简历状态")
        void shouldSaveAnalysisAndUpdateResumeStatus() throws Exception {
            ResumeEntity resume = buildResumeEntity(1L);
            ResumeAnalysisResponse response = buildAnalysisResponse();
            ResumeAnalysisEntity analysisEntity = new ResumeAnalysisEntity();
            ResumeAnalysisEntity savedEntity = new ResumeAnalysisEntity();
            when(resumeMapper.toAnalysisEntity(response)).thenReturn(analysisEntity);
            when(objectMapper.writeValueAsString(response.strengths())).thenReturn("[\"优势1\"]");
            when(objectMapper.writeValueAsString(response.suggestions())).thenReturn("[{\"category\":\"内容\"}]");
            when(resumeAnalysisRepository.save(analysisEntity)).thenReturn(savedEntity);

            ResumeAnalysisEntity result = resumePersistenceService.saveAnalysis(resume, response);

            assertThat(result).isSameAs(savedEntity);
            assertThat(analysisEntity.getResume()).isSameAs(resume);
            assertThat(analysisEntity.getStrengthsJson()).isEqualTo("[\"优势1\"]");
            assertThat(analysisEntity.getSuggestionsJson()).isEqualTo("[{\"category\":\"内容\"}]");
            assertThat(resume.getAnalyzeStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
            assertThat(resume.getAnalyzeError()).isNull();
            verify(resumeRepository).save(resume);
        }

        @Test
        @DisplayName("JSON 序列化失败时应抛出业务异常")
        void shouldThrowWhenSerializeAnalysisFailed() throws Exception {
            ResumeEntity resume = buildResumeEntity(1L);
            ResumeAnalysisResponse response = buildAnalysisResponse();
            ResumeAnalysisEntity analysisEntity = new ResumeAnalysisEntity();
            when(resumeMapper.toAnalysisEntity(response)).thenReturn(analysisEntity);
            when(objectMapper.writeValueAsString(response.strengths()))
                    .thenThrow(new JsonProcessingException("serialize failed") {});

            assertThatThrownBy(() -> resumePersistenceService.saveAnalysis(resume, response))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_ANALYSIS_FAILED.getCode()));
        }
    }

    @Nested
    @DisplayName("查询最新评测结果")
    class GetLatestAnalysis {

        @Test
        @DisplayName("应返回最新评测结果")
        void shouldReturnLatestAnalysis() {
            ResumeAnalysisEntity entity = new ResumeAnalysisEntity();
            when(resumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(1L)).thenReturn(entity);

            ResumeAnalysisEntity result = resumePersistenceService.getLatestAnalysis(1L);

            assertThat(result).isSameAs(entity);
        }

        @Test
        @DisplayName("最新评测结果不存在时应抛出业务异常")
        void shouldThrowWhenLatestAnalysisNotFound() {
            when(resumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(1L)).thenReturn(null);

            assertThatThrownBy(() -> resumePersistenceService.getLatestAnalysis(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_ANALYSIS_NOT_FOUND.getCode()));
        }
    }

    @Nested
    @DisplayName("查询最新评测结果DTO")
    class GetLatestAnalysisDto {

        @Test
        @DisplayName("评测结果不存在时应返回空")
        void shouldReturnEmptyWhenLatestAnalysisNotFound() {
            when(resumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(1L)).thenReturn(null);

            Optional<ResumeAnalysisResponse> result = resumePersistenceService.getLatestAnalysisDTO(1L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("评测结果存在时应返回DTO")
        void shouldReturnDtoWhenLatestAnalysisExists() throws Exception {
            ResumeAnalysisEntity entity = buildAnalysisEntity();
            when(resumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(1L)).thenReturn(entity);
            when(resumeMapper.toScoreDetail(entity)).thenReturn(
                    new ResumeAnalysisResponse.ScoreDetail(20, 18, 22, 12, 14)
            );
            when(objectMapper.readValue(eq("[\"优势1\"]"), any(TypeReference.class)))
                    .thenReturn(List.of("优势1"));
            when(objectMapper.readValue(eq("[{\"category\":\"内容\",\"priority\":\"高\",\"issue\":\"问题\",\"recommendation\":\"建议\"}]"), any(TypeReference.class)))
                    .thenReturn(List.of(new ResumeAnalysisResponse.Suggestion("内容", "高", "问题", "建议")));

            Optional<ResumeAnalysisResponse> result = resumePersistenceService.getLatestAnalysisDTO(1L);

            assertThat(result).isPresent();
            assertThat(result.get().summary()).isEqualTo("总结");
            assertThat(result.get().originalText()).isEqualTo("resume text");
        }
    }

    @Nested
    @DisplayName("查询列表与详情")
    class QueryMethods {

        @Test
        @DisplayName("应返回所有简历")
        void shouldReturnAllResumes() {
            List<ResumeEntity> resumes = List.of(buildResumeEntity(1L), buildResumeEntity(2L));
            when(resumeRepository.findAll()).thenReturn(resumes);

            List<ResumeEntity> result = resumePersistenceService.getAllResumes();

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("应返回指定简历的所有评测记录")
        void shouldReturnAnalysesByResumeId() {
            List<ResumeAnalysisEntity> analyses = List.of(new ResumeAnalysisEntity(), new ResumeAnalysisEntity());
            when(resumeAnalysisRepository.findByResumeIdOrderByAnalyzedAtDesc(1L)).thenReturn(analyses);

            List<ResumeAnalysisEntity> result = resumePersistenceService.findAnalysesByResumeId(1L);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("应根据ID返回简历")
        void shouldFindResumeById() {
            ResumeEntity resume = buildResumeEntity(1L);
            when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));

            Optional<ResumeEntity> result = resumePersistenceService.findById(1L);

            assertThat(result).contains(resume);
        }
    }

    @Nested
    @DisplayName("实体转DTO")
    class EntityToDto {

        @Test
        @DisplayName("应将分析实体转换为DTO")
        void shouldConvertEntityToDto() throws Exception {
            ResumeAnalysisEntity entity = buildAnalysisEntity();
            when(resumeMapper.toScoreDetail(entity)).thenReturn(
                    new ResumeAnalysisResponse.ScoreDetail(20, 18, 22, 12, 14)
            );
            when(objectMapper.readValue(eq("[\"优势1\"]"), any(TypeReference.class)))
                    .thenReturn(List.of("优势1"));
            when(objectMapper.readValue(eq("[{\"category\":\"内容\",\"priority\":\"高\",\"issue\":\"问题\",\"recommendation\":\"建议\"}]"), any(TypeReference.class)))
                    .thenReturn(List.of(new ResumeAnalysisResponse.Suggestion("内容", "高", "问题", "建议")));

            ResumeAnalysisResponse result = resumePersistenceService.entityToDTO(entity);

            assertThat(result.overallScore()).isEqualTo(88);
            assertThat(result.summary()).isEqualTo("总结");
            assertThat(result.strengths()).containsExactly("优势1");
            assertThat(result.originalText()).isEqualTo("resume text");
        }

        @Test
        @DisplayName("JSON 反序列化失败时应抛出业务异常")
        void shouldThrowWhenDeserializeFailed() throws Exception {
            ResumeAnalysisEntity entity = buildAnalysisEntity();
            when(objectMapper.readValue(eq("[\"优势1\"]"), any(TypeReference.class)))
                    .thenThrow(new JsonProcessingException("deserialize failed") {});

            assertThatThrownBy(() -> resumePersistenceService.entityToDTO(entity))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_ANALYSIS_FAILED.getCode()));
        }
    }

    @Nested
    @DisplayName("删除简历")
    class DeleteResume {

        @Test
        @DisplayName("应删除简历及其关联分析记录")
        void shouldDeleteResumeAndAnalyses() {
            ResumeEntity resume = buildResumeEntity(1L);
            List<ResumeAnalysisEntity> analyses = List.of(new ResumeAnalysisEntity(), new ResumeAnalysisEntity());
            when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));
            when(resumeAnalysisRepository.findByResume(resume)).thenReturn(analyses);

            resumePersistenceService.deleteResume(1L);

            verify(resumeAnalysisRepository).deleteAll(analyses);
            verify(resumeRepository).delete(resume);
        }

        @Test
        @DisplayName("简历不存在时应抛出业务异常")
        void shouldThrowWhenResumeNotFound() {
            when(resumeRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> resumePersistenceService.deleteResume(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
        }
    }

    private ResumeEntity buildResumeEntity(Long id) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(id);
        resume.setResumeText("resume text");
        resume.setOriginalFilename("resume.pdf");
        return resume;
    }

    private ResumeAnalysisEntity buildAnalysisEntity() {
        ResumeAnalysisEntity entity = new ResumeAnalysisEntity();
        entity.setId(10L);
        entity.setResume(buildResumeEntity(1L));
        entity.setOverallScore(88);
        entity.setSummary("总结");
        entity.setStrengthsJson("[\"优势1\"]");
        entity.setSuggestionsJson(
                "[{\"category\":\"内容\",\"priority\":\"高\",\"issue\":\"问题\",\"recommendation\":\"建议\"}]"
        );
        return entity;
    }

    private ResumeAnalysisResponse buildAnalysisResponse() {
        return new ResumeAnalysisResponse(
                88,
                new ResumeAnalysisResponse.ScoreDetail(20, 18, 22, 12, 14),
                "总结",
                List.of("优势1"),
                List.of(new ResumeAnalysisResponse.Suggestion("内容", "高", "问题", "建议")),
                "resume text"
        );
    }
}
