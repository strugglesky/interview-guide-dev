package org.example.modules.interview.service;

import org.example.common.exception.BusinessException;
import org.example.infrastructure.export.PdfExportService;
import org.example.infrastructure.mapper.InterviewMapper;
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewDetailDTO;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试历史服务测试")
public class InterviewHistoryServiceTest {

    @Mock
    private InterviewPersistenceService interviewPersistenceService;

    @Mock
    private PdfExportService pdfExportService;

    @Mock
    private InterviewMapper interviewMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InterviewHistoryService interviewHistoryService;

    @BeforeEach
    void setUp() {
        interviewHistoryService = new InterviewHistoryService(
                interviewPersistenceService,
                pdfExportService,
                objectMapper,
                interviewMapper
        );
    }

    @Nested
    @DisplayName("获取面试详情")
    class GetInterviewDetail {

        @Test
        @DisplayName("应加载会话 答案并组装详情数据")
        void shouldLoadSessionAnswersAndAssembleInterviewDetail() throws Exception {
            InterviewSessionEntity session = buildSessionEntity("session-1");
            session.setQuestionsJson(objectMapper.writeValueAsString(buildQuestions()));
            session.setStrengthsJson(objectMapper.writeValueAsString(List.of("项目经验扎实", "基础知识较好")));
            session.setImprovementsJson(objectMapper.writeValueAsString(List.of("补充压测细节")));
            session.setReferenceAnswersJson(objectMapper.writeValueAsString(List.of(
                    java.util.Map.of("questionIndex", 1, "referenceAnswer", "说明职责边界")
            )));
            List<InterviewAnswerEntity> answers = List.of(
                    buildAnswer(
                            1L,
                            1,
                            "请介绍你做过的核心项目",
                            "项目经历",
                            "我负责交易链路设计",
                            86,
                            "回答结构清晰",
                            "说明职责边界",
                            objectMapper.writeValueAsString(List.of("背景", "职责", "结果"))
                    )
            );
            when(interviewPersistenceService.findBySessionId("session-1")).thenReturn(Optional.of(session));
            when(interviewPersistenceService.findAnswersBySessionId("session-1")).thenReturn(answers);
            mockMapperAssembly();

            InterviewDetailDTO result = interviewHistoryService.getInterviewDetail("session-1");

            assertThat(result.sessionId()).isEqualTo("session-1");
            assertThat(result.totalQuestions()).isEqualTo(2);
            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(result.evaluateStatus()).isEqualTo("COMPLETED");
            assertThat(result.questions()).hasSize(2);
            assertThat(result.strengths()).containsExactly("项目经验扎实", "基础知识较好");
            assertThat(result.improvements()).containsExactly("补充压测细节");
            assertThat(result.referenceAnswers()).hasSize(1);
            assertThat(result.answers()).hasSize(1);
            assertThat(result.answers().get(0).keyPoints()).containsExactly("背景", "职责", "结果");
            verify(interviewPersistenceService).findBySessionId("session-1");
            verify(interviewPersistenceService).findAnswersBySessionId("session-1");
            verify(interviewMapper).toAnswerDetailDTOList(eq(answers), any());
            verify(interviewMapper).toDetailDTO(
                    eq(session),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            );
        }

        @Test
        @DisplayName("可选 JSON 为空时应回退为空列表")
        void shouldFallbackToEmptyListsWhenOptionalJsonIsBlank() throws Exception {
            InterviewSessionEntity session = buildSessionEntity("session-empty");
            session.setQuestionsJson(objectMapper.writeValueAsString(buildQuestions()));
            session.setStrengthsJson(null);
            session.setImprovementsJson(" ");
            session.setReferenceAnswersJson(null);
            List<InterviewAnswerEntity> answers = List.of(
                    buildAnswer(
                            2L,
                            2,
                            "MySQL 索引失效有哪些常见场景",
                            "MySQL",
                            "函数操作会导致索引失效",
                            null,
                            null,
                            null,
                            null
                    )
            );
            when(interviewPersistenceService.findBySessionId("session-empty")).thenReturn(Optional.of(session));
            when(interviewPersistenceService.findAnswersBySessionId("session-empty")).thenReturn(answers);
            mockMapperAssembly();

            InterviewDetailDTO result = interviewHistoryService.getInterviewDetail("session-empty");

            assertThat(result.strengths()).isEmpty();
            assertThat(result.improvements()).isEmpty();
            assertThat(result.referenceAnswers()).isEmpty();
            assertThat(result.answers()).singleElement()
                    .extracting(InterviewDetailDTO.AnswerDetailDTO::keyPoints)
                    .isEqualTo(List.of());
        }

