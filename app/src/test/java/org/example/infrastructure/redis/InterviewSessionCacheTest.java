package org.example.infrastructure.redis;

import org.example.common.exception.BusinessException;
import org.example.modules.interview.model.InterviewQuestionDTO;
import org.example.modules.interview.model.InterviewSessionDTO.SessionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试会话缓存测试")
class InterviewSessionCacheTest {

    private static final String SESSION_KEY = "interview:session:session-1";
    private static final String RESUME_KEY = "interview:resume:100";
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    @Mock
    private RedisService redisService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private InterviewSessionCache interviewSessionCache;

    @Nested
    @DisplayName("保存与读取")
    class SaveAndGet {

        @Test
        @DisplayName("应保存会话及简历映射")
        void shouldSaveSessionAndResumeMapping() throws Exception {
            List<InterviewQuestionDTO> questions = buildQuestions();
            when(objectMapper.writeValueAsString(questions)).thenReturn("[{\"questionIndex\":0}]");

            interviewSessionCache.saveSession(
                    "session-1",
                    "简历内容",
                    100L,
                    questions,
                    1,
                    SessionStatus.IN_PROGRESS
            );

            ArgumentCaptor<InterviewSessionCache.CachedSession> sessionCaptor =
                    ArgumentCaptor.forClass(InterviewSessionCache.CachedSession.class);
            verify(redisService).set(eq(SESSION_KEY), sessionCaptor.capture(), eq(SESSION_TTL));
            verify(redisService).set(RESUME_KEY, "session-1", SESSION_TTL);
            InterviewSessionCache.CachedSession cachedSession = sessionCaptor.getValue();
            assertThat(cachedSession.getSessionId()).isEqualTo("session-1");
            assertThat(cachedSession.getResumeText()).isEqualTo("简历内容");
            assertThat(cachedSession.getResumeId()).isEqualTo(100L);
            assertThat(cachedSession.getCurrentIndex()).isEqualTo(1);
            assertThat(cachedSession.getStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
            assertThat(cachedSession.getQuestionsJson()).isEqualTo("[{\"questionIndex\":0}]");
        }

        @Test
        @DisplayName("获取缓存会话时应返回Optional")
        void shouldReturnCachedSession() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1", 100L, 0, SessionStatus.CREATED, "[{\"questionIndex\":0}]"
            );
            when(redisService.get(SESSION_KEY)).thenReturn(cachedSession);

            Optional<InterviewSessionCache.CachedSession> result =
                    interviewSessionCache.getSession("session-1");

            assertThat(result).contains(cachedSession);
        }
    }

    @Nested
    @DisplayName("局部更新")
    class UpdateOperations {

        @Test
        @DisplayName("应更新会话状态并刷新TTL")
        void shouldUpdateSessionStatus() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1", 100L, 0, SessionStatus.CREATED, "questions-json"
            );
            when(redisService.get(SESSION_KEY)).thenReturn(cachedSession);

            interviewSessionCache.updateSessionStatus("session-1", SessionStatus.COMPLETED);

            ArgumentCaptor<InterviewSessionCache.CachedSession> sessionCaptor =
                    ArgumentCaptor.forClass(InterviewSessionCache.CachedSession.class);
            verify(redisService).set(eq(SESSION_KEY), sessionCaptor.capture(), eq(SESSION_TTL));
            verify(redisService).set(RESUME_KEY, "session-1", SESSION_TTL);
            assertThat(sessionCaptor.getValue().getStatus()).isEqualTo(SessionStatus.COMPLETED);
        }

