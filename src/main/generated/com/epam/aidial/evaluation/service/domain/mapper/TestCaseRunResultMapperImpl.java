package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultResponseDto;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-09T16:10:51+0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.3 (Oracle Corporation)"
)
@Component
public class TestCaseRunResultMapperImpl extends TestCaseRunResultMapper {

    @Autowired
    private JacksonMapper jacksonMapper;
    @Autowired
    private ValidationWarningsSerializer validationWarningsSerializer;

    @Override
    public TestCaseRunResult toEntity(TestCaseRunResultItemDto item, UUID testSuiteId, UUID testSuiteRunId, long createdAtMs) {
        if ( item == null && testSuiteId == null && testSuiteRunId == null ) {
            return null;
        }

        TestCaseRunResult.TestCaseRunResultBuilder testCaseRunResult = TestCaseRunResult.builder();

        if ( item != null ) {
            testCaseRunResult.executionStatus( itemExecutionInfoStatus( item ) );
            testCaseRunResult.execStartedAtMs( itemExecutionInfoStartedAt( item ) );
            testCaseRunResult.execCompletedAtMs( itemExecutionInfoCompletedAt( item ) );
            testCaseRunResult.traceId( itemExecutionInfoTraceId( item ) );
            Integer retryCount = itemExecutionInfoRetryCount( item );
            if ( retryCount != null ) {
                testCaseRunResult.retryCount( retryCount );
            }
            else {
                testCaseRunResult.retryCount( 0 );
            }
            testCaseRunResult.logDetails( jacksonMapper.serializeLogDetails( itemExecutionInfoLogDetails( item ) ) );
            testCaseRunResult.testCaseId( item.getTestCaseId() );
            testCaseRunResult.testCaseName( item.getTestCaseName() );
            if ( item.getRunIndex() != null ) {
                testCaseRunResult.runIndex( item.getRunIndex() );
            }
            testCaseRunResult.testCaseData( jacksonMapper.asString( item.getTestCaseData() ) );
            testCaseRunResult.requestBody( jacksonMapper.asString( item.getRequestBody() ) );
            testCaseRunResult.responseBody( jacksonMapper.asString( item.getResponseBody() ) );
            testCaseRunResult.responseStatusCode( item.getResponseStatusCode() );
            testCaseRunResult.extractedColumns( jacksonMapper.asString( item.getExtractedColumns() ) );
            testCaseRunResult.extractionWarnings( validationWarningsSerializer.serializeExtractionWarnings( item.getExtractionWarnings() ) );
        }
        testCaseRunResult.testSuiteId( testSuiteId );
        testCaseRunResult.testSuiteRunId( testSuiteRunId );
        testCaseRunResult.createdAtMs( createdAtMs );
        testCaseRunResult.id( java.util.UUID.randomUUID() );
        testCaseRunResult.execDurationMs( item.getExecutionInfo().getCompletedAt() - item.getExecutionInfo().getStartedAt() );

        TestCaseRunResult testCaseRunResultResult = testCaseRunResult.build();

        defaultExtractedFields( testCaseRunResultResult );

        return testCaseRunResultResult;
    }

