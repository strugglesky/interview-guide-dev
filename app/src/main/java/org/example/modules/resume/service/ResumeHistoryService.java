package org.example.modules.resume.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.infrastructure.export.PdfExportService;
import org.example.infrastructure.file.FileStorageService;
import org.example.modules.resume.repository.ResumeAnalysisRepository;
import org.example.modules.resume.repository.ResumeRepository;
import org.springframework.stereotype.Service;


/**
 * 简历历史服务
 * 简历历史和导出简历分析报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeHistoryService {
    private final ResumePersistenceService resumePersistenceService;
    private final PdfExportService  pdfExportService;
}
