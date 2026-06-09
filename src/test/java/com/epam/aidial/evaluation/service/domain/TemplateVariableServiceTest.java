package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TemplateVariableService — type inference tests")
class TemplateVariableServiceTest {

    private final TemplateVariableExtractor extractor = new TemplateVariableExtractor();
    private final EndpointSchemaExtractor endpointSchemaExtractor = new EndpointSchemaExtractor();
    private final TemplateVariableResolver resolver = new TemplateVariableResolver();

    // Null deps (testSuiteRepository, datasetSchemaProvider, jsonbMapper) are safe: only
    // resolveVariables(...) is exercised below, and that overload does not touch them.
    private final TemplateVariableService service =
            new TemplateVariableService(null, null, extractor, endpointSchemaExtractor, null, resolver);

    @Nested
    @DisplayName("effectiveType resolution")
    class EffectiveTypeResolution {

        @Test
        @DisplayName("Should use declaredType as highest priority")
        void shouldUseDeclaredTypeAsHighestPriority() {
            // ${{doc|file}} with binding to STRING-typed schema field
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("doc", "${{doc|file}}"))
                            .build())
                    .build();
            var bindings = List.of(InputBindingDto.builder()
                    .templateVariable("doc")
                    .dataField("title")
                    .build());
            var schema = List.of(FieldDefinitionDto.builder()
                    .name("title")
                    .type(SchemaFieldType.STRING)
                    .build());

            List<TemplateVariableDto> vars = service.resolveVariables(template, bindings, schema, null, null);

