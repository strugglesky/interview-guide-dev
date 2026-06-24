package org.example.modules.interviewschedule.controller;

import org.example.common.constant.CommonConstants;
import org.example.common.result.Result;
import org.example.modules.interviewschedule.model.CreateInterviewRequest;
import org.example.modules.interviewschedule.model.InterviewScheduleDTO;
import org.example.modules.interviewschedule.model.InterviewStatus;
import org.example.modules.interviewschedule.model.ParseRequest;
import org.example.modules.interviewschedule.model.ParseResponse;
import org.example.modules.interviewschedule.service.InterviewParseService;
import org.example.modules.interviewschedule.service.InterviewScheduleService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试日程控制器测试")
public class InterviewScheduleControllerTest {

    private static final LocalDateTime INTERVIEW_TIME =
            LocalDateTime.of(2026, 4, 15, 19, 30);

    @Mock
    private InterviewScheduleService scheduleService;

    @Mock
    private InterviewParseService parseService;

    @InjectMocks
    private InterviewScheduleController interviewScheduleController;

    @Nested
    @DisplayName("解析邀约")
    class ParseInvite {

        @Test
        @DisplayName("应调用解析服务并返回解析结果")
        void shouldParseInviteText() {
            ParseRequest request = new ParseRequest();
            request.setRawText("公司：阿里巴巴\n岗位：后端开发工程师");
            request.setSource("feishu");
            ParseResponse expected = new ParseResponse(true, buildCreateRequest(), 0.95D, "rule", "ok");
            when(parseService.parse(request.getRawText(), request.getSource())).thenReturn(expected);

            Result<ParseResponse> result = interviewScheduleController.parse(request);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(parseService).parse(request.getRawText(), request.getSource());
        }
    }

    @Nested
    @DisplayName("创建和更新")
    class CreateAndUpdate {

        @Test
        @DisplayName("应将创建请求转换为DTO后创建日程")
        void shouldCreateSchedule() {
            CreateInterviewRequest request = buildCreateRequest();
            InterviewScheduleDTO expected = buildScheduleDTO(1L);
            when(scheduleService.createSchedule(anySchedule())).thenReturn(expected);

            Result<InterviewScheduleDTO> result = interviewScheduleController.create(request);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            ArgumentCaptor<InterviewScheduleDTO> captor =
                    ArgumentCaptor.forClass(InterviewScheduleDTO.class);
            verify(scheduleService).createSchedule(captor.capture());
            assertScheduleDTO(captor.getValue(), request);
        }

        @Test
        @DisplayName("应将更新请求转换为DTO后更新日程")
        void shouldUpdateSchedule() {
            CreateInterviewRequest request = buildCreateRequest();
            request.setCompanyName("腾讯");
            InterviewScheduleDTO expected = buildScheduleDTO(2L);
            expected.setCompanyName("腾讯");
            when(scheduleService.updateSchedule(anyId(), anySchedule())).thenReturn(expected);

            Result<InterviewScheduleDTO> result = interviewScheduleController.update(2L, request);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            ArgumentCaptor<InterviewScheduleDTO> captor =
                    ArgumentCaptor.forClass(InterviewScheduleDTO.class);
            verify(scheduleService).updateSchedule(org.mockito.Mockito.eq(2L), captor.capture());
            assertScheduleDTO(captor.getValue(), request);
        }
    }

    @Nested
    @DisplayName("查询日程")
    class QuerySchedule {

        @Test
        @DisplayName("应根据id查询日程")
        void shouldGetScheduleById() {
            InterviewScheduleDTO expected = buildScheduleDTO(1L);
            when(scheduleService.getById(1L)).thenReturn(expected);

            Result<InterviewScheduleDTO> result = interviewScheduleController.getById(1L);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(scheduleService).getById(1L);
        }

        @Test
        @DisplayName("应按过滤条件查询日程列表")
        void shouldGetScheduleList() {
            LocalDateTime start = INTERVIEW_TIME.minusDays(1);
            LocalDateTime end = INTERVIEW_TIME.plusDays(1);
            List<InterviewScheduleDTO> expected = List.of(buildScheduleDTO(1L), buildScheduleDTO(2L));
            when(scheduleService.getAll("PENDING", start, end)).thenReturn(expected);

            Result<List<InterviewScheduleDTO>> result =
                    interviewScheduleController.getAll("PENDING", start, end);

            assertSuccess(result);
            assertThat(result.getData()).containsExactlyElementsOf(expected);
            verify(scheduleService).getAll("PENDING", start, end);
        }
    }

    @Nested
    @DisplayName("删除和状态")
    class DeleteAndStatus {

        @Test
        @DisplayName("应删除指定日程并返回成功")
        void shouldDeleteSchedule() {
            Result<Void> result = interviewScheduleController.delete(1L);

            assertSuccess(result);
            assertThat(result.getData()).isNull();
            verify(scheduleService).delete(1L);
        }

        @Test
        @DisplayName("应更新指定日程状态")
        void shouldUpdateScheduleStatus() {
            InterviewScheduleDTO expected = buildScheduleDTO(1L);
            expected.setStatus(InterviewStatus.CANCELLED);
            when(scheduleService.updateStatus(1L, InterviewStatus.CANCELLED)).thenReturn(expected);

            Result<InterviewScheduleDTO> result =
                    interviewScheduleController.updateStatus(1L, InterviewStatus.CANCELLED);

            assertSuccess(result);
            assertThat(result.getData()).isEqualTo(expected);
            verify(scheduleService).updateStatus(1L, InterviewStatus.CANCELLED);
        }
    }

    private void assertSuccess(Result<?> result) {
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(CommonConstants.StatusCode.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
    }

    private void assertScheduleDTO(InterviewScheduleDTO dto, CreateInterviewRequest request) {
        assertThat(dto.getCompanyName()).isEqualTo(request.getCompanyName());
        assertThat(dto.getPosition()).isEqualTo(request.getPosition());
        assertThat(dto.getInterviewTime()).isEqualTo(request.getInterviewTime());
        assertThat(dto.getInterviewType()).isEqualTo(request.getInterviewType());
        assertThat(dto.getMeetingLink()).isEqualTo(request.getMeetingLink());
        assertThat(dto.getRoundNumber()).isEqualTo(request.getRoundNumber());
        assertThat(dto.getInterviewer()).isEqualTo(request.getInterviewer());
        assertThat(dto.getNotes()).isEqualTo(request.getNotes());
    }

    private CreateInterviewRequest buildCreateRequest() {
        CreateInterviewRequest request = new CreateInterviewRequest();
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

    private InterviewScheduleDTO buildScheduleDTO(Long id) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        dto.setId(id);
        dto.setCompanyName("阿里巴巴");
        dto.setPosition("后端开发工程师");
        dto.setInterviewTime(INTERVIEW_TIME);
        dto.setInterviewType("VIDEO");
        dto.setMeetingLink("https://meeting.example.com/abc");
        dto.setRoundNumber(2);
        dto.setInterviewer("李老师");
        dto.setNotes("准备项目介绍");
        dto.setStatus(InterviewStatus.PENDING);
        dto.setCreatedAt(INTERVIEW_TIME.minusDays(1));
        dto.setUpdatedAt(INTERVIEW_TIME.minusHours(1));
        return dto;
    }

    private Long anyId() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private InterviewScheduleDTO anySchedule() {
        return org.mockito.ArgumentMatchers.any(InterviewScheduleDTO.class);
    }
}
