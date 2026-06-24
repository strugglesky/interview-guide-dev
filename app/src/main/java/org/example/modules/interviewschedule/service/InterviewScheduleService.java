package org.example.modules.interviewschedule.service;

import lombok.RequiredArgsConstructor;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.interviewschedule.model.InterviewScheduleDTO;
import org.example.modules.interviewschedule.model.InterviewScheduleEntity;
import org.example.modules.interviewschedule.repository.InterviewScheduleRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.modules.interviewschedule.model.InterviewStatus;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 面试日程管理服务：提供对面试日程的增删改查与状态更新。
 * <p>
 * 主要职责：
 * - 创建、更新、删除面试日程记录
 * - 按时间区间或状态查询日程
 * - 更新面试状态（如 PENDING/CONFIRMED/CANCELLED 等）
 * <p>
 * 业务异常通过抛出 BusinessException 返回统一错误码。
 */
@Service
@RequiredArgsConstructor
public class InterviewScheduleService {
    private final InterviewScheduleRepository repository;

    private static final String[] COPYABLE_FIELDS = {
            "companyName", "position", "interviewTime", "interviewType",
            "meetingLink", "roundNumber", "interviewer", "notes"
    };

    /**
     * 创建一条新的面试日程记录。
     *
     * @param request 前端传入的创建请求，包含公司、岗位、时间等信息
     * @return 创建并保存后的 InterviewScheduleDTO
     */
    @Transactional
    public InterviewScheduleDTO createSchedule(InterviewScheduleDTO request) {
        validateScheduleRequest(request, false);
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        copyMutableFields(request, entity);
        entity.setStatus(request.getStatus() != null ? request.getStatus() : InterviewStatus.PENDING);
        return toDto(repository.save(entity));
    }

    /**
     * 更新指定 id 的面试日程（不会修改 id 与 status 字段）。
     *
     * @param id      要更新的日程 id
     * @param request 包含要更新字段的请求体
     * @return 更新后的 InterviewScheduleDTO
     * @throws BusinessException 如果指定 id 不存在则抛出 INTERVIEW_SCHEDULE_NOT_FOUND
     */
    @Transactional
    public InterviewScheduleDTO updateSchedule(Long id, InterviewScheduleDTO request) {
        validateScheduleId(id);
        validateScheduleRequest(request, true);
        InterviewScheduleEntity entity = loadScheduleOrThrow(id);
        copyMutableFields(request, entity);
        return toDto(repository.save(entity));
    }

    /**
     * 删除指定 id 的面试日程。
     *
     * @param id 要删除的日程 id
     */
    @Transactional
    public void delete(Long id) {
        validateScheduleId(id);
        repository.delete(loadScheduleOrThrow(id));
    }

    /**
     * 更新面试日程的状态（如 PENDING/CONFIRMED/CANCELLED）。
     *
     * @param id     要更新的日程 id
     * @param status 新的面试状态
     * @return 更新后的 InterviewScheduleDTO
     * @throws BusinessException 如果指定 id 不存在则抛出 INTERVIEW_SCHEDULE_NOT_FOUND
     */
    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        validateScheduleId(id);
        if (status == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试状态不能为空");
        }
        InterviewScheduleEntity entity = loadScheduleOrThrow(id);
        entity.setStatus(status);
        return toDto(repository.save(entity));
    }

    /**
     * 查询面试日程列表：
     * - 如果同时传入 start 和 end，则按时间区间查询；
     * - 否则如果传入 status，则按状态查询；
     * - 否则返回所有记录。
     *
     * @param status 可选的状态过滤（字符串形式，对应 InterviewStatus 名称）
     * @param start  可选的开始时间（包含）
     * @param end    可选的结束时间（包含）
     * @return InterviewScheduleDTO 列表
     */
    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        validateTimeRange(start, end);
        List<InterviewScheduleEntity> entities;
        if (start != null && end != null) {
            entities = repository.findByInterviewTimeBetween(start, end);
        } else if (StringUtils.hasText(status)) {
            entities = repository.findByStatus(parseStatus(status));
        } else {
            entities = repository.findAll();
        }
        return entities.stream().map(this::toDto).toList();
    }

    /**
     * 根据 id 获取单条面试日程。
     *
     * @param id 日程 id
     * @return InterviewScheduleDTO
     * @throws BusinessException 如果指定 id 不存在则抛出 INTERVIEW_SCHEDULE_NOT_FOUND
     */
    public InterviewScheduleDTO getById(Long id) {
        validateScheduleId(id);
        return toDto(loadScheduleOrThrow(id));
    }

    private void validateScheduleRequest(InterviewScheduleDTO request, boolean ignoreStatusField) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试日程请求不能为空");
        }
        if (!StringUtils.hasText(request.getCompanyName())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "公司名称不能为空");
        }
        if (!StringUtils.hasText(request.getPosition())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "岗位不能为空");
        }
        if (request.getInterviewTime() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试时间不能为空");
        }
        if (request.getRoundNumber() != null && request.getRoundNumber() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试轮次必须大于 0");
        }
        if (StringUtils.hasText(request.getInterviewType())) {
            normalizeInterviewType(request.getInterviewType());
        }
        if (!ignoreStatusField && request.getStatus() == null) {
            request.setStatus(InterviewStatus.PENDING);
        }
    }

    private void copyMutableFields(InterviewScheduleDTO source, InterviewScheduleEntity target) {
        BeanWrapper sourceWrapper = new BeanWrapperImpl(source);
        BeanWrapper targetWrapper = new BeanWrapperImpl(target);
        for (String field : COPYABLE_FIELDS) {
            Object value = sourceWrapper.getPropertyValue(field);
            if (value instanceof String textValue) {
                value = normalizeText(textValue);
            }
            if ("companyName".equals(field) || "position".equals(field)) {
                value = requireNormalizedText((String) value, field);
            }
            if ("interviewType".equals(field) && value instanceof String interviewType) {
                value = normalizeInterviewType(interviewType);
            }
            if ("roundNumber".equals(field)) {
                value = value != null ? value : 1;
            }
            targetWrapper.setPropertyValue(field, value);
        }
    }

    private InterviewScheduleEntity loadScheduleOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND,
                        "面试日程不存在: id=" + id
                ));
    }

    private InterviewStatus parseStatus(String status) {
        try {
            return InterviewStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法的面试状态: " + status);
        }
    }

    private void validateTimeRange(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "开始时间不能晚于结束时间");
        }
    }

    private void validateScheduleId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试日程 id 非法");
        }
    }

    private String normalizeInterviewType(String interviewType) {
        String normalized = interviewType.strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ONSITE", "VIDEO", "PHONE" -> normalized;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "非法的面试形式: " + interviewType);
        };
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private String requireNormalizedText(String value, String fieldName) {
        if (StringUtils.hasText(value)) {
            return value;
        }
        if ("companyName".equals(fieldName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "公司名称不能为空");
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "岗位不能为空");
    }

    private InterviewScheduleDTO toDto(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }


}
