package org.example.modules.interview.controller;

import org.example.common.constant.CommonConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.common.model.ErrorCode;
import org.example.common.result.Result;
import org.example.modules.interview.model.CreateInterviewRequest;
import org.example.modules.interview.model.InterviewDetailDTO;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewReportDTO;
import org.example.modules.interview.model.InterviewSessionDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.model.SessionListItemDTO;
import org.example.modules.interview.model.SubmitAnswerRequest;
import org.example.modules.interview.model.SubmitAnswerResponse;
import org.example.modules.interview.service.InterviewHistoryService;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.example.modules.interview.service.InterviewSessionService;
import org.example.modules.interview.skill.InterviewSkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Interview controller tests")
class InterviewControllerTest {

    @Mock
    private InterviewHistoryService interviewHistoryService;

    @Mock
    private InterviewSessionService interviewSessionService;

    @Mock
    private InterviewPersistenceService interviewPersistenceService;

    @InjectMocks
    private InterviewController interviewController;

    @Nested
    @DisplayName("Session management")
    class SessionManagementTests {

        @Test
        @DisplayName("should list sessions from persistence service")
        void shouldListSessionsFromPersistenceService() {
            InterviewSessionEntity session = buildSessionEntity("session-1");
            when(interviewPersistenceService.findAll()).thenReturn(List.of(session));

            Result<List<SessionListItemDTO>> result = interviewController.listSessions();

            assertSuccess(result);
            assertThat(result.getData()).containsExactly(SessionListItemDTO.from(session));
            verify(interviewPersistenceService).findAll();
        }

        @Test
        @DisplayName("should create session")
        void shouldCreateSession() {
            CreateInterviewRequest request = buildCreateRequest();
            InterviewSessionDTO expected = buildSessionDto("session-1");
            when(interviewSessionService.createSession(request)).thenReturn(expected);

            Result<InterviewSessionDTO> result = interviewController.createSession(request);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(interviewSessionService).createSession(request);
        }

        @Test
        @DisplayName("should get session")
        void shouldGetSession() {
            InterviewSessionDTO expected = buildSessionDto("session-2");
            when(interviewSessionService.getSession("session-2")).thenReturn(expected);

            Result<InterviewSessionDTO> result = interviewController.getSession("session-2");

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(interviewSessionService).getSession("session-2");
        }

        @Test
        @DisplayName("should find unfinished session")
        void shouldFindUnfinishedSession() {
            InterviewSessionDTO expected = buildSessionDto("session-3");
            when(interviewSessionService.findUnfinishedSession(1L)).thenReturn(Optional.of(expected));

            Result<InterviewSessionDTO> result = interviewController.findUnfinishedSession(1L);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(interviewSessionService).findUnfinishedSession(1L);
        }

        @Test
        @DisplayName("should return null data when unfinished session not found")
        void shouldReturnNullDataWhenUnfinishedSessionNotFound() {
            when(interviewSessionService.findUnfinishedSession(2L)).thenReturn(Optional.empty());

            Result<InterviewSessionDTO> result = interviewController.findUnfinishedSession(2L);

            assertSuccess(result);
            assertThat(result.getData()).isNull();
            verify(interviewSessionService).findUnfinishedSession(2L);
        }

        @Test
        @DisplayName("should delete interview session")
        void shouldDeleteInterviewSession() {
            Result<Void> result = interviewController.deleteInterview("session-4");

            assertSuccess(result);
            assertThat(result.getData()).isNull();
            verify(interviewPersistenceService).deleteSessionBySessionId("session-4");
        }
    }

    @Nested
    @DisplayName("Question and answer flow")
    class QuestionAndAnswerFlowTests {

        @Test
        @DisplayName("should get current question response")
        void shouldGetCurrentQuestionResponse() {
            Map<String, Object> expected = Map.of(
                    "questionIndex", 0,
                    "question", "请介绍一下你做过的核心项目"
            );
            when(interviewSessionService.getCurrentQuestionResponse("session-5")).thenReturn(expected);

            Result<Map<String, Object>> result = interviewController.getCurrentQuestion("session-5");

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(interviewSessionService).getCurrentQuestionResponse("session-5");
        }

