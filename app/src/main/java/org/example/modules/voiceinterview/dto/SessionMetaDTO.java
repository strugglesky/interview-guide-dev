package org.example.modules.voiceinterview.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用于列表显示的会话元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetaDTO {
    private Long sessionId;
    private String roleType;
    private String status;
    private String currentPhase;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer actualDuration;
    private Long messageCount;
    private String evaluateStatus;
    private String evaluateError;
}

