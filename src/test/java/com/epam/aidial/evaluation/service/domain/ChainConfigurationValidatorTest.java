package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteProperties;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.HttpChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.McpToolChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

@DisplayName("ChainConfigurationValidator")
class ChainConfigurationValidatorTest {

    private static final int CAP = 4;

    private final ChainNormalizer normalizer =
            new ChainNormalizer(new JsonbMapper(JsonMapperConfiguration.createJsonMapper()));
    private final ChainConfigurationValidator validator = new ChainConfigurationValidator(properties(CAP));

    @Nested
    @DisplayName("chain length cap")
    class ChainLengthCap {

        @Test
        @DisplayName("a chain exactly at the cap is accepted")
        void atCapIsAccepted() {
            assertThatCode(() -> validate(suiteWithRequestCount(CAP))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a chain over the cap is rejected naming both the length and the cap")
        void overCapIsRejected() {
            assertThatThrownBy(() -> validate(suiteWithRequestCount(CAP + 1)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining(String.valueOf(CAP + 1))
                    .hasMessageContaining(String.valueOf(CAP));
        }

        @Test
        @DisplayName("maxRequests exposes the configured cap for the run-creation re-check")
        void capIsExposed() {
            assertThat(validator.maxRequests()).isEqualTo(CAP);
        }
    }

    @Nested
    @DisplayName("MCP-typed elements")
    class McpElements {

        @Test
        @DisplayName("an MCP_TOOL chain element is rejected, naming the request and stating MCP is unsupported")
        void mcpElementIsRejected() {
            McpToolChainRequestDto mcp = new McpToolChainRequestDto();
            mcp.setLabel("tool");

            assertThatThrownBy(() -> validate(TestSuiteRequestDto.builder()
                            .requestTemplate(template("/a"))
                            .additionalRequests(List.of(mcp))
                            .build()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("MCP_TOOL")
                    .hasMessageContaining("not supported");
        }
    }

    @Nested
    @DisplayName("label uniqueness on the resolved set")
    class LabelUniqueness {

        @Test
        @DisplayName("two chain elements declaring the same explicit label are rejected")
        void duplicateExplicitLabelsRejected() {
            assertThatThrownBy(() -> validate(TestSuiteRequestDto.builder()
                            .requestTemplate(template("/a"))
                            .additionalRequests(List.of(element("invoke", "/b"), element("invoke", "/c")))
                            .build()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("invoke");
        }

        @Test
        @DisplayName("an explicit label colliding with another request's request-{n} default is rejected")
        void explicitLabelCollidingWithDefaultRejected() {
            // request 1 has no label so it defaults to "request-2"; request 2 declares that name explicitly.
            assertThatThrownBy(() -> validate(TestSuiteRequestDto.builder()
                            .requestTemplate(template("/a"))
                            .additionalRequests(List.of(element(null, "/b"), element("request-2", "/c")))
                            .build()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("request-2");
        }

        @Test
        @DisplayName("distinct labels are accepted")
        void distinctLabelsAccepted() {
            assertThatCode(() -> validate(TestSuiteRequestDto.builder()
                            .requestLabel("setup")
                            .requestTemplate(template("/a"))
                            .additionalRequests(List.of(element("configure", "/b"), element("invoke", "/c")))
                            .build()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("chain-wide response column uniqueness")
    class ResponseColumnUniqueness {

        @Test
        @DisplayName("the same column name on two different chain requests is rejected, naming the duplicate")
        void duplicateAcrossRequestsRejected() {
            assertThatThrownBy(() -> validate(TestSuiteRequestDto.builder()
                            .requestTemplate(template("/a"))
                            .responseColumns(List.of(column("answer")))
                            .additionalRequests(List.of(elementWithColumns("invoke", "/b", List.of(column("answer")))))
                            .build()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("answer");
        }

        @Test
        @DisplayName("the same column name twice within one request is rejected")
        void duplicateWithinOneRequestRejected() {
            assertThatThrownBy(() -> validate(TestSuiteRequestDto.builder()
                            .requestTemplate(template("/a"))
                            .responseColumns(List.of(column("answer"), column("answer")))
                            .build()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("answer");
        }

        @Test
        @DisplayName("distinct column names across requests are accepted")
        void distinctNamesAccepted() {
            assertThatCode(() -> validate(TestSuiteRequestDto.builder()
                            .requestTemplate(template("/a"))
                            .responseColumns(List.of(column("session_id")))
                            .additionalRequests(List.of(elementWithColumns("invoke", "/b", List.of(column("answer")))))
                            .build()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("responseField reference rules")
    class ResponseFieldReferences {

        @Test
        @DisplayName("a backward reference to an earlier request's column is accepted")
        void backwardReferenceAccepted() {
            assertThatCode(() -> validate(chainWithReference(1, "session_id", 0)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a reference to any earlier request, not only the predecessor, is accepted")
        void nonAdjacentBackwardReferenceAccepted() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .responseColumns(List.of(column("session_id")))
                    .additionalRequests(List.of(element("mid", "/b"), elementWithBinding("invoke", "/c", "session_id")))
                    .build();

            assertThatCode(() -> validate(dto)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a forward reference is rejected, because sequential execution cannot satisfy it")
        void forwardReferenceRejected() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .inputBindings(List.of(chainBinding("session", "answer")))
                    .additionalRequests(List.of(elementWithColumns("invoke", "/b", List.of(column("answer")))))
                    .build();

            assertThatThrownBy(() -> validate(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("LATER");
        }

        @Test
        @DisplayName("a self reference is rejected — the column must come from a STRICTLY earlier request")
        void selfReferenceRejected() {
            HttpChainRequestDto self = new HttpChainRequestDto();
            self.setLabel("invoke");
            self.setEndpointRef(endpoint("/b"));
            self.setRequestTemplate(template("/b"));
            self.setResponseColumns(List.of(column("answer")));
            self.setInputBindings(List.of(chainBinding("prev", "answer")));

            assertThatThrownBy(() -> validate(TestSuiteRequestDto.builder()
                            .requestTemplate(template("/a"))
                            .additionalRequests(List.of(self))
                            .build()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("same request");
        }

        @Test
        @DisplayName("a reference to a column no request declares is rejected")
        void unknownColumnRejected() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .additionalRequests(List.of(elementWithBinding("invoke", "/b", "nonexistent")))
                    .build();

            assertThatThrownBy(() -> validate(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("no request in the chain declares");
        }

        @Test
        @DisplayName("any responseField on a single-request suite is rejected — no earlier request exists")
        void responseFieldOnSingleRequestRejected() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .inputBindings(List.of(chainBinding("session", "session_id")))
                    .build();

            assertThatThrownBy(() -> validate(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("single-request");
        }
    }

    private void validate(TestSuiteRequestDto dto) {
        validator.validate(normalizer.normalize(dto));
    }

    // ---- fixtures ----

    private static TestSuiteProperties properties(int maxRequests) {
        TestSuiteProperties props = new TestSuiteProperties();
        props.getMultiRequest().setMaxRequests(maxRequests);
        return props;
    }

    /** A suite whose normalized chain has exactly {@code total} requests (request 0 plus total-1 elements). */
    private static TestSuiteRequestDto suiteWithRequestCount(int total) {
        List<ChainRequestDto> extra = new ArrayList<>();
        for (int i = 1; i < total; i++) {
            extra.add(element("request-" + (i + 1), "/r" + i));
        }
        return TestSuiteRequestDto.builder()
                .requestTemplate(template("/r0"))
                .additionalRequests(extra)
                .build();
    }

    private static TestSuiteRequestDto chainWithReference(int consumerIndex, String columnName, int producerIndex) {
        return TestSuiteRequestDto.builder()
                .requestTemplate(template("/r" + producerIndex))
                .responseColumns(List.of(column(columnName)))
                .additionalRequests(List.of(elementWithBinding("consumer", "/r" + consumerIndex, columnName)))
                .build();
    }

    private static EndpointContractDto endpoint(String path) {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern(path)
                .build();
    }

    private static RequestTemplateDto template(String urlTemplate) {
        return RequestTemplateDto.builder().urlTemplate(urlTemplate).build();
    }

    private static ResponseColumnDefinitionDto column(String name) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .expression("$." + name)
                .build();
    }

    private static InputBindingDto chainBinding(String variable, String responseField) {
        return InputBindingDto.builder()
                .templateVariable(variable)
                .responseField(responseField)
                .build();
    }

    private static ChainRequestDto element(String label, String urlTemplate) {
        return elementWithColumns(label, urlTemplate, List.of());
    }

    private static ChainRequestDto elementWithColumns(
            String label, String urlTemplate, List<ResponseColumnDefinitionDto> columns) {
        HttpChainRequestDto element = new HttpChainRequestDto();
        element.setLabel(label);
        element.setEndpointRef(endpoint(urlTemplate));
        element.setRequestTemplate(template(urlTemplate));
        element.setResponseColumns(columns);
        return element;
    }

    private static ChainRequestDto elementWithBinding(String label, String urlTemplate, String responseField) {
        HttpChainRequestDto element = (HttpChainRequestDto) element(label, urlTemplate);
        element.setInputBindings(List.of(chainBinding("prev", responseField)));
        return element;
    }
}
