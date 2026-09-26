package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ZipManifestSerializer")
class ZipManifestSerializerTest {

    private ZipManifestSerializer serializer;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        serializer = new ZipManifestSerializer(new ObjectMapper(), validator);
    }

    @Test
    @DisplayName("round trip keeps perTurn, required, displayName, description and file content type")
    void write_thenRead_roundTripsFieldAttributes() {
        FieldDefinitionDto document = FieldDefinitionDto.builder()
                .name("document")
                .displayName("Document")
                .type(SchemaFieldType.FILE)
                .required(true)
                .description("Uploaded attachment")
                .perTurn(false)
                .build();
        FieldDefinitionDto prompt = FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .required(false)
                .perTurn(true)
                .build();
        ZipManifest manifest = new ZipManifest(
                ZipManifest.CURRENT_FORMAT_VERSION,
                List.of(document, prompt),
                List.of(new ZipManifest.FileEntry(
                        "files/1/report.pdf", "@ef/datasets/abc/report.pdf", "application/pdf")));

        byte[] written = serializer.write(manifest);
        ZipManifest read = serializer.read(written);

        assertThat(read.formatVersion()).isEqualTo(ZipManifest.CURRENT_FORMAT_VERSION);
        assertThat(read.testCaseSchema()).hasSize(2);
        FieldDefinitionDto readDocument = read.testCaseSchema().getFirst();
        assertThat(readDocument.getName()).isEqualTo("document");
        assertThat(readDocument.getDisplayName()).isEqualTo("Document");
        assertThat(readDocument.getType()).isEqualTo(SchemaFieldType.FILE);
        assertThat(readDocument.isRequired()).isTrue();
        assertThat(readDocument.getDescription()).isEqualTo("Uploaded attachment");
        assertThat(readDocument.getPerTurn()).isFalse();
        FieldDefinitionDto readPrompt = read.testCaseSchema().get(1);
        assertThat(readPrompt.getPerTurn()).isTrue();
        assertThat(read.files()).hasSize(1);
        assertThat(read.files().getFirst().path()).isEqualTo("files/1/report.pdf");
        assertThat(read.files().getFirst().sourceRef()).isEqualTo("@ef/datasets/abc/report.pdf");
        assertThat(read.files().getFirst().contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("a file entry without contentType (older or hand-made archive) reads with a null content type")
    void read_fileEntryWithoutContentType_hasNullContentType() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":[],"
                + "\"files\":[{\"path\":\"files/1/a.png\",\"sourceRef\":\"@ef/datasets/abc/a.png\"}]}";

        ZipManifest manifest = serializer.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.files()).singleElement().satisfies(entry -> {
            assertThat(entry.path()).isEqualTo("files/1/a.png");
            assertThat(entry.contentType()).isNull();
        });
    }

    @Test
    @DisplayName("files defaults to an empty list when absent from the JSON")
    void read_withoutFilesField_defaultsToEmptyList() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":[]}";

        ZipManifest manifest = serializer.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.files()).isEmpty();
    }

    @Test
    @DisplayName("read throws ValidationException on invalid JSON")
    void read_withInvalidJson_throwsValidationException() {
        byte[] invalid = "{not valid json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> serializer.read(invalid))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("manifest.json");
    }

    @Test
    @DisplayName("read throws ValidationException on an unsupported formatVersion")
    void read_withUnsupportedFormatVersion_throwsValidationException() {
        String json = "{\"formatVersion\":2,\"testCaseSchema\":[]}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("formatVersion");
    }

    @Test
    @DisplayName("read throws ValidationException when testCaseSchema is missing")
    void read_withoutTestCaseSchema_throwsValidationException() {
        String json = "{\"formatVersion\":1}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("testCaseSchema");
    }

    @Test
    @DisplayName("read throws ValidationException when a field name contains a colon")
    void read_withColonInFieldName_throwsValidationException() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":[{\"name\":\"a:b\",\"type\":\"STRING\"}]}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("a:b");
    }

    @Test
    @DisplayName("read throws ValidationException when a field has no type")
    void read_withMissingFieldType_throwsValidationException() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":[{\"name\":\"document\"}]}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("document");
    }

    @Test
    @DisplayName("read throws ValidationException when testCaseSchema holds a null entry")
    void read_withNullSchemaEntry_throwsValidationException() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":[null]}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("testCaseSchema[0]");
    }

    @Test
    @DisplayName("read throws ValidationException on a duplicate testCaseSchema field name")
    void read_withDuplicateFieldName_throwsValidationException() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":["
                + "{\"name\":\"document\",\"type\":\"STRING\"},"
                + "{\"name\":\"document\",\"type\":\"FILE\"}]}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate")
                .hasMessageContaining("document");
    }

    @Test
    @DisplayName("read throws ValidationException when files holds a null entry")
    void read_withNullFileEntry_throwsValidationException() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":[],\"files\":[null]}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("files[0]");
    }

    @Test
    @DisplayName("read throws ValidationException when a file entry has a null path")
    void read_withNullFilePath_throwsValidationException() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":[],\"files\":"
                + "[{\"path\":null,\"sourceRef\":\"@ef/datasets/abc/report.pdf\"}]}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("files[0]")
                .hasMessageContaining("path");
    }

    @Test
    @DisplayName("read throws ValidationException when a file entry has a blank path")
    void read_withBlankFilePath_throwsValidationException() {
        String json = "{\"formatVersion\":1,\"testCaseSchema\":[],\"files\":"
                + "[{\"path\":\"  \",\"sourceRef\":\"@ef/datasets/abc/report.pdf\"}]}";

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("files[0]")
                .hasMessageContaining("path");
    }
}
