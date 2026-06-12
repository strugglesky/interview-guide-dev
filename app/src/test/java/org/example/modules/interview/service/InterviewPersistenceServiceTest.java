package org.example.modules.interview.service;

import org.example.common.model.AsyncTaskStatus;
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewReportDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.repository.InterviewAnswerRepository;
import org.example.modules.interview.repository.InterviewSessionRepository;
import org.example.modules.resume.model.ResumeEntity;
import org.example.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.TypeFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Interview persistence service tests")
class InterviewPersistenceServiceTest {

    @Mock
    private InterviewSessionRepository sessionRepository;

    @Mock
    private InterviewAnswerRepository answerRepository;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private InterviewPersistenceService interviewPersistenceService;

    @Nested
    @DisplayName("Save methods")
    class SaveMethods {

        @Test
        @DisplayName("should save new session with resume")
        void shouldSaveNewSessionWithResume() throws Exception {
            List<InterviewQuestionDTO> questions = buildQuestions();
            ResumeEntity resume = buildResume(1L);
            when(resumeRepository.getReferenceById(1L)).thenReturn(resume);
            when(objectMapper.writeValueAsString(questions)).thenReturn("[{\"questionIndex\":0}]");
            when(sessionRepository.save(any(InterviewSessionEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            InterviewSessionEntity result = interviewPersistenceService.saveNewSession(
                    "session-1", 1L, 2, questions, "dashscope", "java", "mid"
            );

            ArgumentCaptor<InterviewSessionEntity> captor =
                    ArgumentCaptor.forClass(InterviewSessionEntity.class);
            verify(sessionRepository).save(captor.capture());
            InterviewSessionEntity saved = captor.getValue();
            assertThat(saved.getSessionId()).isEqualTo("session-1");
            assertThat(saved.getResume()).isSameAs(resume);
            assertThat(saved.getTotalQuestions()).isEqualTo(2);
            assertThat(saved.getQuestionsJson()).isEqualTo("[{\"questionIndex\":0}]");
            assertThat(saved.getLlmProvider()).isEqualTo("dashscope");
            assertThat(saved.getSkillId()).isEqualTo("java");
            assertThat(saved.getDifficulty()).isEqualTo("mid");
            assertThat(saved.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.CREATED);
            assertThat(result).isSameAs(saved);
        }

        @Test
        @DisplayName("should save new session without resume")
        void shouldSaveNewSessionWithoutResume() throws Exception {
            List<InterviewQuestionDTO> questions = buildQuestions();
            when(objectMapper.writeValueAsString(questions)).thenReturn("[{\"questionIndex\":0}]");
            when(sessionRepository.save(any(InterviewSessionEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            InterviewSessionEntity result = interviewPersistenceService.saveNewSession(
                    "session-2", null, 2, questions, "dashscope", "java", "senior"
            );

            assertThat(result.getResume()).isNull();
            assertThat(result.getDifficulty()).isEqualTo("senior");
            verifyNoInteractions(resumeRepository);
        }

        @Test
        @DisplayName("should save answer")
        void shouldSaveAnswer() {
            InterviewSessionEntity session = buildSession("session-3");
            when(sessionRepository.findBySessionId("session-3")).thenReturn(Optional.of(session));
            when(answerRepository.save(any(InterviewAnswerEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            InterviewAnswerEntity result = interviewPersistenceService.saveAnswer(
                    "session-3", 1, "What is a covering index", "MySQL",
                    "It avoids extra table lookups", 85, "Clear explanation"
            );

            assertThat(result.getSession()).isSameAs(session);
            assertThat(result.getQuestionIndex()).isEqualTo(1);
            assertThat(result.getQuestion()).isEqualTo("What is a covering index");
            assertThat(result.getCategory()).isEqualTo("MySQL");
            assertThat(result.getUserAnswer()).isEqualTo("It avoids extra table lookups");
            assertThat(result.getScore()).isEqualTo(85);
            assertThat(result.getFeedback()).isEqualTo("Clear explanation");
        }
    }

    @Nested
    @DisplayName("Update methods")
    class UpdateMethods {

        @Test
        @DisplayName("should update session status to completed")
        void shouldUpdateSessionStatusToCompleted() {
            InterviewSessionEntity session = buildSession("session-4");
            when(sessionRepository.findBySessionId("session-4")).thenReturn(Optional.of(session));

            interviewPersistenceService.updateSessionStatus("session-4", AsyncTaskStatus.COMPLETED, null);

            assertThat(session.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.COMPLETED);
            assertThat(session.getCompletedAt()).isNotNull();
            verify(sessionRepository).save(session);
        }

        @Test
        @DisplayName("should record evaluate error when session status failed")
        void shouldRecordEvaluateErrorWhenSessionStatusFailed() {
            InterviewSessionEntity session = buildSession("session-5");
            when(sessionRepository.findBySessionId("session-5")).thenReturn(Optional.of(session));

            interviewPersistenceService.updateSessionStatus(
                    "session-5", AsyncTaskStatus.FAILED, "evaluation timeout"
            );

            assertThat(session.getEvaluateError()).isEqualTo("evaluation timeout");
            assertThat(session.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.CREATED);
            verify(sessionRepository).save(session);
        }

        @Test
        @DisplayName("should update evaluate status to completed")
        void shouldUpdateEvaluateStatusToCompleted() {
            InterviewSessionEntity session = buildSession("session-6");
            when(sessionRepository.findBySessionId("session-6")).thenReturn(Optional.of(session));

            interviewPersistenceService.updateEvaluateStatus(
                    "session-6", AsyncTaskStatus.COMPLETED, null
            );

            assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
            assertThat(session.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.EVALUATED);
            verify(sessionRepository).save(session);
        }

        @Test
        @DisplayName("should update current question index and mark in progress")
        void shouldUpdateCurrentQuestionIndexAndMarkInProgress() {
            InterviewSessionEntity session = buildSession("session-7");
            session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
            when(sessionRepository.findBySessionId("session-7")).thenReturn(Optional.of(session));

            interviewPersistenceService.updateCurrentQuestionIndex("session-7", 2);

            assertThat(session.getCurrentQuestionIndex()).isEqualTo(2);
            assertThat(session.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            verify(sessionRepository).save(session);
        }
    }

    @Nested
    @DisplayName("Save report")
    class SaveReport {

        @Test
        @DisplayName("should save report and update answer details")
        void shouldSaveReportAndUpdateAnswers() throws Exception {
            InterviewSessionEntity session = buildSession("session-8");
            InterviewAnswerEntity answer = buildAnswer(session, 0);
            InterviewReportDTO report = buildReport("session-8");
            when(sessionRepository.findBySessionId("session-8")).thenReturn(Optional.of(session));
            when(answerRepository.findBySession_SessionIdAndQuestionIndex("session-8", 0))
                    .thenReturn(Optional.of(answer), Optional.of(answer));
            when(objectMapper.writeValueAsString(report.strengths())).thenReturn("[\"solid basics\"]");
            when(objectMapper.writeValueAsString(report.improvements())).thenReturn("[\"add examples\"]");
            when(objectMapper.writeValueAsString(report.referenceAnswers()))
                    .thenReturn("[{\"questionIndex\":0}]");
            when(objectMapper.writeValueAsString(List.of("covering index", "avoid table lookup")))
                    .thenReturn("[\"covering index\",\"avoid table lookup\"]");

            interviewPersistenceService.saveReport("session-8", report);

            assertThat(session.getOverallScore()).isEqualTo(90);
            assertThat(session.getOverallFeedback()).isEqualTo("Overall performance is good");
            assertThat(session.getStrengthsJson()).isEqualTo("[\"solid basics\"]");
            assertThat(session.getImprovementsJson()).isEqualTo("[\"add examples\"]");
            assertThat(session.getReferenceAnswersJson()).isEqualTo("[{\"questionIndex\":0}]");
            assertThat(answer.getScore()).isEqualTo(92);
            assertThat(answer.getFeedback()).isEqualTo("Explanation is clear");
            assertThat(answer.getReferenceAnswer()).isEqualTo(
                    "Use a covering index to avoid extra table lookups"
            );
            assertThat(answer.getKeyPointsJson())
                    .isEqualTo("[\"covering index\",\"avoid table lookup\"]");
            verify(answerRepository, times(2)).save(answer);
            verify(sessionRepository).save(session);
        }
    }

    @Nested
    @DisplayName("Query and delete")
    class QueryAndDelete {

        @Test
        @DisplayName("should find session by session id")
        void shouldFindSessionBySessionId() {
            InterviewSessionEntity session = buildSession("session-9");
            when(sessionRepository.findBySessionId("session-9")).thenReturn(Optional.of(session));

            Optional<InterviewSessionEntity> result =
                    interviewPersistenceService.findBySessionId("session-9");

            assertThat(result).contains(session);
        }

        @Test
        @DisplayName("should find sessions by resume id")
        void shouldFindSessionsByResumeId() {
            List<InterviewSessionEntity> sessions =
                    List.of(buildSession("session-10"), buildSession("session-11"));
            when(sessionRepository.findByResumeIdOrderByCreatedAtDesc(1L)).thenReturn(sessions);

            List<InterviewSessionEntity> result = interviewPersistenceService.findByResumeId(1L);

            assertThat(result).containsExactlyElementsOf(sessions);
        }

        @Test
        @DisplayName("should find all sessions")
        void shouldFindAllSessions() {
            List<InterviewSessionEntity> sessions = List.of(buildSession("session-12"));
            when(sessionRepository.findAllByOrderByCreatedAtDesc()).thenReturn(sessions);

            List<InterviewSessionEntity> result = interviewPersistenceService.findAll();

            assertThat(result).containsExactlyElementsOf(sessions);
        }

        @Test
        @DisplayName("should find unfinished session")
        void shouldFindUnfinishedSession() {
            InterviewSessionEntity session = buildSession("session-13");
            when(sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
                    1L,
                    List.of(
                            InterviewSessionEntity.SessionStatus.CREATED,
                            InterviewSessionEntity.SessionStatus.IN_PROGRESS
                    ))).thenReturn(Optional.of(session));

            Optional<InterviewSessionEntity> result =
                    interviewPersistenceService.findUnfinishedSession(1L);

            assertThat(result).contains(session);
        }

        @Test
        @DisplayName("should find answers by session id")
        void shouldFindAnswersBySessionId() {
            InterviewAnswerEntity answer = buildAnswer(buildSession("session-14"), 0);
            when(answerRepository.findBySession_SessionIdOrderByQuestionIndex("session-14"))
                    .thenReturn(List.of(answer));

            List<InterviewAnswerEntity> result =
                    interviewPersistenceService.findAnswersBySessionId("session-14");

            assertThat(result).containsExactly(answer);
        }

        @Test
        @DisplayName("should delete sessions by resume id")
        void shouldDeleteSessionsByResumeId() {
            List<InterviewSessionEntity> sessions =
                    List.of(buildSession("session-15"), buildSession("session-16"));
            when(sessionRepository.findByResumeIdOrderByCreatedAtDesc(1L)).thenReturn(sessions);

            interviewPersistenceService.deleteSessionsByResumeId(1L);

            verify(sessionRepository).deleteAll(sessions);
        }

        @Test
        @DisplayName("should delete session by session id")
        void shouldDeleteSessionBySessionId() {
            InterviewSessionEntity session = buildSession("session-17");
            when(sessionRepository.findBySessionId("session-17")).thenReturn(Optional.of(session));

            interviewPersistenceService.deleteSessionBySessionId("session-17");

            verify(sessionRepository).delete(session);
        }
    }

    @Nested
    @DisplayName("History questions")
    class HistoryQuestions {

        @Test
        @DisplayName("should load history questions by resume id")
        void shouldLoadHistoryQuestionsByResumeId() throws Exception {
            InterviewSessionEntity session = buildSession("session-18");
            List<InterviewQuestionDTO> expected = buildQuestions();
            mockHistoryRead(List.of(session), "history-json", expected);
            when(sessionRepository.findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(1L, "java"))
                    .thenReturn(List.of(session));

            List<InterviewQuestionDTO> result =
                    interviewPersistenceService.getHistoryQuestions("java", 1L);

            assertThat(result).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("should load history questions by skill id and ignore bad json")
        void shouldLoadHistoryQuestionsBySkillIdAndIgnoreInvalidJson() throws Exception {
            InterviewSessionEntity validSession = buildSession("session-19");
            InterviewSessionEntity invalidSession = buildSession("session-20");
            List<InterviewQuestionDTO> expected = buildQuestions();
            TypeFactory typeFactory = mock(TypeFactory.class);
            CollectionType collectionType = mock(CollectionType.class);
            validSession.setQuestionsJson("history-json");
            invalidSession.setQuestionsJson("broken-json");
            when(sessionRepository.findTop10BySkillIdOrderByCreatedAtDesc("java"))
                    .thenReturn(List.of(validSession, invalidSession));
            when(objectMapper.getTypeFactory()).thenReturn(typeFactory);
            when(typeFactory.constructCollectionType(List.class, InterviewQuestionDTO.class))
                    .thenReturn(collectionType);
            when(objectMapper.readValue("history-json", collectionType)).thenReturn(expected);
            when(objectMapper.readValue("broken-json", collectionType))
                    .thenThrow(new RuntimeException("bad json"));

            List<InterviewQuestionDTO> result =
                    interviewPersistenceService.getHistoryQuestions("java", null);

            assertThat(result).containsExactlyElementsOf(expected);
        }
    }

    private void mockHistoryRead(List<InterviewSessionEntity> sessions, String json,
                                 List<InterviewQuestionDTO> questions) throws Exception {
        TypeFactory typeFactory = mock(TypeFactory.class);
        CollectionType collectionType = mock(CollectionType.class);
        sessions.forEach(session -> session.setQuestionsJson(json));
        when(objectMapper.getTypeFactory()).thenReturn(typeFactory);
        when(typeFactory.constructCollectionType(List.class, InterviewQuestionDTO.class))
                .thenReturn(collectionType);
        when(objectMapper.readValue(json, collectionType)).thenReturn(questions);
    }

    private List<InterviewQuestionDTO> buildQuestions() {
        return List.of(
                InterviewQuestionDTO.create(0, "What is a covering index", "MYSQL", "MySQL"),
                InterviewQuestionDTO.create(1, "Explain isolation levels", "MYSQL", "MySQL")
        );
    }

    private InterviewReportDTO buildReport(String sessionId) {
        return new InterviewReportDTO(
                sessionId,
                1,
                90,
                List.of(new InterviewReportDTO.CategoryScore("MySQL", 90, 1)),
                List.of(new InterviewReportDTO.QuestionEvaluation(
                        0,
                        "What is a covering index",
                        "MySQL",
                        "It avoids extra table lookups",
                        92,
                        "Explanation is clear"
                )),
                "Overall performance is good",
                List.of("solid basics"),
                List.of("add examples"),
                List.of(new InterviewReportDTO.ReferenceAnswer(
                        0,
                        "What is a covering index",
                        "Use a covering index to avoid extra table lookups",
                        List.of("covering index", "avoid table lookup")
                ))
        );
    }

    private InterviewSessionEntity buildSession(String sessionId) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
        session.setCurrentQuestionIndex(0);
        return session;
    }

    private InterviewAnswerEntity buildAnswer(InterviewSessionEntity session, int questionIndex) {
        InterviewAnswerEntity answer = new InterviewAnswerEntity();
        answer.setSession(session);
        answer.setQuestionIndex(questionIndex);
        return answer;
    }

    private ResumeEntity buildResume(Long id) {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(id);
        return resume;
    }
}
