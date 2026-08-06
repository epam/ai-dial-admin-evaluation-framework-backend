package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ResponseColumnUnionResolver Tests")
class ResponseColumnUnionResolverTest {

    private ResponseColumnUnionResolver resolver;
    private JsonbMapper jsonbMapper;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        jsonbMapper = new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper));
        resolver = new ResponseColumnUnionResolver(jsonbMapper);
    }

    @Test
    @DisplayName("Union from a DTO orders suite columns first, then each additional request's in chain order")
    void shouldOrderUnionFromDto() {
        TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .name("Suite")
                .responseColumns(List.of(column("configId")))
                .additionalRequests(
                        List.of(chainRequest(column("answer")), chainRequest(column("sessionId"), column("followUp"))))
                .build();

        List<ResponseColumnDefinitionDto> union = resolver.unionFrom(dto);

        assertThat(union)
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("configId", "answer", "sessionId", "followUp");
    }

    @Test
    @DisplayName("Union from a DTO with no additional requests is request #0's columns only")
    void shouldReturnSuiteColumnsOnlyWhenNoAdditionalRequests() {
        TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                .name("Suite")
                .responseColumns(List.of(column("configId")))
                .additionalRequests(List.of())
                .build();

        List<ResponseColumnDefinitionDto> union = resolver.unionFrom(dto);

        assertThat(union).extracting(ResponseColumnDefinitionDto::getName).containsExactly("configId");
    }

    @Test
    @DisplayName("Union from a DTO tolerates null responseColumns/additionalRequests")
    void shouldToleratesNullsInDto() {
        TestSuiteRequestDto dto = TestSuiteRequestDto.builder().name("Suite").build();

        assertThat(resolver.unionFrom(dto)).isEmpty();
    }

    @Test
    @DisplayName("Union from an entity decodes both JSONB columns and preserves chain order")
    void shouldOrderUnionFromEntity() {
        String responseColumnsJson = jsonbMapper.mapResponseColumns(List.of(column("configId")));
        String additionalRequestsJson = jsonbMapper.mapAdditionalRequests(
                List.of(chainRequest(column("answer")), chainRequest(column("sessionId"))));
        TestSuite entity = TestSuite.builder()
                .responseColumns(responseColumnsJson)
                .additionalRequests(additionalRequestsJson)
                .build();

        List<ResponseColumnDefinitionDto> union = resolver.unionFrom(entity);

        assertThat(union)
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("configId", "answer", "sessionId");
    }

    @Test
    @DisplayName("unionJson serializes the entity-derived union")
    void shouldSerializeUnionFromEntity() {
        String responseColumnsJson = jsonbMapper.mapResponseColumns(List.of(column("configId")));
        String additionalRequestsJson = jsonbMapper.mapAdditionalRequests(List.of(chainRequest(column("answer"))));
        TestSuite entity = TestSuite.builder()
                .responseColumns(responseColumnsJson)
                .additionalRequests(additionalRequestsJson)
                .build();

        String json = resolver.unionJson(entity);

        assertThat(json).contains("configId").contains("answer");
        assertThat(jsonbMapper.mapResponseColumns(json))
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("configId", "answer");
    }

    @Test
    @DisplayName("Union from a snapshot with no additional requests is request #0's columns only")
    void shouldReturnRequestZeroColumnsOnlyForSnapshot() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .responseColumns(List.of(column("configId"), column("answer")))
                .build();

        List<ResponseColumnDefinitionDto> union = resolver.unionFrom(snapshot);

        assertThat(union).extracting(ResponseColumnDefinitionDto::getName).containsExactly("configId", "answer");
    }

    @Test
    @DisplayName("Union from a snapshot orders suite columns first, then each additional request's in chain order")
    void shouldOrderUnionFromSnapshotChain() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                .responseColumns(List.of(column("configId")))
                .additionalRequests(
                        List.of(chainRequest(column("answer")), chainRequest(column("sessionId"), column("followUp"))))
                .build();

        List<ResponseColumnDefinitionDto> union = resolver.unionFrom(snapshot);

        assertThat(union)
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("configId", "answer", "sessionId", "followUp");
    }

    @Test
    @DisplayName("Union from a snapshot with null responseColumns and no additional requests is empty")
    void shouldReturnEmptyForSnapshotWithNullColumns() {
        SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder().build();

        assertThat(resolver.unionFrom(snapshot)).isEmpty();
    }

    private RequestDefinitionDto chainRequest(ResponseColumnDefinitionDto... columns) {
        return RequestDefinitionDto.builder().responseColumns(List.of(columns)).build();
    }

    private ResponseColumnDefinitionDto column(String name) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.STRING)
                .expression("usage.total_tokens")
                .build();
    }
}
