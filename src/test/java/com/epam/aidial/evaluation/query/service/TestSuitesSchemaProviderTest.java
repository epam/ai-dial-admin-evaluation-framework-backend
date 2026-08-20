package com.epam.aidial.evaluation.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestSuitesSchemaProviderTest {

    private final TestSuitesSchemaProvider provider = new TestSuitesSchemaProvider(new JooqTableSchemaResolver());

    @Test
    @DisplayName("describes test_suites as a simple entity without a schema id field")
    void shouldDescribeSimpleEntity() {
        assertThat(provider.descriptor()).isEqualTo(new QueryEntityDto("test_suites", false, null));
    }

    @Test
    @DisplayName("exposes a flat base schema including table columns and virtual deployment_ref sub-fields")
    void shouldExposeFlatBaseSchemaWithTableColumnsAndVirtualFields() {
        assertThat(provider.baseSchema())
                .isNotEmpty()
                .contains(
                        new QuerySchemaFieldDto("id", QueryFieldType.UUID, "id"),
                        new QuerySchemaFieldDto("suite_type", QueryFieldType.STRING, "suite_type"),
                        new QuerySchemaFieldDto("dataset_id", QueryFieldType.UUID, "dataset_id"),
                        new QuerySchemaFieldDto("response_columns", QueryFieldType.ARRAY, "response_columns"),
                        new QuerySchemaFieldDto("is_valid", QueryFieldType.BOOLEAN, "is_valid"),
                        new QuerySchemaFieldDto("created_at_ms", QueryFieldType.LONG, "created_at_ms"),
                        new QuerySchemaFieldDto("deployment_ref", QueryFieldType.OBJECT, "deployment_ref"),
                        new QuerySchemaFieldDto("mcp_deployment_ref", QueryFieldType.OBJECT, "mcp_deployment_ref"));
    }

    @Test
    @DisplayName("exposes deployment_ref::id, ::name, ::version, ::type as STRING fields sourced from deployment_ref")
    void shouldExposeDeploymentRefSubFields() {
        assertThat(provider.baseSchema())
                .contains(
                        new QuerySchemaFieldDto("deployment_ref::id", QueryFieldType.STRING, "deployment_ref"),
                        new QuerySchemaFieldDto("deployment_ref::name", QueryFieldType.STRING, "deployment_ref"),
                        new QuerySchemaFieldDto("deployment_ref::version", QueryFieldType.STRING, "deployment_ref"),
                        new QuerySchemaFieldDto("deployment_ref::type", QueryFieldType.STRING, "deployment_ref"));
    }

    @Test
    @DisplayName(
            "exposes mcp_deployment_ref::id, ::name, ::type, ::transport as STRING fields sourced from mcp_deployment_ref")
    void shouldExposeMcpDeploymentRefSubFields() {
        assertThat(provider.baseSchema())
                .contains(
                        new QuerySchemaFieldDto("mcp_deployment_ref::id", QueryFieldType.STRING, "mcp_deployment_ref"),
                        new QuerySchemaFieldDto(
                                "mcp_deployment_ref::name", QueryFieldType.STRING, "mcp_deployment_ref"),
                        new QuerySchemaFieldDto(
                                "mcp_deployment_ref::type", QueryFieldType.STRING, "mcp_deployment_ref"),
                        new QuerySchemaFieldDto(
                                "mcp_deployment_ref::transport", QueryFieldType.STRING, "mcp_deployment_ref"));
    }

    @Test
    @DisplayName("has no detailed schema, being a simple entity")
    void shouldThrowUnsupported_whenDetailedSchemaRequested() {
        assertThatThrownBy(() -> provider.detailedSchema(Map.of("test_suite_id", "any-id")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
