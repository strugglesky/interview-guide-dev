package org.example.modules.interview.service;

import lombok.RequiredArgsConstructor;
import org.example.common.evaluation.UnifiedEvaluationService;
import org.example.modules.interview.skill.InterviewSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文字面试答案评估服务
 * 职责：DTO 适配器，将 InterviewQuestionDTO 转为通用 QaRecord，调用 UnifiedEvaluationService
 */
@Service
@RequiredArgsConstructor
public class AnswerEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSkillService skillService;
}
