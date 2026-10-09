package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotResponseDto;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-09T16:10:50+0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.3 (Oracle Corporation)"
)
@Component
public class RunMetricSnapshotMapperImpl extends RunMetricSnapshotMapper {

    @Autowired
    private JacksonMapper jacksonMapper;

    @Override
    public RunMetricSnapshot toEntity(RunMetricSnapshotBatchWriteItemDto item, UUID computationId, UUID testSuiteRunId, long computedAtMs) {
        if ( item == null && computationId == null && testSuiteRunId == null ) {
            return null;
        }

        RunMetricSnapshot.RunMetricSnapshotBuilder runMetricSnapshot = RunMetricSnapshot.builder();

        if ( item != null ) {
            runMetricSnapshot.tsmdId( item.getTsmdId() );
            runMetricSnapshot.tsmdName( item.getTsmdName() );
            runMetricSnapshot.metricDeclarationId( item.getMetricDeclarationId() );
            runMetricSnapshot.metricDeclarationVersionId( item.getMetricDeclarationVersionId() );
            runMetricSnapshot.configBindings( jacksonMapper.asString( item.getConfigBindings() ) );
            runMetricSnapshot.inputBindings( jacksonMapper.asString( item.getInputBindings() ) );
            runMetricSnapshot.outputSchema( jacksonMapper.asString( item.getOutputSchema() ) );
        }
        runMetricSnapshot.computationId( computationId );
        runMetricSnapshot.testSuiteRunId( testSuiteRunId );
        runMetricSnapshot.computedAtMs( computedAtMs );
        runMetricSnapshot.id( java.util.UUID.randomUUID() );

        RunMetricSnapshot runMetricSnapshotResult = runMetricSnapshot.build();

        defaultNullFields( runMetricSnapshotResult );

        return runMetricSnapshotResult;
    }

    @Override
    public RunMetricSnapshotResponseDto toDto(RunMetricSnapshot entity) {
        if ( entity == null ) {
            return null;
        }

        RunMetricSnapshotResponseDto.RunMetricSnapshotResponseDtoBuilder runMetricSnapshotResponseDto = RunMetricSnapshotResponseDto.builder();

        runMetricSnapshotResponseDto.id( entity.getId() );
        runMetricSnapshotResponseDto.computationId( entity.getComputationId() );
        runMetricSnapshotResponseDto.testSuiteRunId( entity.getTestSuiteRunId() );
        runMetricSnapshotResponseDto.tsmdId( entity.getTsmdId() );
        runMetricSnapshotResponseDto.tsmdName( entity.getTsmdName() );
        runMetricSnapshotResponseDto.metricDeclarationId( entity.getMetricDeclarationId() );
        runMetricSnapshotResponseDto.metricDeclarationVersionId( entity.getMetricDeclarationVersionId() );
        runMetricSnapshotResponseDto.configBindings( jacksonMapper.asJsonNode( entity.getConfigBindings() ) );
        runMetricSnapshotResponseDto.inputBindings( jacksonMapper.asJsonNode( entity.getInputBindings() ) );
        runMetricSnapshotResponseDto.computedAtMs( entity.getComputedAtMs() );

        RunMetricSnapshotResponseDto runMetricSnapshotResponseDtoResult = runMetricSnapshotResponseDto.build();

        mapOutputSchema( entity, runMetricSnapshotResponseDtoResult );

        return runMetricSnapshotResponseDtoResult;
    }
}