    @Override
    public TestCaseRunResultResponseDto toDto(TestCaseRunResult entity) {
        if ( entity == null ) {
            return null;
        }

        TestCaseRunResultResponseDto.TestCaseRunResultResponseDtoBuilder testCaseRunResultResponseDto = TestCaseRunResultResponseDto.builder();

        testCaseRunResultResponseDto.executionInfo( testCaseRunResultToExecutionInfoResponseDto( entity ) );
        testCaseRunResultResponseDto.createdAt( entity.getCreatedAtMs() );
        testCaseRunResultResponseDto.id( entity.getId() );
        testCaseRunResultResponseDto.testSuiteRunId( entity.getTestSuiteRunId() );
        testCaseRunResultResponseDto.testSuiteId( entity.getTestSuiteId() );
        testCaseRunResultResponseDto.testCaseId( entity.getTestCaseId() );
        testCaseRunResultResponseDto.testCaseName( entity.getTestCaseName() );
        testCaseRunResultResponseDto.runIndex( entity.getRunIndex() );
        testCaseRunResultResponseDto.testCaseData( jacksonMapper.asJsonNode( entity.getTestCaseData() ) );
        testCaseRunResultResponseDto.requestBody( jacksonMapper.asJsonNode( entity.getRequestBody() ) );
        testCaseRunResultResponseDto.responseBody( jacksonMapper.asJsonNode( entity.getResponseBody() ) );
        testCaseRunResultResponseDto.responseStatusCode( entity.getResponseStatusCode() );
        testCaseRunResultResponseDto.extractedColumns( jacksonMapper.asJsonNode( entity.getExtractedColumns() ) );
        testCaseRunResultResponseDto.extractionWarnings( validationWarningsSerializer.deserializeExtractionWarnings( entity.getExtractionWarnings() ) );

        TestCaseRunResultResponseDto testCaseRunResultResponseDtoResult = testCaseRunResultResponseDto.build();

        populateGrafanaTraceUrl( entity, testCaseRunResultResponseDtoResult );

        return testCaseRunResultResponseDtoResult;
    }

    private ExecutionStatus itemExecutionInfoStatus(TestCaseRunResultItemDto testCaseRunResultItemDto) {
        ExecutionInfoRequestDto executionInfo = testCaseRunResultItemDto.getExecutionInfo();
        if ( executionInfo == null ) {
            return null;
        }
        return executionInfo.getStatus();
    }

    private Long itemExecutionInfoStartedAt(TestCaseRunResultItemDto testCaseRunResultItemDto) {
        ExecutionInfoRequestDto executionInfo = testCaseRunResultItemDto.getExecutionInfo();
        if ( executionInfo == null ) {
            return null;
        }
        return executionInfo.getStartedAt();
    }

    private Long itemExecutionInfoCompletedAt(TestCaseRunResultItemDto testCaseRunResultItemDto) {
        ExecutionInfoRequestDto executionInfo = testCaseRunResultItemDto.getExecutionInfo();
        if ( executionInfo == null ) {
            return null;
        }
        return executionInfo.getCompletedAt();
    }

    private String itemExecutionInfoTraceId(TestCaseRunResultItemDto testCaseRunResultItemDto) {
        ExecutionInfoRequestDto executionInfo = testCaseRunResultItemDto.getExecutionInfo();
        if ( executionInfo == null ) {
            return null;
        }
        return executionInfo.getTraceId();
    }

    private Integer itemExecutionInfoRetryCount(TestCaseRunResultItemDto testCaseRunResultItemDto) {
        ExecutionInfoRequestDto executionInfo = testCaseRunResultItemDto.getExecutionInfo();
        if ( executionInfo == null ) {
            return null;
        }
        return executionInfo.getRetryCount();
    }

    private Object itemExecutionInfoLogDetails(TestCaseRunResultItemDto testCaseRunResultItemDto) {
        ExecutionInfoRequestDto executionInfo = testCaseRunResultItemDto.getExecutionInfo();
        if ( executionInfo == null ) {
            return null;
        }
        return executionInfo.getLogDetails();
    }

    protected ExecutionInfoResponseDto testCaseRunResultToExecutionInfoResponseDto(TestCaseRunResult testCaseRunResult) {
        if ( testCaseRunResult == null ) {
            return null;
        }

        ExecutionInfoResponseDto.ExecutionInfoResponseDtoBuilder executionInfoResponseDto = ExecutionInfoResponseDto.builder();

        executionInfoResponseDto.status( testCaseRunResult.getExecutionStatus() );
        executionInfoResponseDto.startedAt( testCaseRunResult.getExecStartedAtMs() );
        executionInfoResponseDto.completedAt( testCaseRunResult.getExecCompletedAtMs() );
        executionInfoResponseDto.durationMs( testCaseRunResult.getExecDurationMs() );
        executionInfoResponseDto.traceId( testCaseRunResult.getTraceId() );
        executionInfoResponseDto.retryCount( testCaseRunResult.getRetryCount() );
        executionInfoResponseDto.logDetails( jacksonMapper.deserializeLogDetails( testCaseRunResult.getLogDetails() ) );

        return executionInfoResponseDto.build();
    }
}
