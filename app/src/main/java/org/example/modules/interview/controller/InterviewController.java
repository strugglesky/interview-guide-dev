package org.example.modules.interview.controller;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.source.ByteArrayOutputStream;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.annotation.RateLimit;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.common.result.Result;
import org.example.modules.interview.model.CreateInterviewRequest;
import org.example.modules.interview.model.InterviewDetailDTO;
import org.example.modules.interview.model.InterviewReportDTO;
import org.example.modules.interview.model.InterviewSessionDTO;
import org.example.modules.interview.model.SessionListItemDTO;
import org.example.modules.interview.model.SubmitAnswerRequest;
import org.example.modules.interview.model.SubmitAnswerResponse;
import org.example.modules.interview.service.InterviewHistoryService;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.example.modules.interview.service.InterviewSessionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 面试控制器
 * 提供模拟面试相关的API接口
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、问答交互与报告生成")
public class InterviewController {
    private final InterviewHistoryService interviewHistoryService;
    private final InterviewSessionService interviewSessionService;
    private final InterviewPersistenceService interviewPersistenceService;

    /**
     * 列出所有面试会话（用于面试记录页）
     */
    @GetMapping("/api/interview/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(interviewPersistenceService.findAll().stream().map(SessionListItemDTO::from).toList());
    }

    /**
     * 创建面试会话
     */
    @PostMapping("/api/interview/sessions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        return Result.success(interviewSessionService.createSession(request));
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/api/interview/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        return Result.success(interviewSessionService.getSession(sessionId));
    }

    /**
     * 获取当前问题
     */
    @GetMapping("/api/interview/sessions/{sessionId}/question")
    public Result<Map<String,Object>> getCurrentQuestion(@PathVariable String sessionId) {
        return Result.success(interviewSessionService.getCurrentQuestionResponse(sessionId));
    }

    /**
     * 提交答案
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body){
        Object questionIndexValue = body == null ? null : body.get("questionIndex");
        Integer questionIndex = null;
        if (questionIndexValue instanceof Number number) {
            questionIndex = number.intValue();
        } else if (questionIndexValue instanceof String text && StringUtils.hasText(text)) {
            try {
                questionIndex = Integer.valueOf(text.strip());
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "questionIndex 参数无效", e);
            }
        }
        Object answerValue = body == null ? null : body.get("answer");
        String answer = answerValue instanceof String text ? text : answerValue == null ? null : String.valueOf(answerValue);
        return Result.success(interviewSessionService.submitAnswer(
                new SubmitAnswerRequest(sessionId, questionIndex, answer)
        ));
    }

    /**
     * 生成面试报告
     */
    @GetMapping("/api/interview/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        return Result.success(interviewSessionService.generateReport(sessionId));
    }

    /**
     * 查找未完成的面试会话
     * GET /api/interview/sessions/unfinished/{resumeId}
     */
    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        return Result.success(interviewSessionService.findUnfinishedSession(resumeId).orElse(null));
    }

    /**
     * 暂存答案（不进入下一题）
     */
    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body){
        Object questionIndexValue = body == null ? null : body.get("questionIndex");
        Integer questionIndex = null;
        if (questionIndexValue instanceof Number number) {
            questionIndex = number.intValue();
        } else if (questionIndexValue instanceof String text && StringUtils.hasText(text)) {
            try {
                questionIndex = Integer.valueOf(text.strip());
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "questionIndex 参数无效", e);
            }
        }
        Object answerValue = body == null ? null : body.get("answer");
        String answer = answerValue instanceof String text ? text : answerValue == null ? null : String.valueOf(answerValue);
        interviewSessionService.saveAnswer(new SubmitAnswerRequest(sessionId, questionIndex, answer));
        return Result.success();
    }

    /**
     * 提前交卷
     */
    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        interviewSessionService.completeInterview(sessionId);
        return Result.success();
    }

    /**
     * 获取面试会话详情
     * GET /api/interview/sessions/{sessionId}/details
     */
    @GetMapping("/api/interview/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getInterviewDetail(@PathVariable String sessionId) {
        return Result.success(interviewHistoryService.getInterviewDetail(sessionId));
    }

    /**
     * 导出面试报告为PDF
     */
    @GetMapping("/api/interview/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportInterviewPdf(@PathVariable String sessionId) {
        try {
            byte[] pdfBytes = interviewHistoryService.exportInterviewPdf(sessionId);
            String filename = URLEncoder.encode("模拟面试报告_" + sessionId + ".pdf",
                    StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdfBytes);
        } catch (Exception e) {
            log.error("导出PDF失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除面试会话
     */
    @DeleteMapping("/api/interview/sessions/{sessionId}")
    public Result<Void> deleteInterview(@PathVariable String sessionId) {
        interviewPersistenceService.deleteSessionBySessionId(sessionId);
        return Result.success();
    }


}
