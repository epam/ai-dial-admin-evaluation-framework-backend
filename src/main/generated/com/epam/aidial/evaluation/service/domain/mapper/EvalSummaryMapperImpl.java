package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryDetailResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryResponseDto;
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
public class EvalSummaryMapperImpl extends EvalSummaryMapper {

    @Autowired
    private JacksonMapper jacksonMapper;

    @Override
    public EvalSummary toEntity(EvalSummaryBatchWriteItemDto item, UUID testSuiteId, UUID testSuiteRunId, UUID computationId, long createdAtMs, long computedAtMs) {
        if ( item == null && testSuiteId == null && testSuiteRunId == null && computationId == null ) {
            return null;
        }

        EvalSummary.EvalSummaryBuilder evalSummary = EvalSummary.builder();

        if ( item != null ) {
            evalSummary.testCaseRunResultId( item.getTestCaseRunResultId() );
            evalSummary.testCaseId( item.getTestCaseId() );
            evalSummary.testCaseName( item.getTestCaseName() );
            if ( item.getRunIndex() != null ) {
                evalSummary.runIndex( item.getRunIndex() );
            }
            evalSummary.testCaseData( jacksonMapper.asString( item.getTestCaseData() ) );
            evalSummary.extractedColumns( jacksonMapper.asString( item.getExtractedColumns() ) );
            evalSummary.executionStatus( item.getExecutionStatus() );
            evalSummary.execDurationMs( item.getExecDurationMs() );
            evalSummary.responseStatusCode( item.getResponseStatusCode() );
            evalSummary.metricValues( jacksonMapper.asString( item.getMetricValues() ) );
            evalSummary.metricInfos( jacksonMapper.asString( item.getMetricInfos() ) );
            evalSummary.extractionWarnings( jacksonMapper.asString( item.getExtractionWarnings() ) );
        }
        evalSummary.testSuiteId( testSuiteId );
        evalSummary.testSuiteRunId( testSuiteRunId );
        evalSummary.computationId( computationId );
        evalSummary.createdAtMs( createdAtMs );
        evalSummary.computedAtMs( computedAtMs );
        evalSummary.id( java.util.UUID.randomUUID() );

        EvalSummary evalSummaryResult = evalSummary.build();

        defaultExtractedColumns( evalSummaryResult );

        return evalSummaryResult;
    }

    @Override
    public EvalSummaryResponseDto toDto(EvalSummary entity) {
        if ( entity == null ) {
            return null;
        }

        EvalSummaryResponseDto.EvalSummaryResponseDtoBuilder evalSummaryResponseDto = EvalSummaryResponseDto.builder();

        evalSummaryResponseDto.createdAt( entity.getCreatedAtMs() );
        evalSummaryResponseDto.computedAt( entity.getComputedAtMs() );
        evalSummaryResponseDto.id( entity.getId() );
        evalSummaryResponseDto.testSuiteId( entity.getTestSuiteId() );
        evalSummaryResponseDto.testSuiteRunId( entity.getTestSuiteRunId() );
        evalSummaryResponseDto.testCaseRunResultId( entity.getTestCaseRunResultId() );
        evalSummaryResponseDto.testCaseId( entity.getTestCaseId() );
        evalSummaryResponseDto.testCaseName( entity.getTestCaseName() );
        evalSummaryResponseDto.runIndex( entity.getRunIndex() );
        evalSummaryResponseDto.computationId( entity.getComputationId() );
        evalSummaryResponseDto.testCaseData( jacksonMapper.asJsonNode( entity.getTestCaseData() ) );
        evalSummaryResponseDto.extractedColumns( jacksonMapper.asJsonNode( entity.getExtractedColumns() ) );
        evalSummaryResponseDto.execDurationMs( entity.getExecDurationMs() );
        evalSummaryResponseDto.responseStatusCode( entity.getResponseStatusCode() );
        evalSummaryResponseDto.metricValues( jacksonMapper.asJsonNode( entity.getMetricValues() ) );

        evalSummaryResponseDto.executionStatus( entity.getExecutionStatus().name() );

        EvalSummaryResponseDto evalSummaryResponseDtoResult = evalSummaryResponseDto.build();

        populateGrafanaTraceUrl( entity, evalSummaryResponseDtoResult );

        return evalSummaryResponseDtoResult;
    }

    @Override
    public EvalSummaryDetailResponseDto toDetailDto(EvalSummary entity) {
        if ( entity == null ) {
            return null;
        }

        EvalSummaryDetailResponseDto.EvalSummaryDetailResponseDtoBuilder evalSummaryDetailResponseDto = EvalSummaryDetailResponseDto.builder();

        evalSummaryDetailResponseDto.createdAt( entity.getCreatedAtMs() );
        evalSummaryDetailResponseDto.computedAt( entity.getComputedAtMs() );
        evalSummaryDetailResponseDto.extractionWarnings( jacksonMapper.asJsonNode( entity.getExtractionWarnings() ) );
        evalSummaryDetailResponseDto.requestBody( jacksonMapper.asJsonNode( entity.getRequestBody() ) );
        evalSummaryDetailResponseDto.responseBody( jacksonMapper.asJsonNode( entity.getResponseBody() ) );
        evalSummaryDetailResponseDto.id( entity.getId() );
        evalSummaryDetailResponseDto.testSuiteId( entity.getTestSuiteId() );
        evalSummaryDetailResponseDto.testSuiteRunId( entity.getTestSuiteRunId() );
        evalSummaryDetailResponseDto.testCaseRunResultId( entity.getTestCaseRunResultId() );
        evalSummaryDetailResponseDto.testCaseId( entity.getTestCaseId() );
        evalSummaryDetailResponseDto.testCaseName( entity.getTestCaseName() );
        evalSummaryDetailResponseDto.runIndex( entity.getRunIndex() );
        evalSummaryDetailResponseDto.computationId( entity.getComputationId() );
        evalSummaryDetailResponseDto.testCaseData( jacksonMapper.asJsonNode( entity.getTestCaseData() ) );
        evalSummaryDetailResponseDto.extractedColumns( jacksonMapper.asJsonNode( entity.getExtractedColumns() ) );
        evalSummaryDetailResponseDto.execDurationMs( entity.getExecDurationMs() );
        evalSummaryDetailResponseDto.responseStatusCode( entity.getResponseStatusCode() );
        evalSummaryDetailResponseDto.metricValues( jacksonMapper.asJsonNode( entity.getMetricValues() ) );
        evalSummaryDetailResponseDto.metricInfos( jacksonMapper.asJsonNode( entity.getMetricInfos() ) );

        evalSummaryDetailResponseDto.executionStatus( entity.getExecutionStatus().name() );

        EvalSummaryDetailResponseDto evalSummaryDetailResponseDtoResult = evalSummaryDetailResponseDto.build();

        populateGrafanaTraceUrl( entity, evalSummaryDetailResponseDtoResult );

        return evalSummaryDetailResponseDtoResult;
    }
}
