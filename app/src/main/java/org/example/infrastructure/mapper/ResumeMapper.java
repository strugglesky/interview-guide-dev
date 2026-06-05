package org.example.infrastructure.mapper;

import org.example.modules.resume.model.ResumeAnalysisEntity;
import org.example.modules.resume.model.ResumeAnalysisResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 简历相关的对象映射器
 * 使用MapStruct自动生成转换代码
 * <p>
 * 注意：JSON字段(strengthsJson, suggestionsJson)需要在Service层手动处理
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING
        , unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ResumeMapper {
    // ========== ScoreDetail 映射 ==========

    /**
     * 将实体基础字段映射到DTO的ScoreDetail
     */
    @Mapping(target = "contentScore", source = "contentScore", qualifiedByName = "nullToZero")
    @Mapping(target = "structureScore", source = "structureScore", qualifiedByName = "nullToZero")
    @Mapping(target = "skillMatchScore", source = "skillMatchScore", qualifiedByName = "nullToZero")
    @Mapping(target = "expressionScore", source = "expressionScore", qualifiedByName = "nullToZero")
    @Mapping(target = "projectScore", source = "projectScore", qualifiedByName = "nullToZero")
    ResumeAnalysisResponse.ScoreDetail toScoreDetail(ResumeAnalysisEntity entity);
}
