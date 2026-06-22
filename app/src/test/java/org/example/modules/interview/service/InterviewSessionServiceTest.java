package org.example.modules.interview.service;

import org.example.common.ai.LlmProviderRegistry;
import org.example.common.exception.BusinessException;
import org.example.common.model.AsyncTaskStatus;
import org.example.infrastructure.redis.InterviewSessionCache;
import org.example.modules.interview.listener.EvaluateStreamProducer;
import org.example.modules.interview.model.CreateInterviewRequest;
import org.example.modules.interview.model.HistoricalQuestion;
import org.example.modules.interview.model.InterviewAnswerEntity;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewReportDTO;
import org.example.modules.interview.model.InterviewSessionDTO;
import org.example.modules.interview.model.InterviewSessionEntity;
import org.example.modules.interview.model.SubmitAnswerRequest;
import org.example.modules.interview.model.SubmitAnswerResponse;
import org.example.modules.interview.skill.InterviewSkillService.CategoryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试会话管理服务测试")
class InterviewSessionServiceTest {

    @Mock
    private InterviewQuestionService questionService;

    @Mock
    private AnswerEvaluationService evaluationService;

    @Mock
    private InterviewPersistenceService persistenceService;

    @Mock
    private InterviewSessionCache sessionCache;

    @Mock
    private EvaluateStreamProducer evaluateStreamProducer;

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InterviewSessionService interviewSessionService;

    @BeforeEach
    void setUp() {
        interviewSessionService = new InterviewSessionService(
                questionService,
                evaluationService,
                persistenceService,
                sessionCache,
                objectMapper,
                evaluateStreamProducer,
                llmProviderRegistry
        );
    }

    @Nested
    @DisplayName("创建会话")
    class CreateSession {

        @Test
        @DisplayName("存在未完成会话且未强制创建时应直接复用")
        void shouldReuseUnfinishedSessionWhenForceCreateDisabled() {
            CreateInterviewRequest request = buildCreateRequest(1L, false, "简历内容");
            List<InterviewQuestionDTO> questions = buildQuestions();
            InterviewSessionDTO unfinished = new InterviewSessionDTO(
                    "session-existing",
                    "简历内容",
                    2,
                    1,
                    questions,
                    InterviewSessionDTO.SessionStatus.IN_PROGRESS
            );
            when(sessionCache.findUnfinishedSessionId(1L)).thenReturn(Optional.of("session-existing"));
            when(sessionCache.getSession("session-existing")).thenReturn(Optional.of(
                    buildCachedSession(
                            "session-existing",
                            "简历内容",
                            1L,
                            questions,
                            1,
                            InterviewSessionDTO.SessionStatus.IN_PROGRESS
                    )
            ));

            InterviewSessionDTO result = interviewSessionService.createSession(request);

            assertThat(result).isEqualTo(unfinished);
            verify(questionService, never()).generateQuestionsBySkills(
                    any(), any(), any(), any(), anyInt(), any(), any(), any()
            );
            verify(sessionCache).refreshSessionTTL("session-existing");
        }

        @Test
        @DisplayName("不存在未完成会话时应生成题目并创建新会话")
        void shouldCreateFreshSessionWhenNoUnfinishedSessionExists() {
            CreateInterviewRequest request = buildCreateRequest(1L, false, " 五年后端经验 ");
            List<InterviewQuestionDTO> historyQuestions = List.of(
                    new InterviewQuestionDTO(
                            8,
                            "历史题",
                            "JAVA",
                            "Java",
                            "JVM调优",
                            null,
                            null,
                            null,
                            false,
                            null
                    )
            );
            List<InterviewQuestionDTO> generatedQuestions = buildQuestions();
            when(sessionCache.findUnfinishedSessionId(1L)).thenReturn(Optional.empty());
            when(persistenceService.findUnfinishedSession(1L)).thenReturn(Optional.empty());
            when(llmProviderRegistry.getChatClientOrDefault("dashscope")).thenReturn(chatClient);
            when(persistenceService.getHistoryQuestions("java-backend", 1L)).thenReturn(historyQuestions);
            when(questionService.generateQuestionsBySkills(
                    eq(chatClient),
                    eq("java-backend"),
                    eq("mid"),
                    eq("五年后端经验"),
                    eq(2),
                    eq(List.of(new HistoricalQuestion("历史题", "JAVA", "JVM调优"))),
                    eq(List.<CategoryDTO>of()),
                    eq("JD文本")
            )).thenReturn(generatedQuestions);

            InterviewSessionDTO result = interviewSessionService.createSession(request);

            assertThat(result.sessionId()).isNotBlank();
            assertThat(result.resumeText()).isEqualTo("五年后端经验");
            assertThat(result.totalQuestions()).isEqualTo(2);
            assertThat(result.currentQuestionIndex()).isZero();
            assertThat(result.questions()).containsExactlyElementsOf(generatedQuestions);
            assertThat(result.status()).isEqualTo(InterviewSessionDTO.SessionStatus.CREATED);

            ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(persistenceService).saveNewSession(
                    sessionIdCaptor.capture(),
                    eq(1L),
                    eq(2),
                    eq(generatedQuestions),
                    eq("dashscope"),
                    eq("java-backend"),
                    eq("mid")
            );
            verify(sessionCache).saveSession(
                    eq(sessionIdCaptor.getValue()),
                    eq("五年后端经验"),
                    eq(1L),
                    eq(generatedQuestions),
                    eq(0),
                    eq(InterviewSessionDTO.SessionStatus.CREATED)
            );
        }
    }

