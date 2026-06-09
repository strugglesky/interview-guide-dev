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
import java.util.LinkedHashMap;
import java.util.Map;

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
    public Map<String, Object> uploadAndAnalyze(MultipartFile file) {
        //1. 验证文件

        //2，验证文件类型

        //3.检查简历是否已经存在（去重）

        //4.解析简历文本

        //5.保存简历文件到RustFS

        //6.在数据库保存简历数据（状态为pending）

        //7.发送异步分析任务到redis

        //8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of();
    }

    /**
     * 重新分析简历（手动重试）
     * 从数据库获取简历文本并发送分析任务
     *
     * @param resumeId 简历ID
     */
    @Transactional
    public void reanalyze(Long resumeId) {

    }



}
