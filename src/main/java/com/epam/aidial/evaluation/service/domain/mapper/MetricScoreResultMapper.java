package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreResultResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MetricScoreResultMapper {

    MetricScoreResultResponseDto toDto(MetricScoreResult entity);
}
