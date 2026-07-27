package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.HttpChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.McpToolChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ChainNormalizer")
class ChainNormalizerTest {

    private final ObjectMapper objectMapper = JsonMapperConfiguration.createJsonMapper();
    private final JsonbMapper jsonbMapper = new JsonbMapper(objectMapper);
    private final ChainNormalizer normalizer = new ChainNormalizer(jsonbMapper);

    @Nested
    @DisplayName("normalization")
    class Normalization {

        @Test
        @DisplayName("single-request suite normalizes to a one-element chain carrying the flat fields")
        void singleRequestYieldsOneElement() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .endpointRef(endpoint(HttpMethod.POST, "/chat/completions"))
                    .requestTemplate(template("/chat/completions"))
                    .inputBindings(List.of(dataBinding("prompt", "question")))
                    .responseColumns(List.of(column("answer")))
                    .build();

            List<RequestSpec> chain = normalizer.normalize(dto);

            assertThat(chain).hasSize(1);
            RequestSpec only = chain.getFirst();
            assertThat(only.index()).isZero();
            assertThat(only.type()).isEqualTo(ChainRequestType.HTTP);
            assertThat(only.endpointRef().getRelativeUrlPattern()).isEqualTo("/chat/completions");
            assertThat(only.requestTemplate().getUrlTemplate()).isEqualTo("/chat/completions");
            assertThat(only.safeInputBindings()).hasSize(1);
            assertThat(only.safeResponseColumns())
                    .extracting(ResponseColumnDefinitionDto::getName)
                    .containsExactly("answer");
        }

        @Test
        @DisplayName("three additionalRequests yield contiguous zero-based indices 0..3 preserving array order")
        void indicesAreContiguousAndZeroBased() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .endpointRef(endpoint(HttpMethod.POST, "/session"))
                    .requestTemplate(template("/session"))
                    .additionalRequests(List.of(
                            httpElement("configure", "/configure"),
                            httpElement("invoke", "/chat/completions"),
                            httpElement("teardown", "/session/close")))
                    .build();

            List<RequestSpec> chain = normalizer.normalize(dto);

