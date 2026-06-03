package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.epam.aidial.evaluation.configuration.properties.dial.DialFileStorageProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("FileRefValidator")
@ExtendWith(MockitoExtension.class)
class FileRefValidatorTest {

    private static final String BUCKET_ALIAS = "@ef";
    private static final UUID SUITE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private DialFileStorageProperties fileStorageProperties;

    private FileRefValidator validator;

    @BeforeEach
    void setUp() {
        lenient().when(fileStorageProperties.getBucketAlias()).thenReturn(BUCKET_ALIAS);
        validator = new FileRefValidator(fileStorageProperties);
    }

    @Nested
    @DisplayName("validate — format + ownership")
    class Validate {

        @Test
        @DisplayName("valid @ef ref with matching suiteId produces no errors")
        void validEfRefMatchingSuiteId() {
            String ref = "@ef/suites/" + SUITE_ID + "/data.csv";

            List<String> errors = validator.validate(ref, SUITE_ID);

            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("valid public ref produces no errors")
        void validPublicRef() {
            List<String> errors = validator.validate("public/datasets/eval-data.csv", SUITE_ID);

            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("disallowed prefix returns error")
        void disallowedPrefixReturnsError() {
            List<String> errors = validator.validate("user-bucket/path/file.csv", SUITE_ID);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("disallowed prefix");
        }

        @Test
        @DisplayName("path traversal '..' returns error")
        void pathTraversalReturnsError() {
            List<String> errors = validator.validate("public/../etc/passwd", SUITE_ID);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("..");
        }

        @Test
        @DisplayName("invalid characters in segment returns error")
        void invalidCharactersReturnsError() {
            List<String> errors = validator.validate("@ef/suites/abc/fi<le>.csv", SUITE_ID);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("invalid characters");
        }

        @Test
        @DisplayName("empty segment returns error")
        void emptySegmentReturnsError() {
            List<String> errors = validator.validate("public//file.csv", SUITE_ID);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("empty segments");
        }

        @Test
        @DisplayName("prefix only (no path segments) returns error")
        void prefixOnlyReturnsError() {
            List<String> errors = validator.validate("public", SUITE_ID);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("at least one path segment");
        }

        @Test
        @DisplayName("prefix with trailing slash only returns error")
        void prefixWithTrailingSlashReturnsError() {
            List<String> errors = validator.validate("@ef/", SUITE_ID);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("trailing slash");
        }

        @Test
        @DisplayName("@ef ref pointing to different suite produces ownership error")
        void crossSuiteRefProducesOwnershipError() {
            UUID otherSuiteId = UUID.randomUUID();
            String ref = "@ef/suites/" + otherSuiteId + "/data.csv";

            List<String> errors = validator.validate(ref, SUITE_ID);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("different suite");
        }

        @Test
        @DisplayName("null suiteId skips ownership check")
        void nullSuiteIdSkipsOwnership() {
            UUID otherSuiteId = UUID.randomUUID();
            String ref = "@ef/suites/" + otherSuiteId + "/data.csv";

            List<String> errors = validator.validate(ref, null);

            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("old files/ prefix format returns disallowed prefix error")
        void oldFilesFormatReturnsDisallowedError() {
            List<String> errors = validator.validate("files/@ef/suites/abc/data.csv", null);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("disallowed prefix");
        }

        @Test
        @DisplayName("dataset-shaped @ef ref produces no format error")
        void datasetShapedRefIsValidFormat() {
            UUID datasetId = UUID.randomUUID();
            String ref = "@ef/datasets/" + datasetId + "/data.csv";

            List<String> errors = validator.validate(ref, null);

            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("unknown @ef segment returns format error")
        void unknownEfSegmentReturnsError() {
            List<String> errors = validator.validate("@ef/unknown/abc/data.csv", null);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("@ef/suites/");
            assertThat(errors.get(0)).contains("@ef/datasets/");
        }
    }

    @Nested
    @DisplayName("validateOwnership")
    class ValidateOwnership {

        @Test
        @DisplayName("@ef ref with matching suiteId is valid")
        void matchingSuiteIdIsValid() {
            String ref = "@ef/suites/" + SUITE_ID + "/report.pdf";

            List<String> errors = validator.validateOwnership(ref, SUITE_ID);

            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("@ef ref with different suiteId returns error")
        void differentSuiteIdReturnsError() {
            String ref = "@ef/suites/" + UUID.randomUUID() + "/report.pdf";

            List<String> errors = validator.validateOwnership(ref, SUITE_ID);

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("different suite");
        }

        @Test
        @DisplayName("null suiteId skips check")
        void nullSuiteIdSkipsCheck() {
            String ref = "@ef/suites/" + UUID.randomUUID() + "/report.pdf";

            List<String> errors = validator.validateOwnership(ref, null);

            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("public ref not subject to ownership check")
        void publicRefNotSubjectToOwnershipCheck() {
            List<String> errors = validator.validateOwnership("public/datasets/input.csv", SUITE_ID);

            assertThat(errors).isEmpty();
        }
    }
}
