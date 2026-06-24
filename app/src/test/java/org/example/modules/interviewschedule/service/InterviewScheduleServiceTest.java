package org.example.modules.interviewschedule.service;

import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.interviewschedule.model.InterviewScheduleDTO;
import org.example.modules.interviewschedule.model.InterviewScheduleEntity;
import org.example.modules.interviewschedule.model.InterviewStatus;
import org.example.modules.interviewschedule.repository.InterviewScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试日程服务测试")
public class InterviewScheduleServiceTest {

    private static final LocalDateTime INTERVIEW_TIME =
            LocalDateTime.of(2026, 4, 15, 19, 30);

    @Mock
    private InterviewScheduleRepository repository;

    @InjectMocks
    private InterviewScheduleService interviewScheduleService;

    @Nested
    @DisplayName("创建日程")
    class CreateSchedule {

        @Test
        @DisplayName("应创建日程并默认设置待处理状态")
        void shouldCreateScheduleWithDefaultPendingStatus() {
            InterviewScheduleDTO request = buildRequest();
            request.setCompanyName("  阿里巴巴  ");
            request.setInterviewType(" video ");
            request.setStatus(null);
            when(repository.save(any(InterviewScheduleEntity.class)))
                    .thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

            InterviewScheduleDTO result = interviewScheduleService.createSchedule(request);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getCompanyName()).isEqualTo("阿里巴巴");
            assertThat(result.getInterviewType()).isEqualTo("VIDEO");
            assertThat(result.getStatus()).isEqualTo(InterviewStatus.PENDING);

            ArgumentCaptor<InterviewScheduleEntity> captor =
                    ArgumentCaptor.forClass(InterviewScheduleEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getCompanyName()).isEqualTo("阿里巴巴");
            assertThat(captor.getValue().getStatus()).isEqualTo(InterviewStatus.PENDING);
        }