            assertThat(chain).extracting(RequestSpec::index).containsExactly(0, 1, 2, 3);
            assertThat(chain)
                    .extracting(spec -> spec.requestTemplate().getUrlTemplate())
                    .containsExactly("/session", "/configure", "/chat/completions", "/session/close");
        }

        @Test
        @DisplayName("empty additionalRequests is a single-request chain, same as absent")
        void emptyArrayIsSingleRequest() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/chat/completions"))
                    .additionalRequests(List.of())
                    .build();

            assertThat(normalizer.normalize(dto)).hasSize(1);
        }

        @Test
        @DisplayName("chain element preserves its declared MCP_TOOL type so the save-time guard can reject it")
        void mcpTypeIsPreserved() {
            McpToolChainRequestDto mcp = new McpToolChainRequestDto();
            mcp.setLabel("tool");
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .additionalRequests(List.of(mcp))
                    .build();

            assertThat(normalizer.normalize(dto))
                    .extracting(RequestSpec::type)
                    .containsExactly(ChainRequestType.HTTP, ChainRequestType.MCP_TOOL);
        }

        @Test
        @DisplayName("a null suite normalizes to an empty chain rather than throwing")
        void nullSuiteYieldsEmptyChain() {
            assertThat(normalizer.normalize((TestSuiteRequestDto) null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("label defaulting")
    class LabelDefaulting {

        @Test
        @DisplayName("absent labels default to request-{n} using 1-based positions")
        void absentLabelsAreDefaulted() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .additionalRequests(List.of(httpElement(null, "/b"), httpElement(null, "/c")))
                    .build();

            assertThat(normalizer.normalize(dto))
                    .extracting(RequestSpec::label)
                    .containsExactly("request-1", "request-2", "request-3");
        }

        @Test
        @DisplayName("declared labels are used verbatim, mixed freely with defaults")
        void declaredLabelsWin() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestLabel("setup")
                    .requestTemplate(template("/a"))
                    .additionalRequests(List.of(httpElement(null, "/b"), httpElement("invoke", "/c")))
                    .build();

            assertThat(normalizer.normalize(dto))
                    .extracting(RequestSpec::label)
                    .containsExactly("setup", "request-2", "invoke");
        }

        @Test
        @DisplayName("a blank label is treated as absent and defaulted")
        void blankLabelIsDefaulted() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestLabel("   ")
                    .requestTemplate(template("/a"))
                    .build();

            assertThat(normalizer.normalize(dto).getFirst().label()).isEqualTo("request-1");
        }

        @Test
        @DisplayName("every normalized request has a non-null label, so request_label is never null on a row")
        void everyRequestIsLabeled() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .additionalRequests(List.of(httpElement(null, "/b")))
                    .build();

            assertThat(normalizer.normalize(dto))
                    .allSatisfy(spec -> assertThat(spec.label()).isNotNull().isNotBlank());
        }
    }

    @Nested
    @DisplayName("chain-union response columns")
    class ChainUnion {

        @Test
        @DisplayName("union is every request's columns in chain order")
        void unionPreservesChainOrder() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .responseColumns(List.of(column("session_id")))
                    .additionalRequests(List.of(
                            httpElementWithColumns("configure", "/b", List.of(column("config_id"))),
                            httpElementWithColumns("invoke", "/c", List.of(column("answer"), column("usage")))))
                    .build();

            assertThat(normalizer.chainResponseColumnNames(normalizer.normalize(dto)))
                    .containsExactly("session_id", "config_id", "answer", "usage");
        }

        @Test
        @DisplayName("union of a single-request suite equals its flat responseColumns")
        void unionDegeneratesForSingleRequest() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .responseColumns(List.of(column("answer"), column("usage")))
                    .build();

            assertThat(normalizer.chainResponseColumnNames(normalizer.normalize(dto)))
                    .containsExactly("answer", "usage");
        }

        @Test
        @DisplayName("union contains no repeats even if a duplicate name somehow reaches it")
        void unionDeduplicatesDefensively() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .responseColumns(List.of(column("answer")))
                    .additionalRequests(List.of(httpElementWithColumns("b", "/b", List.of(column("answer")))))
                    .build();

            assertThat(normalizer.chainResponseColumnNames(normalizer.normalize(dto)))
                    .containsExactly("answer");
        }

        @Test
        @DisplayName("union is empty when no request declares any column")
        void unionIsEmptyWhenNoColumns() {
            TestSuiteRequestDto dto = TestSuiteRequestDto.builder()
                    .requestTemplate(template("/a"))
                    .build();

            assertThat(normalizer.chainResponseColumns(normalizer.normalize(dto)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("source equivalence")
    class SourceEquivalence {

        @Test
        @DisplayName("a persisted suite and a snapshot built from it normalize to the same chain")
        void suiteAndSnapshotNormalizeIdentically() {
            List<ChainRequestDto> additional =
                    List.of(httpElement("configure", "/configure"), httpElement("invoke", "/chat/completions"));

            TestSuite suite = TestSuite.builder()
                    .requestLabel("setup")
                    .endpointRef(jsonbMapper.map(endpoint(HttpMethod.POST, "/session")))
                    .requestTemplate(jsonbMapper.map(template("/session")))
                    .inputBindings(jsonbMapper.mapInputBindings(List.of(dataBinding("prompt", "question"))))
                    .responseColumns(jsonbMapper.mapResponseColumns(List.of(column("session_id"))))
                    .additionalRequests(jsonbMapper.mapAdditionalRequests(additional))
                    .build();

            SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                    .requestLabel("setup")
                    .endpointRef(endpoint(HttpMethod.POST, "/session"))
                    .requestTemplate(template("/session"))
                    .inputBindings(List.of(dataBinding("prompt", "question")))
                    .responseColumns(List.of(column("session_id")))
                    .additionalRequests(additional)
                    .build();

            List<RequestSpec> fromSuite = normalizer.normalize(suite);
            List<RequestSpec> fromSnapshot = normalizer.normalize(snapshot);

            assertThat(fromSuite).hasSize(3);
            assertThat(fromSuite)
                    .extracting(RequestSpec::index)
                    .isEqualTo(fromSnapshot.stream().map(RequestSpec::index).toList());
            assertThat(fromSuite)
                    .extracting(RequestSpec::label)
                    .isEqualTo(fromSnapshot.stream().map(RequestSpec::label).toList());
            assertThat(fromSuite)
                    .extracting(spec -> spec.requestTemplate().getUrlTemplate())
                    .isEqualTo(fromSnapshot.stream()
                            .map(spec -> spec.requestTemplate().getUrlTemplate())
                            .toList());
        }

        @Test
        @DisplayName("a snapshot with no additionalRequests is a single-request chain — no version branch needed")
        void snapshotWithoutChainIsSingleRequest() {
            SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                    .endpointRef(endpoint(HttpMethod.POST, "/chat/completions"))
                    .requestTemplate(template("/chat/completions"))
                    .build();

            assertThat(normalizer.normalize(snapshot)).hasSize(1);
            assertThat(normalizer.normalize(snapshot).getFirst().label()).isEqualTo("request-1");
        }
    }

    // ---- fixtures ----

    private static EndpointContractDto endpoint(HttpMethod method, String path) {
        return EndpointContractDto.builder()
                .method(method)
                .relativeUrlPattern(path)
                .build();
    }

    private static RequestTemplateDto template(String urlTemplate) {
        return RequestTemplateDto.builder().urlTemplate(urlTemplate).build();
    }

    private static InputBindingDto dataBinding(String variable, String dataField) {
        return InputBindingDto.builder()
                .templateVariable(variable)
                .dataField(dataField)
                .build();
    }

    private static ResponseColumnDefinitionDto column(String name) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .expression("$." + name)
                .build();
    }

    private static ChainRequestDto httpElement(String label, String urlTemplate) {
        return httpElementWithColumns(label, urlTemplate, List.of());
    }

    private static ChainRequestDto httpElementWithColumns(
            String label, String urlTemplate, List<ResponseColumnDefinitionDto> columns) {
        HttpChainRequestDto element = new HttpChainRequestDto();
        element.setLabel(label);
        element.setEndpointRef(endpoint(HttpMethod.POST, urlTemplate));
        element.setRequestTemplate(template(urlTemplate));
        element.setResponseColumns(columns);
        return element;
    }
}
