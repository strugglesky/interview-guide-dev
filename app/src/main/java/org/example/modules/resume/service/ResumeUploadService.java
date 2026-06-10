package org.example.modules.resume.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.config.AppConfigProperties;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.file.FileValidationService;
import org.example.modules.resume.listener.AnalyzeStreamProducer;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

/**
 * 简历上传服务
 * 处理简历上传、解析的业务逻辑
 * AI 分析改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {
    private final ResumeParseService parseService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    private final FileValidationService fileValidationService;
    private final AnalyzeStreamProducer analyzeStreamProducer;
    private final ResumeRepository resumeRepository;

    /**
     * 上传并分析简历（异步）
     *
     * @param file 简历文件
     * @return 上传结果（分析将异步进行）
     */
    @Transactional
    public Map<String, Object> uploadAndAnalyze(MultipartFile file) {
        //1. 验证文件
        fileValidationService.validateResumeFile(file);

        //2，验证文件类型
        String detectedContentType = parseService.detectContentType(file);
        validateDetectedContentType(detectedContentType);

        //3.检查简历是否已经存在（去重）
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            ResumeEntity resume = existingResume.get();
            resume.incrementAccessCount();
            return buildDuplicateResult(resumeRepository.save(resume));
        }

        //4.解析简历文本
        long parseStartTime = System.currentTimeMillis();
        String resumeText = parseService.parseResume(file);
        long parseDurationMs = System.currentTimeMillis() - parseStartTime;
        log.info(
                "简历解析完成: method=uploadAndAnalyze, filename={}, durationMs={}",
                file.getOriginalFilename(),
                parseDurationMs
        );

        //5.保存简历文件到RustFS
        String fileKey = storageService.generateObjectKey("resume", file.getOriginalFilename());
        String fileUrl = storageService.upload(file, fileKey);
        log.info(
                "简历文件已保存到RustFS: method=uploadAndAnalyze, filename={}, fileKey={}, fileUrl={}",
                file.getOriginalFilename(),
                fileKey,
                fileUrl
        );

        //6.在数据库保存简历数据（状态为pending）
        ResumeEntity savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl);

        //7.发送异步分析任务到redis
        sendAnalyzeTaskAfterCommit(savedResume.getId(), resumeText);

        return Map.of(
                //8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
                "resume", Map.of(
                        "id", savedResume.getId(),
                        "filename", savedResume.getOriginalFilename(),
                        "analyzeStatus", AsyncTaskStatus.PENDING.name()
                ),
                "storage", Map.of(
                        "fileKey", fileKey,
                        "fileUrl", fileUrl,
                        "resumeId", savedResume.getId()
                ),
                "duplicate", false
        );
    }

    /**
     * 重新分析简历（手动重试）
     * 从数据库获取简历文本并发送分析任务
     *
     * @param resumeId 简历ID
     */
    @Transactional
    public void reanalyze(Long resumeId) {
        //查找简历是否存在 如果不存在抛出异常
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历ID不合法");
        }
        ResumeEntity resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        String resumeText = resume.getResumeText();
        if (resumeText == null || resumeText.trim().isEmpty()) {
            // 如果没有缓存的文本，尝试重新解析
            resumeText = parseService.downloadAndParseContent(resume.getStorageKey(), resume.getOriginalFilename());
            if (resumeText == null || resumeText.trim().isEmpty()) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
            // 更新缓存的文本
            resume.setResumeText(resumeText);
        }

        //更新状态为PENDING
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        resumeRepository.save(resume);

        //发送分析任务
        sendAnalyzeTaskAfterCommit(resume.getId(), resume.getResumeText());
    }

    private void validateDetectedContentType(String detectedContentType) {
        if (!StringUtils.hasText(detectedContentType)) {
            throw new BusinessException(ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED, "不支持的文件类型");
        }
        if (appConfig.getAllowedTypes() == null || appConfig.getAllowedTypes().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "允许的简历文件类型不能为空");
        }
        boolean allowed = appConfig.getAllowedTypes().stream()
                .anyMatch(type -> type.equalsIgnoreCase(detectedContentType));
        if (!allowed) {
            throw new BusinessException(ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED, "不支持的文件类型");
        }
    }

    private Map<String, Object> buildDuplicateResult(ResumeEntity resume) {
        return Map.of(
                "resume", Map.of(
                        "id", resume.getId(),
                        "filename", resume.getOriginalFilename(),
                        "analyzeStatus", resume.getAnalyzeStatus().name()
                ),
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() == null ? "" : resume.getStorageKey(),
                        "fileUrl", resume.getStorageUrl() == null ? "" : resume.getStorageUrl(),
                        "resumeId", resume.getId()
                ),
                "duplicate", true
        );
    }

    private void sendAnalyzeTaskAfterCommit(Long resumeId, String resumeText) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendAnalyzeTask(resumeId, resumeText);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendAnalyzeTask(resumeId, resumeText);
            }
        });
    }

    private void sendAnalyzeTask(Long resumeId, String resumeText) {
        try {
            Class<?> payloadClass = Class.forName(
                    analyzeStreamProducer.getClass().getName() + "$AnalyzeTaskPayload"
            );
            var constructor = payloadClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object payload = constructor.newInstance(resumeId, resumeText);
            Method sendTaskMethod = analyzeStreamProducer.getClass()
                    .getSuperclass()
                    .getDeclaredMethod("sendTask", Object.class);
            sendTaskMethod.setAccessible(true);
            sendTaskMethod.invoke(analyzeStreamProducer, payload);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (targetException instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error(
                    "发送简历分析任务失败: method=sendAnalyzeTask, resumeId={}",
                    resumeId,
                    targetException
            );
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "简历分析任务发送失败",
                    targetException
            );
        } catch (ReflectiveOperationException e) {
            log.error(
                    "构建简历分析任务失败: method=sendAnalyzeTask, resumeId={}",
                    resumeId,
                    e
            );
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "简历分析任务发送失败", e);
        }
    }
}
