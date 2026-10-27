package com.epam.aidial.evaluation.runner.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("DialFileRefResolver")
@ExtendWith(MockitoExtension.class)
class DialFileRefResolverTest {

    private static final String BUCKET_ALIAS = "@ef";
    private static final String REAL_BUCKET = "ef-bucket-123";

    @Mock
    private DialFileClient dialFileClient;

    @Mock
    private DialFileStorageProperties fileStorageProperties;

    private DialFileRefResolver resolver;

    @BeforeEach
    void setUp() {
        lenient().when(fileStorageProperties.getBucketAlias()).thenReturn(BUCKET_ALIAS);
        resolver = new DialFileRefResolver(dialFileClient, fileStorageProperties, new BindingTypeResolver());
    }

    @Nested
    @DisplayName("resolveToRealPath")
    class ResolveToRealPath {

        @Test
        @DisplayName("should resolve @ef alias to real bucket")
        void shouldResolveEfAlias() {
            when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);

            String result = resolver.resolveToRealPath("@ef/suites/abc/data.csv");

            assertThat(result).isEqualTo("ef-bucket-123/suites/abc/data.csv");
        }

        @Test
        @DisplayName("should resolve @ef alias for dataset-shaped ref")
        void shouldResolveEfAliasForDatasetRef() {
            when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);

            String result = resolver.resolveToRealPath("@ef/datasets/xyz/data.csv");

