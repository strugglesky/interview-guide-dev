package org.example.modules.interviewschedule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.modules.interviewschedule.model.InterviewStatus;
import org.example.modules.interviewschedule.repository.InterviewScheduleRepository;
import org.jetbrains.annotations.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 定时任务：定期扫描并更新过期的面试日程状态。
 *
 * 职责：将状态为 PENDING 且面试时间已过的日程标记为 CANCELLED。运行在 Spring 调度器中。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduleStatusUpdater {
    private final InterviewScheduleRepository repository;

    /**
     * 每小时执行一次的定时任务：
     * - 将所有状态为 PENDING 且 interviewTime 在当前时间之前的记录更新为 CANCELLED
     * - 在更新条数大于 0 时记录日志
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void updateExpiredInterviews() {
        int updated = repository.updateStatusByStatusAndInterviewTimeBefore(
                InterviewStatus.CANCELLED, InterviewStatus.PENDING, LocalDateTime.now());

        if (updated > 0) {
            log.info("已将 {} 条过期面试标记为已取消", updated);
        }
    }

}
