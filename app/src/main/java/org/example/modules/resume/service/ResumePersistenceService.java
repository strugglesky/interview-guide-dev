package org.example.modules.resume.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

/**
 * 简历持久化服务
 * 简历和评测结果的持久化，简历删除时删除所有关联数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {
    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository resumeAnalysisRepository;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final FileHashService fileHashService;
    private final EntityManager entityManager;

    /**
     * 查询简历是否已存在（基于文件内容hash）
     *
     * @param file 上传的文件
     * @return 如果存在返回已有的简历实体，否则返回空
     */
    public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文件不能为空");
        }
        // 先基于文件内容计算哈希，再用唯一索引字段做去重查询。
        String fileHash = fileHashService.calculateHash(file);
        return resumeRepository.findByFileHash(fileHash);
    }

    /**
     * 保存新简历
     * @param file 上传的文件
     * @param resumeText 简历文本
     * @param storageKey 文件存储的key
     * @param storageUrl 文件存储的URL
     * @return 新的简历实体
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeEntity saveResume(MultipartFile file, String resumeText,
                                   String storageKey, String storageUrl) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文件不能为空");
        }
        if (!StringUtils.hasText(resumeText)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文本不能为空");
        }
        String fileHash = fileHashService.calculateHash(file);
        if (resumeRepository.existsByFileHash(fileHash)) {
            throw new BusinessException(ErrorCode.RESUME_DUPLICATE, "简历已存在");
        }

        ResumeEntity resume = new ResumeEntity();
        // 保存时把上传文件元数据、对象存储信息和解析后的文本一次性落库。
        resume.setFileHash(fileHash);
        resume.setOriginalFilename(file.getOriginalFilename());
        resume.setFileSize(file.getSize());
        resume.setContentType(file.getContentType());
        resume.setStorageKey(storageKey);
        resume.setStorageUrl(storageUrl);
        resume.setResumeText(resumeText.strip());
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        return resumeRepository.save(resume);
    }

    /**
     * 保存简历评测结果
     * @param resume 要保存的简历实体
     * @param response 评测结果
     * @return 保存的评测结果实体
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeAnalysisEntity saveAnalysis(ResumeEntity resume, ResumeAnalysisResponse response) {
        if (resume == null || resume.getId() == null) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在");
        }
        if (response == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历分析结果不能为空");
        }
        try {
            ResumeAnalysisEntity analysis = resumeMapper.toAnalysisEntity(response);
            // JSON 字段需要在 Service 层显式序列化，MapStruct 只负责基础字段映射。
            analysis.setResume(resume);
            analysis.setStrengthsJson(objectMapper.writeValueAsString(response.strengths()));
            analysis.setSuggestionsJson(objectMapper.writeValueAsString(response.suggestions()));
            ResumeAnalysisEntity saved = entityManager.merge(analysis);
            resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
            resume.setAnalyzeError(null);
            resumeRepository.save(resume);
            return saved;
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    ErrorCode.RESUME_ANALYSIS_FAILED,
                    "简历分析结果序列化失败",
                    e
            );
        }
    }

    /**
     * 获取简历的最新评测结果
     * @param resumeId 要查询的简历ID
     * @return 最新的评测结果实体
     */
    public ResumeAnalysisEntity getLatestAnalysis(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历ID不合法");
        }
        ResumeAnalysisEntity analysis =
                resumeAnalysisRepository.findFirstByResumeIdOrderByCreatedAtDesc(resumeId);
        if (analysis == null) {
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND, "简历分析结果不存在");
        }
        return analysis;
    }

    /**
     * 获取简历的最新评测结果（返回DTO）
     * @param resumeId 要查询的简历ID
     * @return 最新的评测结果DTO
     */
    public Optional<ResumeAnalysisResponse> getLatestAnalysisDTO(Long resumeId) {
        try {
            return Optional.ofNullable(getLatestAnalysis(resumeId)).map(this::entityToDTO);
        } catch (BusinessException e) {
            if (ErrorCode.RESUME_ANALYSIS_NOT_FOUND.getCode().equals(e.getCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * 获取所有简历列表
     */
    public List<ResumeEntity> getAllResumes() {
        return resumeRepository.findAll();
    }

    /**
     * 获取简历的所有评测记录
     */
    public List<ResumeAnalysisEntity> findAnalysesByResumeId(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历ID不合法");
        }
        return resumeAnalysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId);
    }

    /**
     * 将简历分析实体转换为DTO
     */
    public ResumeAnalysisResponse entityToDTO(ResumeAnalysisEntity entity) {
        if (entity == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历分析实体不能为空");
        }
        try {
            List<String> strengths = StringUtils.hasText(entity.getStrengthsJson())
                    ? objectMapper.readValue(entity.getStrengthsJson(), new TypeReference<>() {})
                    : List.of();
            List<ResumeAnalysisResponse.Suggestion> suggestions =
                    StringUtils.hasText(entity.getSuggestionsJson())
                            ? objectMapper.readValue(
                                    entity.getSuggestionsJson(),
                                    new TypeReference<>() {}
                            )
                            : List.of();
            return new ResumeAnalysisResponse(
                    entity.getOverallScore() != null ? entity.getOverallScore() : 0,
                    resumeMapper.toScoreDetail(entity),
                    entity.getSummary(),
                    strengths,
                    suggestions,
                    entity.getResume() != null ? entity.getResume().getResumeText() : null
            );
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    ErrorCode.RESUME_ANALYSIS_FAILED,
                    "简历分析结果反序列化失败",
                    e
            );
        }
    }

    /**
     * 根据ID获取简历
     */
    public Optional<ResumeEntity> findById(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历ID不合法");
        }
        return resumeRepository.findById(id);
    }

    /**
     * 删除简历及其所有关联数据
     * 包括：简历分析记录
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteResume(Long id){
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历ID不合法");
        }
        ResumeEntity resume = resumeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESUME_NOT_FOUND,
                        "简历不存在"
                ));
        // 先删除所有分析记录，再删除简历主记录，避免外键约束或脏数据残留。
        List<ResumeAnalysisEntity> analyses = resumeAnalysisRepository.findByResumeId(resume);
        for (ResumeAnalysisEntity analysis : analyses) {
            entityManager.remove(entityManager.contains(analysis) ? analysis : entityManager.merge(analysis));
        }
        resumeRepository.delete(resume);
    }
}
