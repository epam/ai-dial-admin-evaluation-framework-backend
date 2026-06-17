package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntitySchemaDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Query Schema Discovery (/api/v0/queries) Tests")
public abstract class QuerySchemaDiscoveryFunctionalTests extends BaseFunctionalTest {

    private static final UUID SEED_ACCURACY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarationTestDataProvider;

    private String queriesUrl(String path) {
        return baseUrl() + "/api/v0/queries" + path;
    }

    @Test
    @DisplayName("lists queryable entities with complexity and schema id field")
    void shouldListQueryableEntities() {
        ResponseEntity<List<QueryEntityDto>> response = restTemplate.exchange(
                queriesUrl("/entities"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsExactly(
                        new QueryEntityDto("eval_summaries", true, "test_suite_id"),
                        new QueryEntityDto("test_suites", false, null));
    }

    @Test
    @DisplayName("returns the flat base schema of the simple test_suites entity")
    void shouldReturnTestSuitesBaseSchema() {
        ResponseEntity<QueryEntitySchemaDto> response =
                restTemplate.getForEntity(queriesUrl("/entities/schema/test_suites"), QueryEntitySchemaDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        QueryEntitySchemaDto schema = response.getBody();
        assertThat(schema).isNotNull();
        assertThat(schema.entity()).isEqualTo("test_suites");
        assertThat(schema.complex()).isFalse();
        assertThat(schema.schemaIdField()).isNull();
        assertThat(schema.fields())
                .contains(
                        new QuerySchemaFieldDto("id", QueryFieldType.UUID, "id"),
                        new QuerySchemaFieldDto("name", QueryFieldType.STRING, "name"),
                        new QuerySchemaFieldDto("response_columns", QueryFieldType.ARRAY, "response_columns"));
    }

    @Test
    @DisplayName("returns the eval_summaries base schema with JSONB fields listed as-is")
    void shouldReturnEvalSummariesBaseSchema() {
        ResponseEntity<QueryEntitySchemaDto> response =
                restTemplate.getForEntity(queriesUrl("/entities/schema/eval_summaries"), QueryEntitySchemaDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        QueryEntitySchemaDto schema = response.getBody();
        assertThat(schema).isNotNull();
        assertThat(schema.entity()).isEqualTo("eval_summaries");
        assertThat(schema.complex()).isTrue();
        assertThat(schema.schemaIdField()).isEqualTo("test_suite_id");
        assertThat(schema.fields())
                .contains(
                        new QuerySchemaFieldDto("test_suite_id", QueryFieldType.UUID, "test_suite_id"),
                        new QuerySchemaFieldDto("test_case_data", QueryFieldType.OBJECT, "test_case_data"),
                        new QuerySchemaFieldDto("metric_values", QueryFieldType.OBJECT, "metric_values"));
    }

    @Test
    @DisplayName("returns the detailed eval_summaries schema flattened from the current suite state")
    void shouldReturnDetailedEvalSummariesSchemaFromCurrentSuiteState() {
        TestSuite suite = metaTestDataHelper.createTestSuite("query-schema-suite-" + UUID.randomUUID());
        metaTestDataHelper.updateSuiteSchema(suite.getId(), """
                [{"name": "question", "type": "STRING", "required": true},
                 {"name": "expectedScore", "type": "NUMBER", "required": false}]
                """, """
                [{"name": "answer", "expression": "$.answer", "type": "STRING"}]
                """);
        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        String versionId = UUID.randomUUID().toString();
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                versionId, SEED_ACCURACY_ID.toString(), 1, "{}", "{}", """
                {"properties": {"score": {"type": "number"}, "explanation": {"type": "string"}}}
                """);
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(), SEED_ACCURACY_ID, UUID.fromString(versionId), "Accuracy");

        ResponseEntity<QueryEntitySchemaDto> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/eval_summaries/detailed?test_suite_id=" + suite.getId()),
                QueryEntitySchemaDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        QueryEntitySchemaDto schema = response.getBody();
        assertThat(schema).isNotNull();
        assertThat(schema.fields())
                .contains(
                        new QuerySchemaFieldDto("test_suite_run_id", QueryFieldType.UUID, "test_suite_run_id"),
                        new QuerySchemaFieldDto("data:question", QueryFieldType.STRING, "test_case_data"),
                        new QuerySchemaFieldDto("data:expectedScore", QueryFieldType.DECIMAL, "test_case_data"),
                        new QuerySchemaFieldDto("response:answer", QueryFieldType.STRING, "extracted_columns"),
                        new QuerySchemaFieldDto("metric:Accuracy:score", QueryFieldType.DECIMAL, "metric_values"),
                        new QuerySchemaFieldDto("metric:Accuracy:explanation", QueryFieldType.DECIMAL, "metric_values"),
                        new QuerySchemaFieldDto("metricInfo:Accuracy", QueryFieldType.OBJECT, "metric_infos"))
                .noneMatch(field -> field.name().equals("test_case_data"))
                .noneMatch(field -> field.name().equals("metric_values"))
                .noneMatch(field -> field.name().equals("metric_infos"));
    }

    @Test
    @DisplayName("returns 404 for an unknown entity name")
    void shouldReturn404_whenEntityUnknown() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(queriesUrl("/entities/schema/unknown_entity"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("returns 400 when a detailed schema is requested for a simple entity")
    void shouldReturn400_whenDetailedSchemaRequestedForSimpleEntity() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/test_suites/detailed?test_suite_id=" + UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("returns 404 when the detailed schema suite does not exist")
    void shouldReturn404_whenSuiteMissing() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/eval_summaries/detailed?test_suite_id=" + UUID.randomUUID()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("returns 400 when the detailed schema id is not a UUID")
    void shouldReturn400_whenSuiteIdMalformed() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/eval_summaries/detailed?test_suite_id=not-a-uuid"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