        @Test
        @DisplayName("应更新当前问题索引")
        void shouldUpdateCurrentIndex() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1", 100L, 0, SessionStatus.IN_PROGRESS, "questions-json"
            );
            when(redisService.get(SESSION_KEY)).thenReturn(cachedSession);

            interviewSessionCache.updateCurrentIndex("session-1", 3);

            ArgumentCaptor<InterviewSessionCache.CachedSession> sessionCaptor =
                    ArgumentCaptor.forClass(InterviewSessionCache.CachedSession.class);
            verify(redisService).set(eq(SESSION_KEY), sessionCaptor.capture(), eq(SESSION_TTL));
            assertThat(sessionCaptor.getValue().getCurrentIndex()).isEqualTo(3);
        }

        @Test
        @DisplayName("应更新问题列表")
        void shouldUpdateQuestions() throws Exception {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1", 100L, 1, SessionStatus.IN_PROGRESS, "old-json"
            );
            List<InterviewQuestionDTO> updatedQuestions = List.of(
                    buildQuestion(0, "什么是索引", "MYSQL", "MySQL").withAnswer("用于加速查询")
            );
            when(redisService.get(SESSION_KEY)).thenReturn(cachedSession);
            when(objectMapper.writeValueAsString(updatedQuestions)).thenReturn("new-json");

            interviewSessionCache.updateQuestions("session-1", updatedQuestions);

            ArgumentCaptor<InterviewSessionCache.CachedSession> sessionCaptor =
                    ArgumentCaptor.forClass(InterviewSessionCache.CachedSession.class);
            verify(redisService).set(eq(SESSION_KEY), sessionCaptor.capture(), eq(SESSION_TTL));
            assertThat(sessionCaptor.getValue().getQuestionsJson()).isEqualTo("new-json");
        }
    }

    @Nested
    @DisplayName("删除与查找")
    class DeleteAndFind {

        @Test
        @DisplayName("删除会话时应同时删除简历映射")
        void shouldDeleteSessionAndResumeMapping() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1", 100L, 0, SessionStatus.CREATED, "questions-json"
            );
            when(redisService.get(SESSION_KEY)).thenReturn(cachedSession);

            interviewSessionCache.deleteSession("session-1");

            verify(redisService).delete(SESSION_KEY);
            verify(redisService).delete(RESUME_KEY);
        }

        @Test
        @DisplayName("根据简历ID查找未完成会话时应返回会话ID")
        void shouldFindUnfinishedSessionId() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1", 100L, 0, SessionStatus.IN_PROGRESS, "questions-json"
            );
            when(redisService.get(RESUME_KEY)).thenReturn("session-1");
            when(redisService.get(SESSION_KEY)).thenReturn(cachedSession);

            Optional<String> result = interviewSessionCache.findUnfinishedSessionId(100L);

            assertThat(result).contains("session-1");
        }

        @Test
        @DisplayName("会话已完成时应清理映射并返回空")
        void shouldDeleteMappingWhenSessionCompleted() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1", 100L, 0, SessionStatus.COMPLETED, "questions-json"
            );
            when(redisService.get(RESUME_KEY)).thenReturn("session-1");
            when(redisService.get(SESSION_KEY)).thenReturn(cachedSession);

            Optional<String> result = interviewSessionCache.findUnfinishedSessionId(100L);

            assertThat(result).isEmpty();
            verify(redisService).delete(RESUME_KEY);
        }

        @Test
        @DisplayName("缓存会话丢失时应清理映射并返回空")
        void shouldDeleteMappingWhenCachedSessionMissing() {
            when(redisService.get(RESUME_KEY)).thenReturn("session-1");
            when(redisService.get(SESSION_KEY)).thenReturn(null);

            Optional<String> result = interviewSessionCache.findUnfinishedSessionId(100L);

            assertThat(result).isEmpty();
            verify(redisService).delete(RESUME_KEY);
        }
    }

    @Nested
    @DisplayName("TTL与存在性")
    class TtlAndExists {

        @Test
        @DisplayName("刷新TTL时应同步刷新会话和简历映射")
        void shouldRefreshSessionTtl() {
            InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                    "session-1", 100L, 0, SessionStatus.CREATED, "questions-json"
            );
            when(redisService.get(SESSION_KEY)).thenReturn(cachedSession);

            interviewSessionCache.refreshSessionTTL("session-1");

            verify(redisService).expire(SESSION_KEY, SESSION_TTL);
            verify(redisService).expire(RESUME_KEY, SESSION_TTL);
        }

        @Test
        @DisplayName("缓存不存在时刷新TTL不应调用expire")
        void shouldNotRefreshTtlWhenSessionMissing() {
            when(redisService.get(SESSION_KEY)).thenReturn(null);

            interviewSessionCache.refreshSessionTTL("session-1");

            verify(redisService, never()).expire(any(String.class), any(Duration.class));
        }

        @Test
        @DisplayName("应检查会话是否存在")
        void shouldCheckSessionExists() {
            when(redisService.exists(SESSION_KEY)).thenReturn(true);

            boolean result = interviewSessionCache.exists("session-1");

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("参数校验")
    class Validation {

        @Test
        @DisplayName("会话ID为空时应抛出异常")
        void shouldThrowWhenSessionIdBlank() {
            assertThatThrownBy(() -> interviewSessionCache.getSession(" "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("会话ID不能为空");
            verifyNoInteractions(redisService);
        }

        @Test
        @DisplayName("更新不存在的会话时应抛出异常")
        void shouldThrowWhenUpdatingMissingSession() {
            when(redisService.get(SESSION_KEY)).thenReturn(null);

            assertThatThrownBy(() ->
                    interviewSessionCache.updateSessionStatus("session-1", SessionStatus.IN_PROGRESS))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("缓存中的面试会话不存在: session-1");
        }
    }

    @Test
    @DisplayName("CachedSession应支持反序列化问题列表")
    void shouldDeserializeQuestionsFromCachedSession() throws Exception {
        InterviewSessionCache.CachedSession cachedSession = buildCachedSession(
                "session-1", 100L, 0, SessionStatus.CREATED, "questions-json"
        );
        List<InterviewQuestionDTO> questions = buildQuestions();
        when(objectMapper.readValue(eq("questions-json"), any(TypeReference.class))).thenReturn(questions);

        List<InterviewQuestionDTO> result = cachedSession.getQuestions(objectMapper);

        assertThat(result).containsExactlyElementsOf(questions);
    }

    private InterviewSessionCache.CachedSession buildCachedSession(
            String sessionId,
            Long resumeId,
            int currentIndex,
            SessionStatus status,
            String questionsJson
    ) {
        InterviewSessionCache.CachedSession cachedSession = new InterviewSessionCache.CachedSession();
        cachedSession.setSessionId(sessionId);
        cachedSession.setResumeText("简历内容");
        cachedSession.setResumeId(resumeId);
        cachedSession.setQuestionsJson(questionsJson);
        cachedSession.setCurrentIndex(currentIndex);
        cachedSession.setStatus(status);
        return cachedSession;
    }

    private List<InterviewQuestionDTO> buildQuestions() {
        return List.of(
                buildQuestion(0, "什么是索引", "MYSQL", "MySQL"),
                buildQuestion(1, "解释JVM内存模型", "JAVA", "Java")
        );
    }

    private InterviewQuestionDTO buildQuestion(int index, String question, String type, String category) {
        return InterviewQuestionDTO.create(index, question, type, category);
    }
}
