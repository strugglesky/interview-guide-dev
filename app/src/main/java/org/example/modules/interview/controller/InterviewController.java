package org.example.modules.interview.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.result.Result;
import org.example.modules.interview.model.SessionListItemDTO;
import org.example.modules.interview.service.InterviewHistoryService;
import org.example.modules.interview.service.InterviewPersistenceService;
import org.example.modules.interview.service.InterviewSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    }
}
