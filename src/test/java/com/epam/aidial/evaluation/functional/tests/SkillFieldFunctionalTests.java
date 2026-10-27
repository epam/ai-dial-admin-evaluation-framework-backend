package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.DatasetCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutWithVariablesRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SKILL Field Functional Tests")
public abstract class SkillFieldFunctionalTests extends BaseFunctionalTest {

    private static final String SKILL_REF = "skills/my-bucket/summariser";
    private static final String SKILL_FIELD = "skillField";

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private DialFileClient dialFileClient;

    // -----------------------------------------------------------------------
    // schema + test case data
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SKILL column type and a well-formed value round-trip through the dataset and test case APIs")
    void skillColumnTypeAndValueRoundTrip() {
        final UUID datasetId = newDataset(skillSchema());

        final ResponseEntity<TestCaseResponseDto> created =
                createTestCase(datasetId, "TC", Map.of("prompt", "hi", SKILL_FIELD, SKILL_REF));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().isValid()).isTrue();
        assertThat(created.getBody().getValidationWarnings()).isEmpty();
        assertThat(typeOf(getDataset(datasetId), SKILL_FIELD)).isEqualTo(SchemaFieldType.SKILL);
        assertThat(listTestCases(datasetId))
                .singleElement()
                .satisfies(tc -> assertThat(tc.getData()).containsEntry(SKILL_FIELD, SKILL_REF));
    }

    @Test
    @DisplayName("A malformed SKILL value is saved with a TYPE validation warning, not rejected")
    void malformedSkillValueIsSavedWithTypeWarning() {
        final UUID datasetId = newDataset(skillSchema());

        final ResponseEntity<TestCaseResponseDto> created =
                createTestCase(datasetId, "TC", Map.of("prompt", "hi", SKILL_FIELD, "my-bucket/summariser"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().isValid()).isFalse();
        assertThat(created.getBody().getValidationWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.TYPE
                        && w.getMessage().startsWith("SKILL field 'skillField': "));
        assertThat(created.getBody().getData()).containsEntry(SKILL_FIELD, "my-bucket/summariser");
    }

    @Test
    @DisplayName("PATCH replaces a SKILL value verbatim and the case stays valid")
    void patchReplacesSkillValueVerbatim() {
        final UUID datasetId = newDataset(skillSchema());
        final TestCaseResponseDto created = createTestCase(
                        datasetId, "TC", Map.of("prompt", "hi", SKILL_FIELD, SKILL_REF))
                .getBody();
        assertThat(created).isNotNull();

        final ResponseEntity<TestCaseResponseDto> patched = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases/" + created.getId()),
                HttpMethod.PATCH,
                jsonEntity(Map.of("data", Map.of("prompt", "hi", SKILL_FIELD, "skills/my-bucket/team/other"))),
                TestCaseResponseDto.class);

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody()).isNotNull();
        assertThat(patched.getBody().isValid()).isTrue();
        assertThat(patched.getBody().getData()).containsEntry(SKILL_FIELD, "skills/my-bucket/team/other");
    }

    // -----------------------------------------------------------------------
    // CSV import / export
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Exporting a dataset whose only reference column is SKILL returns a plain CSV with the value verbatim")
    void csvExportOfSkillOnlyDatasetIsPlainCsv() {
        final UUID datasetId = newDataset(skillSchema());
        createTestCase(datasetId, "TC", Map.of("prompt", "hi", SKILL_FIELD, SKILL_REF));

        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv; charset=UTF-8")));
        final ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases/export.csv"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(response.getBody())
                .contains("testCaseName")
                .contains(SKILL_FIELD)
                .contains(SKILL_REF);
        verify(dialFileClient, never()).download(anyString());
    }

    @Test
    @DisplayName("Importing a CSV into an existing SKILL column keeps each cell verbatim and the column type")
    void csvImportIntoSkillColumnKeepsCellVerbatim() {
        final UUID datasetId = newDataset(skillSchema());
        final String cell = SKILL_REF + "/";

        final ResponseEntity<CsvImportResultDto> response =
                importCsv(datasetId, "testCaseName,prompt,skillField\nTC1,hello," + cell, "APPEND");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(typeOf(getDataset(datasetId), SKILL_FIELD)).isEqualTo(SchemaFieldType.SKILL);
        assertThat(listTestCases(datasetId))
                .singleElement()
                .satisfies(tc -> assertThat(tc.getData()).containsEntry(SKILL_FIELD, cell));
    }

    @Test
    @DisplayName("CSV schema detection never infers SKILL from a skills/... cell — the column stays STRING")
    void csvSchemaDetectionDoesNotInferSkillType() {
        final UUID datasetId = newDataset(List.of());

        final ResponseEntity<CsvImportResultDto> response =
                importCsv(datasetId, "testCaseName,prompt,skillField\nTC1,hello," + SKILL_REF, "OVERRIDE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(typeOf(getDataset(datasetId), SKILL_FIELD)).isEqualTo(SchemaFieldType.STRING);
        assertThat(listTestCases(datasetId))
                .singleElement()
                .satisfies(tc -> assertThat(tc.getData()).containsEntry(SKILL_FIELD, SKILL_REF));
    }

    // -----------------------------------------------------------------------
    // ZIP export / import
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ZIP export of FILE + SKILL columns packs only the FILE value; import restores both types and the"
            + " verbatim SKILL value")
    void zipRoundTripPacksOnlyFileValuesAndRestoresSkillType() throws IOException {
        final TestSuiteResponseDto suite = createSuite(
                "Skill Zip Suite " + UUID.randomUUID(),
                List.of(
                        field("prompt", SchemaFieldType.STRING),
                        field("document", SchemaFieldType.FILE),
                        field(SKILL_FIELD, SchemaFieldType.SKILL)),
                null,
                List.of());
        final UUID datasetId = suite.getDatasetId();
        final FileMetadataDto file = uploadFileToSuite(suite.getId(), "report.txt", "Report content");
        createTestCase(datasetId, "TC", Map.of("prompt", "hi", "document", file.getPath(), SKILL_FIELD, SKILL_REF));

        final ResponseEntity<byte[]> export =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/test-cases/export.csv"), byte[].class);

        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        final Map<String, byte[]> entries = readZipEntries(export.getBody());
        assertThat(entries.keySet().stream().filter(k -> k.startsWith("files/")).count())
                .isEqualTo(1);
        assertThat(new String(entries.get("test-cases.csv"), StandardCharsets.UTF_8))
                .contains(SKILL_REF);
        final JsonNode manifestSchema =
                objectMapper.readTree(entries.get("manifest.json")).path("testCaseSchema");
        assertThat(manifestSchema.toString()).contains("\"type\":\"SKILL\"").contains("\"type\":\"FILE\"");

        final UUID targetDatasetId = newDataset(List.of());
        final ResponseEntity<CsvImportResultDto> imported =
                importFile(targetDatasetId, export.getBody(), "export.zip", "OVERRIDE");

        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        final DatasetResponseDto target = getDataset(targetDatasetId);
        assertThat(typeOf(target, SKILL_FIELD)).isEqualTo(SchemaFieldType.SKILL);
        assertThat(typeOf(target, "document")).isEqualTo(SchemaFieldType.FILE);
        assertThat(listTestCases(targetDatasetId))
                .singleElement()
                .satisfies(tc -> assertThat(tc.getData()).containsEntry(SKILL_FIELD, SKILL_REF));
    }

    // -----------------------------------------------------------------------
    // clone + snapshot + run
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Cloning a dataset keeps the SKILL column type and the stored values, with no file copy")
    void datasetCloneKeepsSkillColumnAndValues() {
        final UUID datasetId = newDataset(skillSchema());
        createTestCase(datasetId, "TC", Map.of("prompt", "hi", SKILL_FIELD, SKILL_REF));

        final ResponseEntity<DatasetResponseDto> clone = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/clone"),
                jsonEntity(new DatasetCloneRequestDto()),
                DatasetResponseDto.class);

        assertThat(clone.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(clone.getBody()).isNotNull();
        final UUID cloneId = clone.getBody().getId();
        assertThat(typeOf(getDataset(cloneId), SKILL_FIELD)).isEqualTo(SchemaFieldType.SKILL);
        assertThat(listTestCases(cloneId))
                .singleElement()
                .satisfies(tc -> assertThat(tc.getData()).containsEntry(SKILL_FIELD, SKILL_REF));
        verify(dialFileClient, never()).upload(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("A run snapshots the SKILL column and sends the canonical skill ref for a hintless placeholder"
            + " bound to it")
    void runSnapshotsSkillColumnAndSendsCanonicalRef() {
        final TestSuiteResponseDto suite = createSuite(
                "Skill Run Suite " + UUID.randomUUID(),
                skillSchema(),
                bodyTemplate(Map.of("skill", "${{s}}")),
                List.of(InputBindingDto.builder()
                        .templateVariable("s")
                        .dataField(SKILL_FIELD)
                        .build()));
        createTestCase(suite.getDatasetId(), "TC", Map.of("prompt", "hi", SKILL_FIELD, SKILL_REF + "/"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(successResult());

        final UUID runId = createRun(suite.getId());
        final TestSuiteRunResponseDto terminal = awaitRunTerminal(runId, 20);

        assertThat(terminal.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(terminal.getSuiteSnapshot().getTestCaseSchema())
                .anyMatch(f -> SKILL_FIELD.equals(f.getName()) && f.getType() == SchemaFieldType.SKILL);
        final ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(deploymentInvoker).invokeWithStreaming(any(), any(), any(), any(), bodyCaptor.capture());
        final String sentBody = String.valueOf(bodyCaptor.getValue());
        assertThat(sentBody).contains(SKILL_REF).doesNotContain(SKILL_REF + "/").doesNotContain("files/");
        verify(dialFileClient, never()).download(anyString());
    }

    // -----------------------------------------------------------------------
    // template variables + try-out
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Template variables report SKILL as declaredType for |skill and as effectiveType for a column-bound"
            + " hintless variable")
    void templateVariablesReportSkillTypes() {
        final TestSuiteResponseDto suite = createSuite(
                "Skill Vars Suite " + UUID.randomUUID(),
                skillSchema(),
                bodyTemplate(Map.of("explicit", "${{s|skill}}", "bound", "${{t}}")),
                List.of(InputBindingDto.builder()
                        .templateVariable("t")
                        .dataField(SKILL_FIELD)
                        .build()));

        final ResponseEntity<Map<String, List<TemplateVariableDto>>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        final List<TemplateVariableDto> variables = response.getBody().get("0");
        final TemplateVariableDto explicit = variable(variables, "s");
        assertThat(explicit.getDeclaredType()).isEqualTo(SchemaFieldType.SKILL);
        assertThat(explicit.getEffectiveType()).isEqualTo(SchemaFieldType.SKILL);
        final TemplateVariableDto bound = variable(variables, "t");
        assertThat(bound.getDeclaredType()).isNull();
        assertThat(bound.getEffectiveType()).isEqualTo(SchemaFieldType.SKILL);
    }

    @Test
    @DisplayName(
            "Test-case try-out sends the canonical skill ref for a column-bound placeholder and downloads" + " nothing")
    void testCaseTryItOutSendsCanonicalSkillRef() {
        final TestSuiteResponseDto suite = createSuite(
                "Skill TryOut Suite " + UUID.randomUUID(),
                skillSchema(),
                bodyTemplate(Map.of("skill", "${{s}}")),
                List.of(InputBindingDto.builder()
                        .templateVariable("s")
                        .dataField(SKILL_FIELD)
                        .build()));
        final TestCaseResponseDto testCase = createTestCase(
                        suite.getDatasetId(), "TC", Map.of("prompt", "hi", SKILL_FIELD, SKILL_REF + "/"))
                .getBody();
        assertThat(testCase).isNotNull();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(successResult());

        final ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + testCase.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(resolvedSkill(response.getBody())).isEqualTo(SKILL_REF);
        verify(dialFileClient, never()).download(anyString());
    }

    @Test
    @DisplayName("Variables try-out canonicalises a |skill variable supplied as a constant")
    void variablesTryItOutCanonicalisesExplicitSkillVariable() {
        final TestSuiteResponseDto suite = createSuite(
                "Skill Vars TryOut Suite " + UUID.randomUUID(),
                skillSchema(),
                bodyTemplate(Map.of("skill", "${{s|skill}}")),
                List.of());
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(successResult());

        final ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"),
                jsonEntity(TryItOutWithVariablesRequestDto.builder()
                        .variables(Map.of(0, Map.of("s", SKILL_REF + "/")))
                        .build()),
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(resolvedSkill(response.getBody())).isEqualTo(SKILL_REF);
    }

    @Test
    @DisplayName("Variables try-out canonicalises a hintless variable whose suite binding names a SKILL column")
    void variablesTryItOutCanonicalisesHintlessSkillColumnVariable() {
        final TestSuiteResponseDto suite = createSuite(
                "Skill Col TryOut Suite " + UUID.randomUUID(),
                skillSchema(),
                bodyTemplate(Map.of("skill", "${{s}}")),
                List.of(InputBindingDto.builder()
                        .templateVariable("s")
                        .dataField(SKILL_FIELD)
                        .build()));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(successResult());

        final ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"),
                jsonEntity(TryItOutWithVariablesRequestDto.builder()
                        .variables(Map.of(0, Map.of("s", " " + SKILL_REF + "/ ")))
                        .build()),
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(resolvedSkill(response.getBody())).isEqualTo(SKILL_REF);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static FieldDefinitionDto field(String name, SchemaFieldType type) {
        return FieldDefinitionDto.builder().name(name).type(type).build();
    }

    private static List<FieldDefinitionDto> skillSchema() {
        return List.of(
                FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build(),
                field(SKILL_FIELD, SchemaFieldType.SKILL));
    }

    private static SchemaFieldType typeOf(DatasetResponseDto dataset, String fieldName) {
        return dataset.getTestCaseSchema().stream()
                .filter(f -> fieldName.equals(f.getName()))
                .map(FieldDefinitionDto::getType)
                .findFirst()
                .orElseThrow();
    }

    private static TemplateVariableDto variable(List<TemplateVariableDto> variables, String name) {
        return variables.stream()
                .filter(v -> name.equals(v.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static Object resolvedSkill(TryItOutResponseDto response) {
        return ((ResolvedJsonBodyDto) response.getResolvedRequest().getBody())
                .getContent()
                .get("skill");
    }

    private static RequestTemplateDto bodyTemplate(Map<String, Object> content) {
        return RequestTemplateDto.builder()
                .urlTemplate("/chat/completions")
                .body(JsonRequestBodyDto.builder().content(content).build())
                .build();
    }

    private static DeploymentInvocationResult successResult() {
        return new DeploymentInvocationResult(200, false, Map.of("id", "mock-1"), null, new HttpHeaders());
    }

    private UUID newDataset(List<FieldDefinitionDto> schema) {
        try {
            final String schemaJson = objectMapper.writeValueAsString(schema);
            final Dataset dataset = metaTestDataHelper.createDataset("skill-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private TestSuiteResponseDto createSuite(
            String name, List<FieldDefinitionDto> schema, RequestTemplateDto template, List<InputBindingDto> bindings) {
        final TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("D1")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/chat/completions")
                        .build())
                .datasetId(newDataset(schema))
                .requestTemplate(template)
                .inputBindings(bindings)
                .build();
        final ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ResponseEntity<TestCaseResponseDto> createTestCase(UUID datasetId, String name, Map<String, Object> data) {
        return restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases?includeWarnings=true"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(data)
                        .build()),
                TestCaseResponseDto.class);
    }

    private DatasetResponseDto getDataset(UUID datasetId) {
        final ResponseEntity<DatasetResponseDto> response =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId), DatasetResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<TestCaseResponseDto> listTestCases(UUID datasetId) {
        final ResponseEntity<PageResponseDto<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<PageResponseDto<TestCaseResponseDto>>() {});
        assertThat(response.getBody()).isNotNull();
        return response.getBody().getContent();
    }

    private ResponseEntity<CsvImportResultDto> importCsv(UUID datasetId, String csv, String importMode) {
        return importFile(datasetId, csv.getBytes(StandardCharsets.UTF_8), "test.csv", importMode);
    }

    private ResponseEntity<CsvImportResultDto> importFile(
            UUID datasetId, byte[] content, String filename, String importMode) {
        final URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + "/test-cases/import"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", "FAIL")
                .build()
                .toUri();
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", namedResource(content, filename));
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.postForEntity(uri, new HttpEntity<>(body, headers), CsvImportResultDto.class);
    }

    private FileMetadataDto uploadFileToSuite(UUID suiteId, String filename, String content) {
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", namedResource(content.getBytes(StandardCharsets.UTF_8), filename));
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        final ResponseEntity<FileMetadataDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/files"), new HttpEntity<>(body, headers), FileMetadataDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static ByteArrayResource namedResource(byte[] content, String filename) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static Map<String, byte[]> readZipEntries(byte[] zipBytes) throws IOException {
        final Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return entries;
    }

    private UUID createRun(UUID suiteId) {
        final ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().getId();
    }

    private TestSuiteRunResponseDto awaitRunTerminal(UUID runId, int timeoutSeconds) {
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            final ResponseEntity<TestSuiteRunResponseDto> response =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (response.getStatusCode() == HttpStatus.OK
                    && response.getBody() != null
                    && RunStatus.isTerminal(response.getBody().getStatus())) {
                return response.getBody();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling", e);
            }
        }
        throw new AssertionError("Run did not reach terminal status within " + timeoutSeconds + "s");
    }
}
