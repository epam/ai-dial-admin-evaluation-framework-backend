package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationResponseDto;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-09T16:10:51+0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.3 (Oracle Corporation)"
)
@Component
public class MetricDeclarationMapperImpl implements MetricDeclarationMapper {

    @Override
    public MetricDeclarationResponseDto toDto(MetricDeclaration entity) {
        if ( entity == null ) {
            return null;
        }

        MetricDeclarationResponseDto.MetricDeclarationResponseDtoBuilder metricDeclarationResponseDto = MetricDeclarationResponseDto.builder();

        metricDeclarationResponseDto.id( entity.getId() );
        metricDeclarationResponseDto.providerId( entity.getProviderId() );
        metricDeclarationResponseDto.name( entity.getName() );
        metricDeclarationResponseDto.displayName( entity.getDisplayName() );
        metricDeclarationResponseDto.description( entity.getDescription() );
        metricDeclarationResponseDto.createdAt( entity.getCreatedAt() );

        return metricDeclarationResponseDto.build();
    }
}
