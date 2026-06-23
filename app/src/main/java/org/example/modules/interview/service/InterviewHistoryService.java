package org.example.modules.interview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.infrastructure.export.PdfExportService;
import org.example.infrastructure.mapper.InterviewMapper;
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewDetailDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * 面试历史服务
 * 获取面试会话详情和导出面试报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {
    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final InterviewMapper interviewMapper;

    /**
     * 获取面试会话详情
     */
    public InterviewDetailDTO getInterviewDetail(String sessionId) {
        validateSessionId(sessionId);
        try {
            var session = loadSessionOrThrow(sessionId);
            var answers = interviewPersistenceService.findAnswersBySessionId(sessionId);
            return interviewMapper.toDetailDTO(
                    session,
                    parseObjectList(session.getQuestionsJson(), "questionsJson", sessionId),
                    parseStringList(session.getStrengthsJson(), "strengthsJson", sessionId),
                    parseStringList(session.getImprovementsJson(), "improvementsJson", sessionId),
                    parseObjectList(session.getReferenceAnswersJson(), "referenceAnswersJson", sessionId),
                    buildAnswerDetails(sessionId, answers)
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取面试详情失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取面试详情失败", e);
        }
    }

    private org.example.modules.interview.model.InterviewSessionEntity loadSessionOrThrow(String sessionId) {
        return interviewPersistenceService.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND,
                        "面试会话不存在: " + sessionId
                ));
    }

    private List<InterviewDetailDTO.AnswerDetailDTO> buildAnswerDetails(
            String sessionId,
            List<InterviewAnswerEntity> answers) {
        return interviewMapper.toAnswerDetailDTOList(
                answers,
                answer -> parseAnswerKeyPoints(answer, sessionId)
        );
    }

    private List<Object> parseObjectList(String json, String fieldName, String sessionId) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {});
        } catch (JacksonException e) {
            log.error("反序列化面试详情对象列表失败: sessionId={}, fieldName={}", sessionId, fieldName, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化面试详情数据失败", e);
        }
    }

    private List<String> parseStringList(String json, String fieldName, String sessionId) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JacksonException e) {
            log.error("反序列化面试详情字符串列表失败: sessionId={}, fieldName={}", sessionId, fieldName, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化面试详情数据失败", e);
        }
    }

    private List<String> parseAnswerKeyPoints(InterviewAnswerEntity answer, String sessionId) {
        if (answer == null || !StringUtils.hasText(answer.getKeyPointsJson())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(answer.getKeyPointsJson(), new TypeReference<List<String>>() {});
        } catch (JacksonException e) {
            log.error(
                    "反序列化答案关键点失败: sessionId={}, answerId={}, questionIndex={}",
                    sessionId,
                    answer.getId(),
                    answer.getQuestionIndex(),
                    e
            );
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反序列化答案关键点失败", e);
        }
    }

    private void validateSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试会话ID不能为空");
        }
    }

    /**
     * 导出面试报告为PDF
     */
    public byte[] exportInterviewPdf(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        try {
            return pdfExportService.exportInterviewReport(session);
        } catch (Exception e) {
            log.error("导出PDF失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }
}