        @Test
        @DisplayName("should submit answer with numeric question index")
        void shouldSubmitAnswerWithNumericQuestionIndex() {
            SubmitAnswerResponse expected = new SubmitAnswerResponse(
                    true,
                    InterviewQuestionDTO.create(1, "MySQL 索引失效场景有哪些", "MYSQL", "MySQL"),
                    1,
                    3
            );
            when(interviewSessionService.submitAnswer(any(SubmitAnswerRequest.class))).thenReturn(expected);

            Result<SubmitAnswerResponse> result = interviewController.submitAnswer(
                    "session-6",
                    Map.of("questionIndex", 0, "answer", "我负责订单系统架构设计")
            );

            ArgumentCaptor<SubmitAnswerRequest> captor =
                    ArgumentCaptor.forClass(SubmitAnswerRequest.class);
            verify(interviewSessionService).submitAnswer(captor.capture());
            assertThat(captor.getValue()).isEqualTo(
                    new SubmitAnswerRequest("session-6", 0, "我负责订单系统架构设计")
            );
            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
        }

        @Test
        @DisplayName("should submit answer with string question index")
        void shouldSubmitAnswerWithStringQuestionIndex() {
            when(interviewSessionService.submitAnswer(any(SubmitAnswerRequest.class)))
                    .thenReturn(new SubmitAnswerResponse(false, null, 2, 3));

            interviewController.submitAnswer(
                    "session-7",
                    Map.of("questionIndex", " 2 ", "answer", "回答内容")
            );

            ArgumentCaptor<SubmitAnswerRequest> captor =
                    ArgumentCaptor.forClass(SubmitAnswerRequest.class);
            verify(interviewSessionService).submitAnswer(captor.capture());
            assertThat(captor.getValue()).isEqualTo(new SubmitAnswerRequest("session-7", 2, "回答内容"));
        }

        @Test
        @DisplayName("should throw business exception when question index invalid")
        void shouldThrowBusinessExceptionWhenQuestionIndexInvalid() {
            assertThatThrownBy(() -> interviewController.submitAnswer(
                    "session-8",
                    Map.of("questionIndex", "bad-index", "answer", "回答内容")
            ))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception ->
                            assertThat(((BusinessException) exception).getCode())
                                    .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
            verifyNoInteractions(interviewSessionService);
        }

        @Test
        @DisplayName("should save answer without moving next question")
        void shouldSaveAnswerWithoutMovingNextQuestion() {
            Result<Void> result = interviewController.saveAnswer(
                    "session-9",
                    Map.of("questionIndex", "1", "answer", "暂存答案")
            );

            ArgumentCaptor<SubmitAnswerRequest> captor =
                    ArgumentCaptor.forClass(SubmitAnswerRequest.class);
            verify(interviewSessionService).saveAnswer(captor.capture());
            assertThat(captor.getValue()).isEqualTo(new SubmitAnswerRequest("session-9", 1, "暂存答案"));
            assertSuccess(result);
        }

        @Test
        @DisplayName("should complete interview")
        void shouldCompleteInterview() {
            Result<Void> result = interviewController.completeInterview("session-10");

            assertSuccess(result);
            verify(interviewSessionService).completeInterview("session-10");
        }
    }

    @Nested
    @DisplayName("Report and detail")
    class ReportAndDetailTests {

        @Test
        @DisplayName("should get interview report")
        void shouldGetInterviewReport() {
            InterviewReportDTO expected = buildReport("session-11");
            when(interviewSessionService.generateReport("session-11")).thenReturn(expected);

            Result<InterviewReportDTO> result = interviewController.getReport("session-11");

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(interviewSessionService).generateReport("session-11");
        }

        @Test
        @DisplayName("should get interview detail")
        void shouldGetInterviewDetail() {
            InterviewDetailDTO expected = buildDetail("session-12");
            when(interviewHistoryService.getInterviewDetail("session-12")).thenReturn(expected);

            Result<InterviewDetailDTO> result = interviewController.getInterviewDetail("session-12");

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(interviewHistoryService).getInterviewDetail("session-12");
        }