            assertThat(vars).hasSize(1);
            assertThat(vars.get(0).getDeclaredType()).isEqualTo(SchemaFieldType.FILE);
            assertThat(vars.get(0).getEffectiveType()).isEqualTo(SchemaFieldType.FILE);
        }

        @Test
        @DisplayName("Should use endpointRef schema when no declaredType")
        void shouldUseEndpointRefSchemaWhenNoDeclaredType() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("prompt", "${{prompt}}"))
                            .build())
                    .build();
            var endpoint = EndpointContractDto.builder()
                    .method(HttpMethod.POST)
                    .relativeUrlPattern("/v1/chat")
                    .requestBodySchema(JsonRequestBodySchemaDto.builder()
                            .schema(Map.of("type", "object", "properties", Map.of("prompt", Map.of("type", "string"))))
                            .build())
                    .build();

            List<TemplateVariableDto> vars = service.resolveVariables(template, List.of(), List.of(), endpoint, null);

            assertThat(vars).hasSize(1);
            assertThat(vars.get(0).getDeclaredType()).isNull();
            assertThat(vars.get(0).getEffectiveType()).isEqualTo(SchemaFieldType.STRING);
        }

        @Test
        @DisplayName("Should use binding+testCaseSchema when no declaredType and no endpointRef match")
        void shouldUseBindingSchemaWhenNoDeclaredTypeAndNoEndpoint() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("doc", "${{doc}}"))
                            .build())
                    .build();
            var bindings = List.of(InputBindingDto.builder()
                    .templateVariable("doc")
                    .dataField("input_doc")
                    .build());
            var schema = List.of(FieldDefinitionDto.builder()
                    .name("input_doc")
                    .type(SchemaFieldType.FILE)
                    .build());

            List<TemplateVariableDto> vars = service.resolveVariables(template, bindings, schema, null, null);

            assertThat(vars).hasSize(1);
            assertThat(vars.get(0).getDeclaredType()).isNull();
            assertThat(vars.get(0).getEffectiveType()).isEqualTo(SchemaFieldType.FILE);
        }

        @Test
        @DisplayName("Should fall back to STRING when no declaredType, no endpoint, and no binding")
        void shouldFallBackToStringWhenNothingMatches() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("prompt", "${{prompt}}"))
                            .build())
                    .build();

            List<TemplateVariableDto> vars = service.resolveVariables(template, List.of(), List.of(), null, null);

            assertThat(vars).hasSize(1);
            assertThat(vars.get(0).getDeclaredType()).isNull();
            assertThat(vars.get(0).getEffectiveType()).isEqualTo(SchemaFieldType.STRING);
        }

        @Test
        @DisplayName("Should fall back to STRING for constantValue binding with no declaredType and no endpoint match")
        void shouldFallBackToStringForConstantValueBindingNoDeclaredType() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("model", "${{model}}"))
                            .build())
                    .build();
            var bindings = List.of(InputBindingDto.builder()
                    .templateVariable("model")
                    .constantValue("gpt-4")
                    .build());

            List<TemplateVariableDto> vars = service.resolveVariables(template, bindings, List.of(), null, null);

            assertThat(vars).hasSize(1);
            assertThat(vars.get(0).getDeclaredType()).isNull();
            assertThat(vars.get(0).getEffectiveType()).isEqualTo(SchemaFieldType.STRING);
        }

        @Test
        @DisplayName("Should propagate declaredType even when effectiveType comes from declared")
        void shouldPropagateDeclaredType() {
            var template = RequestTemplateDto.builder()
                    .body(JsonRequestBodyDto.builder()
                            .content(Map.of("temp", "${{temp|number:0.7}}"))
                            .build())
                    .build();

            List<TemplateVariableDto> vars = service.resolveVariables(template, List.of(), List.of(), null, null);

            assertThat(vars).hasSize(1);
            assertThat(vars.get(0).getDeclaredType()).isEqualTo(SchemaFieldType.NUMBER);
            assertThat(vars.get(0).getEffectiveType()).isEqualTo(SchemaFieldType.NUMBER);
            assertThat(vars.get(0).isHasDefault()).isTrue();
            assertThat(vars.get(0).getDefaultValue()).isEqualTo("0.7");
        }
    }

    /**
     * Tests for {@link TemplateVariableService#getTemplateVariables(UUID)} — the entry point that
     * resolves the dataset-scoped schema via {@link DatasetSchemaProvider}. Requires real (or mocked)
     * {@link TestSuiteRepository}, {@link DatasetSchemaProvider}, and {@link JsonbMapper} dependencies.
     */
    @Nested
    @DisplayName("getTemplateVariables(testSuiteId) — dataset-rooted schema lookup")
    class DatasetRootedSchemaLookup {

        private final TestSuiteRepository testSuiteRepository = mock(TestSuiteRepository.class);
        private final DatasetSchemaProvider datasetSchemaProvider = mock(DatasetSchemaProvider.class);
        private final JsonbMapper jsonbMapper = new JsonbMapper(new ObjectMapper());

        private final TemplateVariableService wiredService = new TemplateVariableService(
                testSuiteRepository, datasetSchemaProvider, extractor, endpointSchemaExtractor, jsonbMapper, resolver);

        @Test
        @DisplayName("uses dataset schema (looked up via suite's datasetId) for type inference")
        void usesDatasetSchemaForTypeInference() {
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .id(suiteId)
                    .datasetId(datasetId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .requestTemplate(
                            "{\"body\":{\"contentType\":\"application/json\",\"content\":{\"doc\":\"${{doc}}\"}}}")
                    .inputBindings("[{\"templateVariable\":\"doc\",\"dataField\":\"input_doc\"}]")
                    .endpointRef(null)
                    .build();
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(suite));
            when(datasetSchemaProvider.getSchema(datasetId))
                    .thenReturn(List.of(FieldDefinitionDto.builder()
                            .name("input_doc")
                            .type(SchemaFieldType.FILE)
                            .build()));

            List<TemplateVariableDto> vars = wiredService.getTemplateVariables(suiteId);

            assertThat(vars).hasSize(1);
            assertThat(vars.get(0).getName()).isEqualTo("doc");
            assertThat(vars.get(0).getEffectiveType()).isEqualTo(SchemaFieldType.FILE);
        }

        @Test
        @DisplayName("EntityNotFoundException when suite does not exist")
        void throwsWhenSuiteMissing() {
            UUID suiteId = UUID.randomUUID();
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> wiredService.getTemplateVariables(suiteId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(suiteId.toString());
        }

        @Test
        @DisplayName("MCP_TOOL suite uses argumentTemplate path and dataset schema for type inference")
        void mcpToolSuiteUsesArgumentTemplate() {
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .id(suiteId)
                    .datasetId(datasetId)
                    .suiteType(SuiteType.MCP_TOOL)
                    .argumentTemplate("{\"arguments\":{\"query\":\"${{query}}\"}}")
                    .inputBindings("[]")
                    .build();
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(suite));
            when(datasetSchemaProvider.getSchema(datasetId))
                    .thenReturn(List.of(FieldDefinitionDto.builder()
                            .name("query")
                            .type(SchemaFieldType.STRING)
                            .build()));

            List<TemplateVariableDto> vars = wiredService.getTemplateVariables(suiteId);

            assertThat(vars).hasSize(1);
            assertThat(vars.get(0).getName()).isEqualTo("query");
            assertThat(vars.get(0).getEffectiveType()).isEqualTo(SchemaFieldType.STRING);
        }
    }
}
