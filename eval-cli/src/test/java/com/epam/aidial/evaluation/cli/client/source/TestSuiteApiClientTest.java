package com.epam.aidial.evaluation.cli.client.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.cli.client.source.dto.PageResponseDto;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteUpdateResultDto;
import java.util.List;
import java.util.Optional;
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

class TestSuiteApiClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private TestSuiteApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://source-ef");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new TestSuiteApiClient(builder.build());
    }

    @Test
    @DisplayName("findById returns suite when found")
    void findByIdReturnsSuiteWhenFound() throws Exception {
        final UUID suiteId = UUID.randomUUID();
        TestSuiteResponseDto expected =
                TestSuiteResponseDto.builder().id(suiteId).name("My Suite").build();

        mockServer
                .expect(requestTo("http://source-ef/api/v1/test-suites/" + suiteId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(OBJECT_MAPPER.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        final Optional<TestSuiteResponseDto> result = client.findById(suiteId);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(suiteId);
        assertThat(result.get().getName()).isEqualTo("My Suite");
        mockServer.verify();
    }

    @Test
    @DisplayName("findById returns empty on 404")
    void findByIdReturnsEmptyOnNotFound() {
        final UUID suiteId = UUID.randomUUID();

        mockServer
                .expect(requestTo("http://source-ef/api/v1/test-suites/" + suiteId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        final Optional<TestSuiteResponseDto> result = client.findById(suiteId);

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("findByExactName sends a well-formed name:eq:<value> filter and returns the first match")
    void findByExactNameSendsWellFormedFilterAndReturnsMatch() throws Exception {
        final UUID suiteId = UUID.randomUUID();
        final String exactName = "eval_partial run";
        final TestSuiteResponseDto expected =
                TestSuiteResponseDto.builder().id(suiteId).name(exactName).build();
        final PageResponseDto<TestSuiteResponseDto> page = PageResponseDto.<TestSuiteResponseDto>builder()
                .content(List.of(expected))
                .build();

        mockServer
                .expect(requestTo("http://source-ef/api/v1/test-suites?filter=name:eq:eval_partial%20run&size=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(OBJECT_MAPPER.writeValueAsString(page), MediaType.APPLICATION_JSON));

        final Optional<TestSuiteResponseDto> result = client.findByExactName(exactName);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(suiteId);
        mockServer.verify();
    }

    @Test
    @DisplayName("findByExactName returns empty when no suite matches")
    void findByExactNameReturnsEmptyWhenNoMatch() throws Exception {
        final PageResponseDto<TestSuiteResponseDto> emptyPage = PageResponseDto.<TestSuiteResponseDto>builder()
                .content(List.of())
                .build();

        mockServer
                .expect(requestTo("http://source-ef/api/v1/test-suites?filter=name:eq:missing&size=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(OBJECT_MAPPER.writeValueAsString(emptyPage), MediaType.APPLICATION_JSON));

        final Optional<TestSuiteResponseDto> result = client.findByExactName("missing");

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("clone unwraps the suite nested under the TestSuiteUpdateResultDto envelope")
    void cloneReturnsNewSuite() throws Exception {
        final UUID sourceSuiteId = UUID.randomUUID();
        final UUID cloneId = UUID.randomUUID();
        final TestSuiteResponseDto expectedSuite =
                TestSuiteResponseDto.builder().id(cloneId).name("ci_My Suite").build();
        final TestSuiteUpdateResultDto envelope =
                TestSuiteUpdateResultDto.builder().suite(expectedSuite).build();

        mockServer
                .expect(requestTo("http://source-ef/api/v1/test-suites/" + sourceSuiteId + "/clone"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(OBJECT_MAPPER.writeValueAsString(envelope), MediaType.APPLICATION_JSON));

        TestSuiteCloneRequestDto request =
                TestSuiteCloneRequestDto.builder().name("ci_My Suite").build();
        final TestSuiteResponseDto result = client.clone(sourceSuiteId, request);

        assertThat(result.getId()).isEqualTo(cloneId);
        assertThat(result.getName()).isEqualTo("ci_My Suite");
        mockServer.verify();
    }

    @Test
    @DisplayName("clone propagates non-2xx HTTP errors")
    void clonePropagatesHttpErrors() {
        final UUID sourceSuiteId = UUID.randomUUID();

        mockServer
                .expect(requestTo("http://source-ef/api/v1/test-suites/" + sourceSuiteId + "/clone"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        TestSuiteCloneRequestDto request =
                TestSuiteCloneRequestDto.builder().name("ci_My Suite").build();
        assertThatThrownBy(() -> client.clone(sourceSuiteId, request))
                .isInstanceOf(org.springframework.web.client.RestClientException.class);
        mockServer.verify();
    }
}
