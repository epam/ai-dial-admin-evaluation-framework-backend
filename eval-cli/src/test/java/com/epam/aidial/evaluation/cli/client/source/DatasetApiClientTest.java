package com.epam.aidial.evaluation.cli.client.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class DatasetApiClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private DatasetApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://source-ef");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new DatasetApiClient(builder.build());
    }

    @Test
    @DisplayName("fetchTestCaseSchema returns the schema with perTurn scope preserved")
    void fetchTestCaseSchemaReturnsSchemaWithPerTurnPreserved() throws Exception {
        final UUID datasetId = UUID.randomUUID();
        final FieldDefinitionDto sharedField = FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .required(true)
                .build();
        final FieldDefinitionDto perTurnField = FieldDefinitionDto.builder()
                .name("turnPrompt")
                .type(SchemaFieldType.STRING)
                .perTurn(true)
                .build();
        final String body = "{\"id\":\"" + datasetId + "\",\"name\":\"My Dataset\",\"visibility\":\"PUBLIC\","
                + "\"testCaseSchema\":"
                + OBJECT_MAPPER.writeValueAsString(List.of(sharedField, perTurnField)) + "}";

        mockServer
                .expect(requestTo("http://source-ef/api/v1/datasets/" + datasetId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        final List<FieldDefinitionDto> result = client.fetchTestCaseSchema(datasetId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("prompt");
        assertThat(result.get(0).getPerTurn()).isNull();
        assertThat(result.get(1).getName()).isEqualTo("turnPrompt");
        assertThat(result.get(1).getPerTurn()).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("fetchTestCaseSchema returns empty list when the dataset declares no schema")
    void fetchTestCaseSchemaReturnsEmptyWhenNoSchema() throws Exception {
        final UUID datasetId = UUID.randomUUID();
        final String body = "{\"id\":\"" + datasetId + "\",\"name\":\"My Dataset\"}";

        mockServer
                .expect(requestTo("http://source-ef/api/v1/datasets/" + datasetId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        final List<FieldDefinitionDto> result = client.fetchTestCaseSchema(datasetId);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("fetchTestCaseSchema ignores unknown response properties")
    void fetchTestCaseSchemaIgnoresUnknownProperties() throws Exception {
        final UUID datasetId = UUID.randomUUID();
        final String body = "{\"id\":\"" + datasetId + "\",\"name\":\"My Dataset\",\"visibility\":\"PRIVATE\","
                + "\"valid\":true,\"version\":3,\"createdBy\":\"someone@example.com\","
                + "\"validationWarnings\":[{\"fieldName\":\"x\"}],\"testCaseSchema\":[]}";

        mockServer
                .expect(requestTo("http://source-ef/api/v1/datasets/" + datasetId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        final List<FieldDefinitionDto> result = client.fetchTestCaseSchema(datasetId);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("fetchTestCaseSchema propagates non-2xx HTTP errors")
    void fetchTestCaseSchemaPropagatesHttpErrors() {
        final UUID datasetId = UUID.randomUUID();

        mockServer
                .expect(requestTo("http://source-ef/api/v1/datasets/" + datasetId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchTestCaseSchema(datasetId))
                .isInstanceOf(org.springframework.web.client.RestClientException.class);
        mockServer.verify();
    }
}