        @Test
        @DisplayName("必填字段缺失时应抛出业务异常")
        void shouldThrowWhenRequiredFieldsMissing() {
            InterviewScheduleDTO request = buildRequest();
            request.setCompanyName(" ");

            assertThatThrownBy(() -> interviewScheduleService.createSchedule(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("更新日程")
    class UpdateSchedule {

        @Test
        @DisplayName("应更新可编辑字段且不修改状态")
        void shouldUpdateMutableFieldsOnly() {
            InterviewScheduleEntity existing = buildEntity(1L);
            existing.setStatus(InterviewStatus.COMPLETED);
            InterviewScheduleDTO request = buildRequest();
            request.setCompanyName("腾讯");
            request.setPosition("Go开发工程师");
            request.setInterviewType("phone");
            request.setStatus(InterviewStatus.CANCELLED);
            when(repository.findById(1L)).thenReturn(Optional.of(existing));
            when(repository.save(existing)).thenReturn(existing);

            InterviewScheduleDTO result = interviewScheduleService.updateSchedule(1L, request);

            assertThat(result.getCompanyName()).isEqualTo("腾讯");
            assertThat(result.getPosition()).isEqualTo("Go开发工程师");
            assertThat(result.getInterviewType()).isEqualTo("PHONE");
            assertThat(result.getStatus()).isEqualTo(InterviewStatus.COMPLETED);
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("日程不存在时应抛出业务异常")
        void shouldThrowWhenScheduleNotFound() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> interviewScheduleService.updateSchedule(99L, buildRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND.getCode()));
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("删除日程")
    class DeleteSchedule {

        @Test
        @DisplayName("应删除已存在的日程")
        void shouldDeleteExistingSchedule() {
            InterviewScheduleEntity existing = buildEntity(1L);
            when(repository.findById(1L)).thenReturn(Optional.of(existing));

            interviewScheduleService.delete(1L);

            verify(repository).delete(existing);
        }

        @Test
        @DisplayName("id非法时应抛出业务异常")
        void shouldThrowWhenIdInvalid() {
            assertThatThrownBy(() -> interviewScheduleService.delete(0L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
            verify(repository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("状态更新")
    class UpdateStatus {

        @Test
        @DisplayName("应更新日程状态")
        void shouldUpdateStatus() {
            InterviewScheduleEntity existing = buildEntity(1L);
            when(repository.findById(1L)).thenReturn(Optional.of(existing));
            when(repository.save(existing)).thenReturn(existing);

            InterviewScheduleDTO result =
                    interviewScheduleService.updateStatus(1L, InterviewStatus.CANCELLED);

            assertThat(result.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
            assertThat(existing.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("状态为空时应抛出业务异常")
        void shouldThrowWhenStatusNull() {
            assertThatThrownBy(() -> interviewScheduleService.updateStatus(1L, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
            verify(repository, never()).findById(1L);
        }
    }

    @Nested
    @DisplayName("查询日程")
    class QuerySchedule {

        @Test
        @DisplayName("同时传入起止时间时应按时间区间查询")
        void shouldQueryByTimeRangeWhenStartAndEndPresent() {
            LocalDateTime start = INTERVIEW_TIME.minusDays(1);
            LocalDateTime end = INTERVIEW_TIME.plusDays(1);
            when(repository.findByInterviewTimeBetween(start, end))
                    .thenReturn(List.of(buildEntity(1L)));

            List<InterviewScheduleDTO> result =
                    interviewScheduleService.getAll("cancelled", start, end);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getId()).isEqualTo(1L);
            verify(repository).findByInterviewTimeBetween(start, end);
            verify(repository, never()).findByStatus(any());
        }

        @Test
        @DisplayName("未传时间区间且有状态时应按状态查询")
        void shouldQueryByStatusWhenStatusPresent() {
            when(repository.findByStatus(InterviewStatus.CANCELLED))
                    .thenReturn(List.of(buildEntity(2L)));

            List<InterviewScheduleDTO> result =
                    interviewScheduleService.getAll("cancelled", null, null);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getId()).isEqualTo(2L);
            verify(repository).findByStatus(InterviewStatus.CANCELLED);
        }

        @Test
        @DisplayName("无过滤条件时应查询全部")
        void shouldQueryAllWhenNoFilterPresent() {
            when(repository.findAll()).thenReturn(List.of(buildEntity(1L), buildEntity(2L)));

            List<InterviewScheduleDTO> result = interviewScheduleService.getAll(null, null, null);

            assertThat(result).extracting(InterviewScheduleDTO::getId).containsExactly(1L, 2L);
            verify(repository).findAll();
        }

        @Test
        @DisplayName("时间区间非法时应抛出业务异常")
        void shouldThrowWhenTimeRangeInvalid() {
            LocalDateTime start = INTERVIEW_TIME.plusDays(1);
            LocalDateTime end = INTERVIEW_TIME.minusDays(1);

            assertThatThrownBy(() -> interviewScheduleService.getAll(null, start, end))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
            verify(repository, never()).findAll();
        }
    }

    @Nested
    @DisplayName("查询详情")
    class GetById {

        @Test
        @DisplayName("应返回指定id的日程")
        void shouldReturnScheduleById() {
            when(repository.findById(1L)).thenReturn(Optional.of(buildEntity(1L)));

            InterviewScheduleDTO result = interviewScheduleService.getById(1L);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getCompanyName()).isEqualTo("阿里巴巴");
            verify(repository).findById(1L);
        }

        @Test
        @DisplayName("日程不存在时应抛出业务异常")
        void shouldThrowWhenScheduleMissing() {
            when(repository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> interviewScheduleService.getById(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                            .isEqualTo(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND.getCode()));
        }
    }

    private InterviewScheduleDTO buildRequest() {
        InterviewScheduleDTO request = new InterviewScheduleDTO();
        request.setCompanyName("阿里巴巴");
        request.setPosition("后端开发工程师");
        request.setInterviewTime(INTERVIEW_TIME);
        request.setInterviewType("VIDEO");
        request.setMeetingLink("https://meeting.example.com/abc");
        request.setRoundNumber(2);
        request.setInterviewer("李老师");
        request.setNotes("准备项目介绍");
        return request;
    }

    private InterviewScheduleEntity buildEntity(Long id) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        entity.setId(id);
        entity.setCompanyName("阿里巴巴");
        entity.setPosition("后端开发工程师");
        entity.setInterviewTime(INTERVIEW_TIME);
        entity.setInterviewType("VIDEO");
        entity.setMeetingLink("https://meeting.example.com/abc");
        entity.setRoundNumber(2);
        entity.setInterviewer("李老师");
        entity.setNotes("准备项目介绍");
        entity.setStatus(InterviewStatus.PENDING);
        entity.setCreatedAt(INTERVIEW_TIME.minusDays(1));
        entity.setUpdatedAt(INTERVIEW_TIME.minusHours(1));
        return entity;
    }

    private InterviewScheduleEntity withId(InterviewScheduleEntity entity, Long id) {
        entity.setId(id);
        return entity;
    }
}
