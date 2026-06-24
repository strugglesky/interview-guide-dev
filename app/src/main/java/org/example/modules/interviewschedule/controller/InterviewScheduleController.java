package org.example.modules.interviewschedule.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.result.Result;
import org.example.modules.interviewschedule.model.*;
import org.example.modules.interviewschedule.service.InterviewParseService;
import org.example.modules.interviewschedule.service.InterviewScheduleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程管理控制器
 * Interview Schedule Management Controller
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/interview-schedule")
public class InterviewScheduleController {
    private final InterviewScheduleService scheduleService;
    private final InterviewParseService parseService;

    /**
     * 解析面试邀约文本
     *
     * @param request 解析请求
     * @return 解析结果
     */
    @PostMapping("/parse")
    public Result<ParseResponse> parse(@Valid @RequestBody ParseRequest request) {
        return Result.success(parseService.parse(request.getRawText(), request.getSource()));
    }

    /**
     * 创建面试记录
     *
     * @param request 创建请求
     * @return 创建的面试记录
     */
    @PostMapping
    public Result<InterviewScheduleDTO> create(@Valid @RequestBody CreateInterviewRequest request) {
        return Result.success(scheduleService.createSchedule(toScheduleDTO(request)));
    }

    /**
     * 根据ID获取面试记录
     *
     * @param id 面试记录ID
     * @return 面试记录详情
     */
    @GetMapping("/{id}")
    public Result<InterviewScheduleDTO> getById(@PathVariable Long id) {
        return Result.success(scheduleService.getById(id));
    }

    /**
     * 获取面试记录列表
     *
     * @param status 状态过滤（可选）
     * @param start 开始时间（可选）
     * @param end 结束时间（可选）
     * @return 面试记录列表
     */
    @GetMapping
    public Result<List<InterviewScheduleDTO>> getAll(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        return Result.success(scheduleService.getAll(status, start, end));
    }

    /**
     * 更新面试记录
     *
     * @param id 面试记录ID
     * @param request 更新请求
     * @return 更新后的面试记录
     */
    @PutMapping("/{id}")
    public Result<InterviewScheduleDTO> update(@PathVariable Long id, @Valid @RequestBody CreateInterviewRequest request) {
        return Result.success(scheduleService.updateSchedule(id, toScheduleDTO(request)));
    }

    /**
     * 删除面试记录
     *
     * @param id 面试记录ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        scheduleService.delete(id);
        return Result.success();
    }

    /**
     * 更新面试状态
     *
     * @param id 面试记录ID
     * @param status 新状态
     * @return 更新后的面试记录
     */
    @RequestMapping(path = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Result<InterviewScheduleDTO> updateStatus(@PathVariable Long id, @RequestParam InterviewStatus status) {
        return Result.success(scheduleService.updateStatus(id, status));
    }

    private InterviewScheduleDTO toScheduleDTO(CreateInterviewRequest request) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        dto.setCompanyName(request.getCompanyName());
        dto.setPosition(request.getPosition());
        dto.setInterviewTime(request.getInterviewTime());
        dto.setInterviewType(request.getInterviewType());
        dto.setMeetingLink(request.getMeetingLink());
        dto.setRoundNumber(request.getRoundNumber());
        dto.setInterviewer(request.getInterviewer());
        dto.setNotes(request.getNotes());
        return dto;
    }




}
