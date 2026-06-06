package org.example.modules.resume.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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


    /**
     * 查询简历是否已存在（基于文件内容hash）
     *
     * @param file 上传的文件
     * @return 如果存在返回已有的简历实体，否则返回空
     */
    public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        String fileHash = fileHashService.calculateHash(file);
        if (!resumeRepository.existsByFileHash(fileHash)) {
            return Optional.empty();
        }
        log.info("检测到重复简历: method=findExistingResume, fileHash={}", fileHash);
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
    public ResumeEntity saveResume(
            MultipartFile file,
            String resumeText,
            String storageKey,
            String storageUrl
    ) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (!StringUtils.hasText(resumeText)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文本不能为空");
        }
        if (!StringUtils.hasText(storageKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文件存储路径不能为空");
        }
        if (!StringUtils.hasText(storageUrl)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文件访问地址不能为空");
        }
        String fileHash = fileHashService.calculateHash(file);
        ResumeEntity resume = new ResumeEntity();
        resume.setFileHash(fileHash);
        resume.setOriginalFilename(file.getOriginalFilename());
        resume.setFileSize(file.getSize());
        resume.setContentType(file.getContentType());
        resume.setStorageKey(storageKey);
        resume.setStorageUrl(storageUrl);
        resume.setResumeText(resumeText);
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
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历实体不能为空");
        }
        if (response == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评测结果不能为空");
        }
        try {
            ResumeAnalysisEntity entity = resumeMapper.toAnalysisEntity(response);
            entity.setResume(resume);
            entity.setStrengthsJson(objectMapper.writeValueAsString(response.strengths()));
            entity.setSuggestionsJson(objectMapper.writeValueAsString(response.suggestions()));
            ResumeAnalysisEntity savedEntity = resumeAnalysisRepository.save(entity);
            resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
            resume.setAnalyzeError(null);
            resumeRepository.save(resume);
            return savedEntity;
        } catch (JsonProcessingException e) {
            log.error(
                    "保存简历分析失败: method=saveAnalysis, resumeId={}, strengthsSize={}, suggestionsSize={}",
                    resume.getId(),
                    response.strengths() == null ? 0 : response.strengths().size(),
                    response.suggestions() == null ? 0 : response.suggestions().size(),
                    e
            );
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历评测结果序列化失败", e);
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
        ResumeAnalysisEntity entity =
                resumeAnalysisRepository.findFirstByResumeIdOrderByCreatedAtDesc(resumeId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND, "简历分析结果不存在");
        }
        return entity;
    }

    /**
     * 获取简历的最新评测结果（返回DTO）
     * @param resumeId 要查询的简历ID
     * @return 最新的评测结果DTO
     */
    public Optional<ResumeAnalysisResponse> getLatestAnalysisDTO(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历ID不合法");
        }
        ResumeAnalysisEntity entity =
                resumeAnalysisRepository.findFirstByResumeIdOrderByCreatedAtDesc(resumeId);
        return entity == null ? Optional.empty() : Optional.of(entityToDTO(entity));
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
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历分析结果不能为空");
        }
        try {
            List<String> strengths = StringUtils.hasText(entity.getStrengthsJson())
                    ? objectMapper.readValue(entity.getStrengthsJson(), new TypeReference<List<String>>() {})
                    : List.of();
            List<ResumeAnalysisResponse.Suggestion> suggestions =
                    StringUtils.hasText(entity.getSuggestionsJson())
                            ? objectMapper.readValue(
                                    entity.getSuggestionsJson(),
                                    new TypeReference<List<ResumeAnalysisResponse.Suggestion>>() {}
                            )
                            : List.of();
            return new ResumeAnalysisResponse(
                    entity.getOverallScore() == null ? 0 : entity.getOverallScore(),
                    resumeMapper.toScoreDetail(entity),
                    entity.getSummary(),
                    strengths,
                    suggestions,
                    entity.getResume() == null ? null : entity.getResume().getResumeText()
            );
        } catch (JsonProcessingException e) {
            log.error(
                    "将简历分析实体转换为 DTO 失败: method=entityToDTO, analysisId={}, resumeId={}",
                    entity.getId(),
                    entity.getResume() == null ? null : entity.getResume().getId(),
                    e
            );
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历评测结果反序列化失败", e);
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
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        resumeAnalysisRepository.deleteAll(resumeAnalysisRepository.findByResumeId(resume));
        resumeRepository.delete(resume);
    }
}