        @Test
        @DisplayName("会话不存在时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenSessionNotFound() {
            when(interviewPersistenceService.findBySessionId("missing-session")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> interviewHistoryService.getInterviewDetail("missing-session"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("面试会话不存在: missing-session");

            verify(interviewPersistenceService).findBySessionId("missing-session");
            verifyNoInteractions(interviewMapper);
        }

        @Test
        @DisplayName("会话ID为空时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenSessionIdIsBlank() {
            assertThatThrownBy(() -> interviewHistoryService.getInterviewDetail(" "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("面试会话ID不能为空");

            verifyNoInteractions(interviewPersistenceService, interviewMapper);
        }

        @Test
        @DisplayName("问题列表 JSON 非法时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenQuestionsJsonIsInvalid() {
            InterviewSessionEntity session = buildSessionEntity("session-bad-questions");
            session.setQuestionsJson("not-json");
            when(interviewPersistenceService.findBySessionId("session-bad-questions"))
                    .thenReturn(Optional.of(session));
            when(interviewPersistenceService.findAnswersBySessionId("session-bad-questions"))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> interviewHistoryService.getInterviewDetail("session-bad-questions"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("反序列化面试详情数据失败");
        }

        @Test
        @DisplayName("答案关键点 JSON 非法时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenAnswerKeyPointsJsonIsInvalid() throws Exception {
            InterviewSessionEntity session = buildSessionEntity("session-bad-keypoints");
            session.setQuestionsJson(objectMapper.writeValueAsString(buildQuestions()));
            List<InterviewAnswerEntity> answers = List.of(
                    buildAnswer(
                            3L,
                            1,
                            "请介绍你做过的核心项目",
                            "项目经历",
                            "我负责交易链路设计",
                            80,
                            "回答较完整",
                            "说明职责边界",
                            "bad-json"
                    )
            );
            when(interviewPersistenceService.findBySessionId("session-bad-keypoints"))
                    .thenReturn(Optional.of(session));
            when(interviewPersistenceService.findAnswersBySessionId("session-bad-keypoints"))
                    .thenReturn(answers);
            when(interviewMapper.toAnswerDetailDTOList(eq(answers), any())).thenAnswer(invocation -> {
                Function<InterviewAnswerEntity, List<String>> extractor = invocation.getArgument(1);
                return answers.stream()
                        .map(answer -> new InterviewDetailDTO.AnswerDetailDTO(
                                answer.getQuestionIndex(),
                                answer.getQuestion(),
                                answer.getCategory(),
                                answer.getUserAnswer(),
                                answer.getScore(),
                                answer.getFeedback(),
                                answer.getReferenceAnswer(),
                                extractor.apply(answer),
                                answer.getAnsweredAt()
                        ))
                        .toList();
            });

            assertThatThrownBy(() -> interviewHistoryService.getInterviewDetail("session-bad-keypoints"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("反序列化答案关键点失败");
        }
    }

    private void mockMapperAssembly() {
        when(interviewMapper.toAnswerDetailDTOList(any(), any())).thenAnswer(invocation -> {
            List<InterviewAnswerEntity> entities = invocation.getArgument(0);
            Function<InterviewAnswerEntity, List<String>> extractor = invocation.getArgument(1);
            return entities.stream()
                    .map(answer -> new InterviewDetailDTO.AnswerDetailDTO(
                            answer.getQuestionIndex(),
                            answer.getQuestion(),
                            answer.getCategory(),
                            answer.getUserAnswer(),
                            answer.getScore(),
                            answer.getFeedback(),
                            answer.getReferenceAnswer(),
                            extractor.apply(answer),
                            answer.getAnsweredAt()
                    ))
                    .toList();
        });
        when(interviewMapper.toDetailDTO(any(), any(), any(), any(), any(), any())).thenAnswer(invocation ->
                new InterviewDetailDTO(
                        ((InterviewSessionEntity) invocation.getArgument(0)).getId(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getSessionId(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getTotalQuestions(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getStatus().toString(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getEvaluateStatus().name(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getEvaluateError(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getOverallScore(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getOverallFeedback(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getCreatedAt(),
                        ((InterviewSessionEntity) invocation.getArgument(0)).getCompletedAt(),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5)
                )
        );
    }

    private InterviewSessionEntity buildSessionEntity(String sessionId) {
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setId(10L);
        entity.setSessionId(sessionId);
        entity.setTotalQuestions(2);
        entity.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
        entity.setEvaluateStatus(org.example.common.model.AsyncTaskStatus.COMPLETED);
        entity.setEvaluateError(null);
        entity.setOverallScore(88);
        entity.setOverallFeedback("整体表现良好");
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 20, 10, 0));
        entity.setCompletedAt(LocalDateTime.of(2026, 6, 20, 10, 30));
        return entity;
    }

    private List<InterviewQuestionDTO> buildQuestions() {
        return List.of(
                InterviewQuestionDTO.create(
                        1,
                        "请介绍你做过的核心项目",
                        "PROJECT",
                        "项目经历",
                        "项目职责",
                        false,
                        null
                ),
                InterviewQuestionDTO.create(
                        2,
                        "MySQL 索引失效有哪些常见场景",
                        "MYSQL",
                        "MySQL",
                        "索引失效场景",
                        false,
                        null
                )
        );
    }

    private InterviewAnswerEntity buildAnswer(
            Long id,
            Integer questionIndex,
            String question,
            String category,
            String userAnswer,
            Integer score,
            String feedback,
            String referenceAnswer,
            String keyPointsJson
    ) {
        InterviewAnswerEntity answer = new InterviewAnswerEntity();
        answer.setId(id);
        answer.setQuestionIndex(questionIndex);
        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);
        answer.setReferenceAnswer(referenceAnswer);
        answer.setKeyPointsJson(keyPointsJson);
        answer.setAnsweredAt(LocalDateTime.of(2026, 6, 20, 10, 15));
        return answer;
    }
}