    @Nested
    @DisplayName("会话恢复")
    class SessionRecovery {

        @Test
        @DisplayName("缓存命中时应直接返回会话信息")
        void shouldReturnSessionFromCache() {
            List<InterviewQuestionDTO> questions = buildQuestions();
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1",
                    "简历内容",
                    1L,
                    questions,
                    1,
                    InterviewSessionDTO.SessionStatus.IN_PROGRESS
            );
            when(sessionCache.getSession("session-1")).thenReturn(Optional.of(cachedSession));

            InterviewSessionDTO result = interviewSessionService.getSession("session-1");

            assertThat(result.sessionId()).isEqualTo("session-1");
            assertThat(result.currentQuestionIndex()).isEqualTo(1);
            assertThat(result.questions()).containsExactlyElementsOf(questions);
            assertThat(result.status()).isEqualTo(InterviewSessionDTO.SessionStatus.IN_PROGRESS);
            verify(sessionCache).refreshSessionTTL("session-1");
        }

        @Test
        @DisplayName("缓存未命中时应从数据库恢复并写回缓存")
        void shouldRestoreSessionFromDatabaseWhenCacheMissed() {
            List<InterviewQuestionDTO> questions = buildQuestions();
            List<InterviewAnswerEntity> answers = List.of(buildAnswer(1, "我负责交易链路设计"));
            List<InterviewQuestionDTO> hydratedQuestions = List.of(
                    questions.get(0).withAnswer("我负责交易链路设计"),
                    questions.get(1)
            );
            InterviewSessionEntity sessionEntity = buildSessionEntity(
                    "session-db",
                    1L,
                    questions,
                    1,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS,
                    "dashscope"
            );
            InterviewSessionCache.CachedSession restored = buildCachedSession(
                    "session-db",
                    "",
                    1L,
                    hydratedQuestions,
                    1,
                    InterviewSessionDTO.SessionStatus.IN_PROGRESS
            );
            when(sessionCache.getSession("session-db")).thenReturn(Optional.empty(), Optional.of(restored));
            when(persistenceService.findBySessionId("session-db")).thenReturn(Optional.of(sessionEntity));
            when(persistenceService.findAnswersBySessionId("session-db")).thenReturn(answers);

            InterviewSessionDTO result = interviewSessionService.getSession("session-db");

            assertThat(result.sessionId()).isEqualTo("session-db");
            assertThat(result.questions().get(0).userAnswer()).isEqualTo("我负责交易链路设计");
            verify(sessionCache).saveSession(
                    eq("session-db"),
                    eq(""),
                    eq(1L),
                    eq(hydratedQuestions),
                    eq(1),
                    eq(InterviewSessionDTO.SessionStatus.IN_PROGRESS)
            );
            verify(sessionCache).refreshSessionTTL("session-db");
        }
    }

    @Nested
    @DisplayName("答题流程")
    class SubmitAnswerFlow {

        @Test
        @DisplayName("提交非最后一题答案时应保存答案并推进到下一题")
        void shouldSubmitAnswerAndMoveToNextQuestion() {
            List<InterviewQuestionDTO> questions = buildQuestions();
            List<InterviewQuestionDTO> updatedQuestions = List.of(
                    questions.get(0).withAnswer("我负责交易链路设计"),
                    questions.get(1)
            );
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-2",
                    "简历内容",
                    1L,
                    questions,
                    0,
                    InterviewSessionDTO.SessionStatus.IN_PROGRESS
            );
            when(sessionCache.getSession("session-2")).thenReturn(Optional.of(cachedSession));

            SubmitAnswerResponse result = interviewSessionService.submitAnswer(
                    new SubmitAnswerRequest("session-2", 0, " 我负责交易链路设计 ")
            );

            assertThat(result.hasNextQuestion()).isTrue();
            assertThat(result.currentIndex()).isEqualTo(1);
            assertThat(result.totalQuestions()).isEqualTo(2);
            assertThat(result.nextQuestion()).isEqualTo(questions.get(1));
            verify(sessionCache).updateQuestions(eq("session-2"), eq(updatedQuestions));
            verify(persistenceService).saveAnswer(
                    "session-2",
                    1,
                    "请介绍你做过的核心项目",
                    "项目经历",
                    "我负责交易链路设计",
                    0,
                    null
            );
            verify(sessionCache).updateCurrentIndex("session-2", 1);
            verify(sessionCache).updateSessionStatus("session-2", InterviewSessionDTO.SessionStatus.IN_PROGRESS);
            verify(persistenceService).updateCurrentQuestionIndex("session-2", 1);
            verify(sessionCache).refreshSessionTTL("session-2");
        }

        @Test
        @DisplayName("提交最后一题答案时应自动交卷并发送评估任务")
        void shouldCompleteInterviewWhenSubmittingLastAnswer() {
            List<InterviewQuestionDTO> questions = buildQuestions();
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-last",
                    "简历内容",
                    1L,
                    questions,
                    1,
                    InterviewSessionDTO.SessionStatus.IN_PROGRESS
            );
            when(sessionCache.getSession("session-last")).thenReturn(Optional.of(cachedSession));

            SubmitAnswerResponse result = interviewSessionService.submitAnswer(
                    new SubmitAnswerRequest("session-last", 1, "知道索引覆盖和最左匹配原则")
            );

            assertThat(result.hasNextQuestion()).isFalse();
            assertThat(result.nextQuestion()).isNull();
            assertThat(result.currentIndex()).isEqualTo(2);
            assertThat(result.totalQuestions()).isEqualTo(2);
            verify(sessionCache).updateCurrentIndex("session-last", 2);
            verify(sessionCache).updateSessionStatus("session-last", InterviewSessionDTO.SessionStatus.COMPLETED);
            verify(persistenceService).updateCurrentQuestionIndex("session-last", 2);
            verify(persistenceService).updateSessionStatus("session-last", AsyncTaskStatus.COMPLETED, null);
            verify(persistenceService).updateEvaluateStatus("session-last", AsyncTaskStatus.PENDING, null);
            verify(evaluateStreamProducer).sendEvaluateTask("session-last");
        }

        @Test
        @DisplayName("暂存答案时应只更新缓存问题列表")
        void shouldSaveAnswerWithoutMovingToNextQuestion() {
            List<InterviewQuestionDTO> questions = buildQuestions();
            List<InterviewQuestionDTO> updatedQuestions = List.of(
                    questions.get(0).withAnswer("暂存答案"),
                    questions.get(1)
            );
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-save",
                    "简历内容",
                    1L,
                    questions,
                    0,
                    InterviewSessionDTO.SessionStatus.IN_PROGRESS
            );
            when(sessionCache.getSession("session-save")).thenReturn(Optional.of(cachedSession));

            interviewSessionService.saveAnswer(new SubmitAnswerRequest("session-save", 0, "暂存答案"));

            verify(sessionCache).updateQuestions(eq("session-save"), eq(updatedQuestions));
            verify(sessionCache, never()).updateCurrentIndex(any(), anyInt());
            verify(persistenceService, never()).saveAnswer(any(), anyInt(), any(), any(), any(), anyInt(), any());
            verify(sessionCache).refreshSessionTTL("session-save");
        }

        @Test
        @DisplayName("问题索引不匹配时应抛出业务异常")
        void shouldThrowBusinessExceptionWhenQuestionIndexMismatched() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-mismatch",
                    "简历内容",
                    1L,
                    buildQuestions(),
                    1,
                    InterviewSessionDTO.SessionStatus.IN_PROGRESS
            );
            when(sessionCache.getSession("session-mismatch")).thenReturn(Optional.of(cachedSession));

            assertThatThrownBy(() -> interviewSessionService.submitAnswer(
                    new SubmitAnswerRequest("session-mismatch", 0, "错误索引答案")
            )).isInstanceOf(BusinessException.class)
                    .hasMessage("问题索引不匹配，当前应答索引为: 1");
        }
    }

    @Nested
    @DisplayName("报告生成")
    class GenerateReport {

        @Test
        @DisplayName("应加载答案 调用评估服务并更新评估状态")
        void shouldGenerateReportAndUpdateEvaluateStatus() {
            List<InterviewQuestionDTO> questions = buildQuestions();
            List<InterviewQuestionDTO> hydratedQuestions = List.of(
                    questions.get(0).withAnswer("我负责交易链路设计"),
                    questions.get(1).withAnswer("知道索引覆盖和最左匹配原则")
            );
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-report",
                    "简历内容",
                    1L,
                    questions,
                    2,
                    InterviewSessionDTO.SessionStatus.COMPLETED
            );
            InterviewSessionEntity sessionEntity = buildSessionEntity(
                    "session-report",
                    1L,
                    questions,
                    2,
                    InterviewSessionEntity.SessionStatus.COMPLETED,
                    "dashscope"
            );
            List<InterviewAnswerEntity> answers = List.of(
                    buildAnswer(1, "我负责交易链路设计"),
                    buildAnswer(2, "知道索引覆盖和最左匹配原则")
            );
            InterviewReportDTO report = new InterviewReportDTO(
                    "session-report",
                    2,
                    88,
                    List.of(),
                    List.of(),
                    "整体表现良好",
                    List.of("项目经验较好"),
                    List.of("建议补充更多细节"),
                    List.of()
            );
            when(sessionCache.getSession("session-report")).thenReturn(Optional.of(cachedSession));
            when(persistenceService.findBySessionId("session-report")).thenReturn(Optional.of(sessionEntity));
            when(persistenceService.findAnswersBySessionId("session-report")).thenReturn(answers);
            when(llmProviderRegistry.getChatClientOrDefault("dashscope")).thenReturn(chatClient);
            when(evaluationService.evaluateInterview(
                    eq(chatClient),
                    eq("session-report"),
                    eq("简历内容"),
                    eq(hydratedQuestions)
            )).thenReturn(report);

            InterviewReportDTO result = interviewSessionService.generateReport("session-report");

            assertThat(result).isEqualTo(report);
            verify(sessionCache).updateQuestions(eq("session-report"), eq(hydratedQuestions));
            verify(sessionCache).updateSessionStatus("session-report", InterviewSessionDTO.SessionStatus.EVALUATED);
            verify(persistenceService).updateEvaluateStatus("session-report", AsyncTaskStatus.COMPLETED, null);
            verify(sessionCache).refreshSessionTTL("session-report");
        }

        @Test
        @DisplayName("会话未完成时生成报告应抛出业务异常")
        void shouldThrowBusinessExceptionWhenInterviewNotCompleted() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-pending",
                    "简历内容",
                    1L,
                    buildQuestions(),
                    1,
                    InterviewSessionDTO.SessionStatus.IN_PROGRESS
            );
            when(sessionCache.getSession("session-pending")).thenReturn(Optional.of(cachedSession));

            assertThatThrownBy(() -> interviewSessionService.generateReport("session-pending"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("面试尚未完成，无法生成报告");

            verifyNoInteractions(evaluationService);
        }
    }

    private CreateInterviewRequest buildCreateRequest(Long resumeId, Boolean forceCreate, String resumeText) {
        return new CreateInterviewRequest(
                resumeText,
                2,
                resumeId,
                forceCreate,
                "dashscope",
                "java-backend",
                "mid",
                List.of(),
                "JD文本"
        );
    }

    private InterviewSessionCache.CachedSession buildCachedSession(
            String sessionId,
            String resumeText,
            Long resumeId,
            List<InterviewQuestionDTO> questions,
            int currentIndex,
            InterviewSessionDTO.SessionStatus status
    ) {
        return new InterviewSessionCache.CachedSession(
                sessionId,
                resumeText,
                resumeId,
                questions,
                currentIndex,
                status,
                objectMapper
        );
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

    private InterviewSessionEntity buildSessionEntity(
            String sessionId,
            Long resumeId,
            List<InterviewQuestionDTO> questions,
            Integer currentQuestionIndex,
            InterviewSessionEntity.SessionStatus status,
            String llmProvider
    ) {
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setSessionId(sessionId);
        entity.setCurrentQuestionIndex(currentQuestionIndex);
        entity.setStatus(status);
        entity.setLlmProvider(llmProvider);
        entity.setTotalQuestions(questions.size());
        writeQuestionsJson(entity, questions);
        setResumeId(entity, resumeId);
        return entity;
    }

    private void writeQuestionsJson(InterviewSessionEntity entity, List<InterviewQuestionDTO> questions) {
        try {
            entity.setQuestionsJson(objectMapper.writeValueAsString(questions));
        } catch (Exception e) {
            throw new IllegalStateException("序列化测试问题失败", e);
        }
    }

    private void setResumeId(InterviewSessionEntity entity, Long resumeId) {
        if (resumeId == null) {
            return;
        }
        try {
            var field = InterviewSessionEntity.class.getDeclaredField("resumeId");
            field.setAccessible(true);
            field.set(entity, resumeId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("设置 resumeId 失败", e);
        }
    }

    private InterviewAnswerEntity buildAnswer(int questionIndex, String userAnswer) {
        InterviewAnswerEntity answer = new InterviewAnswerEntity();
        answer.setQuestionIndex(questionIndex);
        answer.setUserAnswer(userAnswer);
        answer.setFeedback(null);
        answer.setScore(null);
        return answer;
    }
}