        @Test
        @DisplayName("should export interview pdf")
        void shouldExportInterviewPdf() {
            byte[] pdfBytes = new byte[]{1, 2, 3};
            when(interviewHistoryService.exportInterviewPdf("session-13")).thenReturn(pdfBytes);

            ResponseEntity<byte[]> response = interviewController.exportInterviewPdf("session-13");

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).containsExactly(1, 2, 3);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .contains("attachment; filename*=UTF-8''");
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .contains("session-13");
            verify(interviewHistoryService).exportInterviewPdf("session-13");
        }

        @Test
        @DisplayName("should return internal server error when export fails")
        void shouldReturnInternalServerErrorWhenExportFails() {
            when(interviewHistoryService.exportInterviewPdf("session-14"))
                    .thenThrow(new RuntimeException("export failed"));

            ResponseEntity<byte[]> response = interviewController.exportInterviewPdf("session-14");

            assertThat(response.getStatusCode().is5xxServerError()).isTrue();
            assertThat(response.getBody()).isNull();
            verify(interviewHistoryService).exportInterviewPdf("session-14");
        }
    }

    private void assertSuccess(Result<?> result) {
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(CommonConstants.StatusCode.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
    }

    private CreateInterviewRequest buildCreateRequest() {
        return new CreateInterviewRequest(
                "Java resume text",
                3,
                1L,
                false,
                "dashscope",
                "java-backend",
                "mid",
                List.of(new InterviewSkillService.CategoryDTO("JAVA", "Java", "CORE", "java.md", true)),
                "负责 Java 后端开发"
        );
    }

    private InterviewSessionDTO buildSessionDto(String sessionId) {
        return new InterviewSessionDTO(
                sessionId,
                "Java resume text",
                3,
                0,
                List.of(InterviewQuestionDTO.create(0, "请介绍一下你做过的核心项目", "PROJECT", "项目经验")),
                InterviewSessionDTO.SessionStatus.CREATED
        );
    }

    private InterviewSessionEntity buildSessionEntity(String sessionId) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setSkillId("java-backend");
        session.setDifficulty("mid");
        session.setTotalQuestions(3);
        session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
        session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
        session.setOverallScore(90);
        session.setCreatedAt(LocalDateTime.of(2026, 6, 23, 10, 0));
        session.setCompletedAt(LocalDateTime.of(2026, 6, 23, 10, 30));
        return session;
    }

    private InterviewReportDTO buildReport(String sessionId) {
        return new InterviewReportDTO(
                sessionId,
                2,
                90,
                List.of(new InterviewReportDTO.CategoryScore("项目经验", 92, 1)),
                List.of(new InterviewReportDTO.QuestionEvaluation(
                        0,
                        "请介绍一下你做过的核心项目",
                        "项目经验",
                        "我负责订单系统架构设计",
                        92,
                        "表达清晰"
                )),
                "整体表现良好",
                List.of("项目经历具体"),
                List.of("补充压测数据"),
                List.of(new InterviewReportDTO.ReferenceAnswer(
                        0,
                        "请介绍一下你做过的核心项目",
                        "从背景、职责、难点和结果展开",
                        List.of("背景", "职责", "结果")
                ))
        );
    }

    private InterviewDetailDTO buildDetail(String sessionId) {
        return new InterviewDetailDTO(
                1L,
                sessionId,
                2,
                "EVALUATED",
                "COMPLETED",
                null,
                91,
                "整体表现良好",
                LocalDateTime.of(2026, 6, 23, 10, 0),
                LocalDateTime.of(2026, 6, 23, 10, 30),
                List.of("Q1", "Q2"),
                List.of("项目经历具体"),
                List.of("补充压测数据"),
                List.of("参考答案1"),
                List.of(new InterviewDetailDTO.AnswerDetailDTO(
                        0,
                        "请介绍一下你做过的核心项目",
                        "项目经验",
                        "我负责订单系统架构设计",
                        95,
                        "回答完整",
                        "从背景、职责、难点和结果展开",
                        List.of("背景", "职责"),
                        LocalDateTime.of(2026, 6, 23, 10, 10)
                ))
        );
    }
}
