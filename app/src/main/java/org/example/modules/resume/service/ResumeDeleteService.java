package org.example.modules.resume.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.file.FileStorageService;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.example.modules.resume.model.ResumeEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 简历删除服务
 * 处理简历删除的业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {

    private final ResumePersistenceService persistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final FileStorageService fileStorageService;

    /**
     * 删除简历
     *
     * @param id 简历ID
     * @throws org.example.common.exception.BusinessException 如果简历不存在
     */
    public void deleteResume(Long id) {
        ResumeEntity resume = persistenceService.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        // 先删除对象存储中的原始文件，避免外部资源删除失败后数据库记录已经丢失。
        if (StringUtils.hasText(resume.getStorageKey())) {
            fileStorageService.delete(resume.getStorageKey());
        }
        // 先清理简历关联的面试记录，再删除简历及分析记录。
        interviewPersistenceService.deleteSessionsByResumeId(id);
        persistenceService.deleteResume(id);
        log.info("简历删除完成: resumeId={}, filename={}", id, resume.getOriginalFilename());
    }
}
