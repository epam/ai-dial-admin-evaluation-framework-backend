package com.epam.aidial.evaluation.cli.client.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
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

class TestCaseApiClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private TestCaseApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://source-ef");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new TestCaseApiClient(builder.build());
    }

    @Test
    @DisplayName("fetchAll returns all test cases across multiple pages")
    void fetchAllPaginatesCorrectly() throws Exception {
        final UUID datasetId = UUID.randomUUID();
        final UUID tc1 = UUID.randomUUID();
        final UUID tc2 = UUID.randomUUID();

        // First page — exactly 100 items to trigger a second call
        final List<TestCaseResponseDto> page0Content = buildPage(100, tc1);
        final PageResponseDto<TestCaseResponseDto> page0 = new PageResponseDto<>(page0Content, 0, 100, 101L, 2);

        // Second page — 1 item signals last page
        final PageResponseDto<TestCaseResponseDto> page1 = new PageResponseDto<>(
                List.of(TestCaseResponseDto.builder().id(tc2).build()), 1, 100, 101L, 2);

        mockServer
                .expect(requestToUriTemplate(
                        "http://source-ef/api/v1/datasets/{id}/test-cases?page=0&size=100", datasetId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(OBJECT_MAPPER.writeValueAsString(page0), MediaType.APPLICATION_JSON));

        mockServer
                .expect(requestToUriTemplate(
                        "http://source-ef/api/v1/datasets/{id}/test-cases?page=1&size=100", datasetId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(OBJECT_MAPPER.writeValueAsString(page1), MediaType.APPLICATION_JSON));

        final List<TestCaseResponseDto> result = client.fetchAll(datasetId);

        assertThat(result).hasSize(101);
        mockServer.verify();
    }

    @Test
    @DisplayName("fetchAll returns empty list when no test cases exist")
    void fetchAllReturnsEmptyWhenNoTestCases() throws Exception {
        final UUID datasetId = UUID.randomUUID();
        final PageResponseDto<TestCaseResponseDto> emptyPage = new PageResponseDto<>(List.of(), 0, 100, 0L, 0);

        mockServer
                .expect(requestToUriTemplate(
                        "http://source-ef/api/v1/datasets/{id}/test-cases?page=0&size=100", datasetId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(OBJECT_MAPPER.writeValueAsString(emptyPage), MediaType.APPLICATION_JSON));

        final List<TestCaseResponseDto> result = client.fetchAll(datasetId);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("fetchAll propagates non-2xx HTTP errors")
    void fetchAllPropagatesHttpErrors() {
        final UUID datasetId = UUID.randomUUID();

        mockServer
                .expect(requestToUriTemplate(
                        "http://source-ef/api/v1/datasets/{id}/test-cases?page=0&size=100", datasetId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.fetchAll(datasetId))
                .isInstanceOf(org.springframework.web.client.RestClientException.class);
        mockServer.verify();
    }

    private List<TestCaseResponseDto> buildPage(int count, UUID startId) {
        final List<TestCaseResponseDto> list = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(TestCaseResponseDto.builder()
                    .id(i == 0 ? startId : UUID.randomUUID())
                    .build());
        }
        return list;
    }
}
