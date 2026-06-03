package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.validation.ValidationProperties;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("TestCaseValidationService — FILE field validation (DIAL file refs)")
@ExtendWith(MockitoExtension.class)
class TestCaseValidationServiceFileTest {

    private static final UUID TEST_SUITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private TemplateVariableExtractor templateVariableExtractor;

    @Mock
    private ValidationProperties validationProperties;

    @Mock
    private FileRefValidator fileRefValidator;

    @InjectMocks
    private TestCaseValidationService service;

    @BeforeEach
    void setUp() {
        when(validationProperties.getMaxWarningsPerCase()).thenReturn(100);
        when(templateVariableExtractor.extract(any())).thenReturn(List.of());
        // Default: valid ref produces no errors (lenient — not all tests invoke file validation)
        lenient().when(fileRefValidator.validateDatasetOwnership(any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("FILE field with valid short @ef path produces no warning")
    void fileFieldWithValidEfPath_noWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        String validRef = "@ef/suites/" + TEST_SUITE_ID + "/data.csv";
        Map<String, Object> data = Map.of("attachment", validRef);

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> typeWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.TYPE)
                .toList();
        assertThat(typeWarnings).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("FILE field with valid short public path produces no warning")
    void fileFieldWithValidPublicPath_noWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        Map<String, Object> data = Map.of("attachment", "public/datasets/input.csv");

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> typeWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.TYPE)
                .toList();
        assertThat(typeWarnings).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("FILE field with old files/ format produces TYPE warning")
    void fileFieldWithOldFilesFormat_producesTypeWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        String oldRef = "files/@ef/suites/" + TEST_SUITE_ID + "/data.csv";
        Map<String, Object> data = Map.of("attachment", oldRef);
        when(fileRefValidator.validateDatasetOwnership(eq(oldRef), eq(TEST_SUITE_ID)))
                .thenReturn(List.of("File reference uses disallowed prefix 'files': " + oldRef));

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> typeWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.TYPE)
                .toList();
        assertThat(typeWarnings).hasSize(1);
        assertThat(typeWarnings.get(0).getMessage()).contains("disallowed prefix");
        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("FILE field with disallowed prefix produces TYPE warning")
    void fileFieldWithDisallowedPrefix_producesTypeWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        String badRef = "user-bucket/private/doc.pdf";
        Map<String, Object> data = Map.of("attachment", badRef);
        when(fileRefValidator.validateDatasetOwnership(eq(badRef), eq(TEST_SUITE_ID)))
                .thenReturn(List.of("File reference uses disallowed prefix 'user-bucket': " + badRef));

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> typeWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.TYPE)
                .toList();
        assertThat(typeWarnings).hasSize(1);
        assertThat(typeWarnings.get(0).getMessage()).contains("disallowed prefix");
        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("FILE field with null value produces no FILE-specific warning")
    void fileFieldWithNullValue_noWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        Map<String, Object> data = Map.of();

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> typeWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.TYPE)
                .toList();
        assertThat(typeWarnings).isEmpty();
    }

    @Test
    @DisplayName("FILE field with @ef ref pointing to different suite produces TYPE warning")
    void fileFieldWithCrossSuiteRef_producesTypeWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        UUID otherSuiteId = UUID.randomUUID();
        String crossSuiteRef = "@ef/suites/" + otherSuiteId + "/data.csv";
        Map<String, Object> data = Map.of("attachment", crossSuiteRef);
        when(fileRefValidator.validateDatasetOwnership(eq(crossSuiteRef), eq(TEST_SUITE_ID)))
                .thenReturn(List.of("File reference points to a different suite's files: " + crossSuiteRef));

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> typeWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.TYPE)
                .toList();
        assertThat(typeWarnings).hasSize(1);
        assertThat(typeWarnings.get(0).getMessage()).contains("different suite");
        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("Non-FILE field is not validated as file")
    void nonFileField_notValidatedAsFile() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("description")
                .type(SchemaFieldType.STRING)
                .build());
        Map<String, Object> data = Map.of("description", "not-a-dial-ref-but-thats-fine");

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> typeWarnings = result.getWarnings().stream()
                .filter(w -> w.getCode() == ValidationWarningCode.TYPE)
                .toList();
        assertThat(typeWarnings).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Required FILE field missing from data produces REQUIRED warning")
    void requiredFileFieldMissing_producesRequiredWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .required(true)
                .build());
        Map<String, Object> data = Map.of();

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> requiredWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.REQUIRED)
                .toList();
        assertThat(requiredWarnings).hasSize(1);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("Optional FILE field with empty string produces no warning")
    void optionalFileFieldWithEmptyString_noWarning() {
        // "" passes both the blank-skip in validateFileFields (no ref format check)
        // and the type-compatible check (String is valid for FILE type)
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        Map<String, Object> data = Map.of("attachment", "");

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> fileWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()))
                .toList();
        assertThat(fileWarnings).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Required FILE field with empty string produces REQUIRED warning")
    void requiredFileFieldWithEmptyString_producesRequiredWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .required(true)
                .build());
        Map<String, Object> data = Map.of("attachment", "");

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> requiredWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.REQUIRED)
                .toList();
        assertThat(requiredWarnings).hasSize(1);
        assertThat(requiredWarnings.get(0).getMessage()).contains("is missing from data");
        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("Required FILE field with valid file ref produces no warning")
    void requiredFileFieldWithValidRef_noWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .required(true)
                .build());
        String validRef = "@ef/suites/" + TEST_SUITE_ID + "/file.csv";
        Map<String, Object> data = Map.of("attachment", validRef);

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Optional FILE field with whitespace-only string produces no warning")
    void optionalFileFieldWithWhitespaceOnly_noWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        Map<String, Object> data = Map.of("attachment", "   ");

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> fileWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()))
                .toList();
        assertThat(fileWarnings).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Required FILE field with whitespace-only string produces REQUIRED warning")
    void requiredFileFieldWithWhitespaceOnly_producesRequiredWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .required(true)
                .build());
        Map<String, Object> data = Map.of("attachment", "   ");

        ValidationResult result = service.validateTestCase(data, schema, null, null, false, TEST_SUITE_ID);

        List<ValidationWarningDto> requiredWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.REQUIRED)
                .toList();
        assertThat(requiredWarnings).hasSize(1);
        assertThat(requiredWarnings.get(0).getMessage()).contains("is missing from data");
        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("Required binding + FILE field + empty string produces REQUIRED warning (data-vs-binding)")
    void requiredBindingFileFieldEmptyString_producesRequiredWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        Map<String, Object> data = new HashMap<>();
        data.put("attachment", "");

        TemplateVariableExtractor.ExtractedVariable var =
                new TemplateVariableExtractor.ExtractedVariable("myVar", Set.of(), false, null, null);
        when(templateVariableExtractor.extract(any())).thenReturn(List.of(var));

        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("myVar")
                .dataField("attachment")
                .build();

        ValidationResult result = service.validateTestCase(data, schema, null, List.of(binding), false, TEST_SUITE_ID);

        List<ValidationWarningDto> requiredWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.REQUIRED)
                .toList();
        assertThat(requiredWarnings).anyMatch(w -> w.getMessage().contains("is empty in data"));
    }

    @Test
    @DisplayName("Required binding + FILE field + whitespace-only produces REQUIRED warning (data-vs-binding)")
    void requiredBindingFileFieldWhitespaceOnly_producesRequiredWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        Map<String, Object> data = new HashMap<>();
        data.put("attachment", "   ");

        TemplateVariableExtractor.ExtractedVariable var =
                new TemplateVariableExtractor.ExtractedVariable("myVar", Set.of(), false, null, null);
        when(templateVariableExtractor.extract(any())).thenReturn(List.of(var));

        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("myVar")
                .dataField("attachment")
                .build();

        ValidationResult result = service.validateTestCase(data, schema, null, List.of(binding), false, TEST_SUITE_ID);

        List<ValidationWarningDto> requiredWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.REQUIRED)
                .toList();
        assertThat(requiredWarnings).anyMatch(w -> w.getMessage().contains("is empty in data"));
    }

    @Test
    @DisplayName("Required binding + STRING field + empty string produces no warning (data-vs-binding)")
    void requiredBindingStringFieldEmptyString_noWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("description")
                .type(SchemaFieldType.STRING)
                .build());
        Map<String, Object> data = new HashMap<>();
        data.put("description", "");

        TemplateVariableExtractor.ExtractedVariable var =
                new TemplateVariableExtractor.ExtractedVariable("myVar", Set.of(), false, null, null);
        when(templateVariableExtractor.extract(any())).thenReturn(List.of(var));

        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("myVar")
                .dataField("description")
                .build();

        ValidationResult result = service.validateTestCase(data, schema, null, List.of(binding), false, TEST_SUITE_ID);

        List<ValidationWarningDto> requiredWarnings = result.getWarnings().stream()
                .filter(w -> "description".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.REQUIRED)
                .toList();
        assertThat(requiredWarnings).isEmpty();
    }

    @Test
    @DisplayName("Optional binding (has default) + FILE field + empty string produces no warning (data-vs-binding)")
    void optionalBindingFileFieldEmptyString_noWarning() {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .build());
        Map<String, Object> data = new HashMap<>();
        data.put("attachment", "");

        TemplateVariableExtractor.ExtractedVariable var = new TemplateVariableExtractor.ExtractedVariable(
                "myVar", Set.of(), true, "@ef/suites/default/file.csv", SchemaFieldType.FILE);
        when(templateVariableExtractor.extract(any())).thenReturn(List.of(var));

        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("myVar")
                .dataField("attachment")
                .build();

        ValidationResult result = service.validateTestCase(data, schema, null, List.of(binding), false, TEST_SUITE_ID);

        List<ValidationWarningDto> requiredWarnings = result.getWarnings().stream()
                .filter(w -> "attachment".equals(w.getFieldName()) && w.getCode() == ValidationWarningCode.REQUIRED)
                .toList();
        assertThat(requiredWarnings).isEmpty();
    }
}