            assertThat(result).isEqualTo("ef-bucket-123/datasets/xyz/data.csv");
        }

        @Test
        @DisplayName("should pass through public prefix unchanged")
        void shouldPassthroughPublicPrefix() {
            String result = resolver.resolveToRealPath("public/datasets/input.csv");

            assertThat(result).isEqualTo("public/datasets/input.csv");
        }

        @Test
        @DisplayName("should reject disallowed prefix")
        void shouldRejectDisallowedPrefix() {
            assertThatThrownBy(() -> resolver.resolveToRealPath("user-bucket/private/data.csv"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Disallowed file reference prefix");
        }

        @Test
        @DisplayName("should reject null reference")
        void shouldRejectNullReference() {
            assertThatThrownBy(() -> resolver.resolveToRealPath(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Invalid DIAL file reference");
        }

        @Test
        @DisplayName("should reject old files/ format")
        void shouldRejectOldFilesFormat() {
            assertThatThrownBy(() -> resolver.resolveToRealPath("files/@ef/suites/abc/data.csv"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Invalid DIAL file reference");
        }
    }

    @Nested
    @DisplayName("resolveToDialRef")
    class ResolveToDialRef {

        @Test
        @DisplayName("should return files/{realBucket}/path for @ef ref")
        void shouldReturnDialRefForEfRef() {
            when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);

            String result = resolver.resolveToDialRef("@ef/suites/abc/data.csv");

            assertThat(result).isEqualTo("files/ef-bucket-123/suites/abc/data.csv");
        }

        @Test
        @DisplayName("should return files/{realBucket}/datasets/... for @ef dataset ref")
        void shouldReturnDialRefForDatasetRef() {
            when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);

            String result = resolver.resolveToDialRef("@ef/datasets/xyz/data.csv");

            assertThat(result).isEqualTo("files/ef-bucket-123/datasets/xyz/data.csv");
        }

        @Test
        @DisplayName("should return files/public/path for public ref")
        void shouldReturnDialRefForPublicRef() {
            String result = resolver.resolveToDialRef("public/datasets/input.csv");

            assertThat(result).isEqualTo("files/public/datasets/input.csv");
        }
    }

    @Nested
    @DisplayName("buildEfRef")
    class BuildEfRef {

        @Test
        @DisplayName("should build short-format EF file reference without files/ prefix")
        void shouldBuildShortFormatEfRef() {
            UUID suiteId = UUID.fromString("11111111-1111-1111-1111-111111111111");

            String result = resolver.buildEfRef(suiteId, "report.pdf");

            assertThat(result).isEqualTo("@ef/suites/11111111-1111-1111-1111-111111111111/report.pdf");
        }
    }

    @Nested
    @DisplayName("buildDatasetEfRef")
    class BuildDatasetEfRef {

        @Test
        @DisplayName("should build short-format dataset EF reference without files/ prefix")
        void shouldBuildShortFormatDatasetEfRef() {
            UUID datasetId = UUID.fromString("22222222-2222-2222-2222-222222222222");

            String result = resolver.buildDatasetEfRef(datasetId, "report.pdf");

            assertThat(result).isEqualTo("@ef/datasets/22222222-2222-2222-2222-222222222222/report.pdf");
        }
    }

    @Nested
    @DisplayName("extractFilename")
    class ExtractFilename {

        @Test
        @DisplayName("should extract filename from short @ef reference")
        void shouldExtractFromEfRef() {
            String result = resolver.extractFilename("@ef/suites/abc/data.csv");

            assertThat(result).isEqualTo("data.csv");
        }

        @Test
        @DisplayName("should extract filename from short public reference")
        void shouldExtractFromPublicRef() {
            String result = resolver.extractFilename("public/datasets/input.csv");

            assertThat(result).isEqualTo("input.csv");
        }

        @Test
        @DisplayName("should extract filename from short dataset @ef reference")
        void shouldExtractFromDatasetEfRef() {
            String result = resolver.extractFilename("@ef/datasets/xyz/data.csv");

            assertThat(result).isEqualTo("data.csv");
        }

        @Test
        @DisplayName("should return input when no slash present")
        void shouldReturnInputWhenNoSlash() {
            String result = resolver.extractFilename("standalone.txt");

            assertThat(result).isEqualTo("standalone.txt");
        }
    }

    @Nested
    @DisplayName("getAllowedPrefixes")
    class GetAllowedPrefixes {

        @Test
        @DisplayName("should return configured alias and public")
        void shouldReturnAliasAndPublic() {
            var prefixes = resolver.getAllowedPrefixes();

            assertThat(prefixes).containsExactlyInAnyOrder("@ef", "public");
        }
    }

    @Nested
    @DisplayName("isFileReference")
    class IsFileReference {

        private final List<FieldDefinitionDto> schema = List.of(
                FieldDefinitionDto.builder()
                        .name("attachment")
                        .type(SchemaFieldType.FILE)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("title")
                        .type(SchemaFieldType.STRING)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("skill")
                        .type(SchemaFieldType.SKILL)
                        .build());

        private InputBindingDto dataBinding(String dataField) {
            return InputBindingDto.builder()
                    .templateVariable("doc")
                    .dataField(dataField)
                    .build();
        }

        @Test
        @DisplayName("hintless placeholder bound to a FILE column is a file reference")
        void hintlessBoundToFileColumnIsFile() {
            assertThat(resolver.isFileReference(null, dataBinding("attachment"), schema))
                    .isTrue();
        }

        @Test
        @DisplayName("hintless placeholder bound to a STRING or SKILL column is not a file reference")
        void hintlessBoundToOtherColumnIsNotFile() {
            assertThat(resolver.isFileReference(null, dataBinding("title"), schema))
                    .isFalse();
            assertThat(resolver.isFileReference(null, dataBinding("skill"), schema))
                    .isFalse();
        }

        @Test
        @DisplayName("explicit file hint is a file reference without a binding or schema")
        void explicitFileHintIsFileWithoutSchema() {
            assertThat(resolver.isFileReference("file", null, null)).isTrue();
        }

        @Test
        @DisplayName("file hint is case-insensitive and trimmed")
        void fileHintIsCaseInsensitiveAndTrimmed() {
            assertThat(resolver.isFileReference(" FILE ", null, schema)).isTrue();
            assertThat(resolver.isFileReference("File", dataBinding("title"), schema))
                    .isTrue();
        }

        @Test
        @DisplayName("explicit non-file hint wins over a FILE column")
        void explicitNonFileHintWinsOverFileColumn() {
            assertThat(resolver.isFileReference("string", dataBinding("attachment"), schema))
                    .isFalse();
        }

        @Test
        @DisplayName("explicit skill hint is not a file reference")
        void explicitSkillHintIsNotFile() {
            assertThat(resolver.isFileReference("skill", null, null)).isFalse();
        }

        @Test
        @DisplayName("blank hint is treated as absent")
        void blankHintIsTreatedAsAbsent() {
            assertThat(resolver.isFileReference("  ", dataBinding("attachment"), schema))
                    .isTrue();
        }

        @Test
        @DisplayName("constantValue-only binding without hint is not a file reference")
        void constantValueBindingWithoutHintIsNotFile() {
            final InputBindingDto binding = InputBindingDto.builder()
                    .templateVariable("doc")
                    .constantValue("@ef/suites/abc/doc.pdf")
                    .build();

            assertThat(resolver.isFileReference(null, binding, schema)).isFalse();
        }

        @Test
        @DisplayName("null or empty schema, unknown data field or no binding is not a file reference")
        void unresolvableBindingIsNotFile() {
            assertThat(resolver.isFileReference(null, dataBinding("attachment"), null))
                    .isFalse();
            assertThat(resolver.isFileReference(null, dataBinding("attachment"), List.of()))
                    .isFalse();
            assertThat(resolver.isFileReference(null, dataBinding("missing"), schema))
                    .isFalse();
            assertThat(resolver.isFileReference(null, null, schema)).isFalse();
        }
    }
}
