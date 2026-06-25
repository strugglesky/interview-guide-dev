package org.example.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
/**
 * 语音评估细节 DTO - 与基于文本的访谈 InterviewDetailDTO 保持一致。
 * 这允许前端重复使用 InterviewDetailPanel 进行渲染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationDetailDTO {

    private Long sessionId;
    private int totalQuestions;
    private int overallScore;
    private String overallFeedback;
    private List<String> strengths;
    private List<String> improvements;
    private List<AnswerDetail> answers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDetail {
        private int questionIndex;
        private String question;
        private String category;
        private String userAnswer;
        private int score;
        private String feedback;
        private String referenceAnswer;
        private List<String> keyPoints;
    }
}
