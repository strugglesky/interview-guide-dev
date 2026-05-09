package org.example.modules.resume.model;

import org.example.common.model.AsyncTaskStatus;

import java.time.LocalDateTime;

/**
 * 简历列表项DTO
 */
public record ResumeListItemDTO(
    Long id,
    String filename,
    Long fileSize,
    LocalDateTime uploadedAt,
    Integer accessCount,
    Integer latestScore,
    LocalDateTime lastAnalyzedAt,
    Integer interviewCount,
    AsyncTaskStatus analyzeStatus,
    String analyzeError
) {}

