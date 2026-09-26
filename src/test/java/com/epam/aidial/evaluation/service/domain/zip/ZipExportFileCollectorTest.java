package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.service.domain.FileRefValidator;
import com.epam.aidial.evaluation.service.domain.zip.ZipExportFileCollector.FileClassification;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ZipExportFileCollector")
@ExtendWith(MockitoExtension.class)
class ZipExportFileCollectorTest {

    private static final String BUCKET_ALIAS = "@ef";
    private static final String REAL_BUCKET = "real-bucket";

    @Mock
    private DialFileClient dialFileClient;

    private ZipExportFileCollector collector;
    private UUID datasetId;

    @BeforeEach
    void setUp() {
        DialFileStorageProperties properties = new DialFileStorageProperties();
        properties.setBucketAlias(BUCKET_ALIAS);
        DialFileRefResolver dialFileRefResolver = new DialFileRefResolver(dialFileClient, properties);
        FileRefValidator fileRefValidator = new FileRefValidator(properties);
        collector = new ZipExportFileCollector(dialFileRefResolver, fileRefValidator);
        datasetId = UUID.randomUUID();
    }

    @Test
    @DisplayName("public/... reference is kept verbatim with no download")
    void classify_publicReference_isVerbatim() {
        Map<String, String> assigned = new LinkedHashMap<>();

        FileClassification classification = collector.classify("public/shared/guide.pdf", assigned);

        assertThat(classification).isInstanceOf(FileClassification.Verbatim.class);
        assertThat(((FileClassification.Verbatim) classification).value()).isEqualTo("public/shared/guide.pdf");
        assertThat(assigned).isEmpty();
    }

    @Test
    @DisplayName("blank value is kept verbatim")
    void classify_blankValue_isVerbatim() {
        Map<String, String> assigned = new LinkedHashMap<>();

        FileClassification classification = collector.classify("", assigned);

        assertThat(classification).isInstanceOf(FileClassification.Verbatim.class);
        assertThat(((FileClassification.Verbatim) classification).value()).isEmpty();
    }

    @Test
    @DisplayName("invalid-format value is kept verbatim")
    void classify_invalidFormat_isVerbatim() {
        Map<String, String> assigned = new LinkedHashMap<>();

        FileClassification classification = collector.classify("not-a-valid-ref", assigned);

        assertThat(classification).isInstanceOf(FileClassification.Verbatim.class);
        assertThat(assigned).isEmpty();
    }

    @Test
    @DisplayName("EF-owned dataset reference is materialized under files/{n}/{filename} on first sight")
    void classify_datasetReference_isMaterializedOnFirstSight() {
        when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);
        Map<String, String> assigned = new LinkedHashMap<>();
        String ref = BUCKET_ALIAS + "/datasets/" + datasetId + "/report.pdf";

        FileClassification classification = collector.classify(ref, assigned);

        assertThat(classification).isInstanceOf(FileClassification.EfOwned.class);
        FileClassification.EfOwned efOwned = (FileClassification.EfOwned) classification;
        assertThat(efOwned.archivePath()).isEqualTo("files/1/report.pdf");
        assertThat(efOwned.realPath()).isEqualTo(REAL_BUCKET + "/datasets/" + datasetId + "/report.pdf");
        assertThat(efOwned.firstSeen()).isTrue();
        assertThat(assigned).containsEntry(ref, "files/1/report.pdf");
    }

    @Test
    @DisplayName("legacy suite-scoped reference is materialized")
    void classify_legacySuiteReference_isMaterialized() {
        when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);
        Map<String, String> assigned = new LinkedHashMap<>();
        UUID suiteId = UUID.randomUUID();
        String ref = BUCKET_ALIAS + "/suites/" + suiteId + "/guide.pdf";

        FileClassification classification = collector.classify(ref, assigned);

        assertThat(classification).isInstanceOf(FileClassification.EfOwned.class);
        FileClassification.EfOwned efOwned = (FileClassification.EfOwned) classification;
        assertThat(efOwned.archivePath()).isEqualTo("files/1/guide.pdf");
        assertThat(efOwned.firstSeen()).isTrue();
    }

    @Test
    @DisplayName("the same reference seen again across rows/turns/fields reuses its path and is not first-seen")
    void classify_sameReferenceSeenAgain_reusesPathAndIsNotFirstSeen() {
        when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);
        Map<String, String> assigned = new LinkedHashMap<>();
        String ref = BUCKET_ALIAS + "/datasets/" + datasetId + "/report.pdf";

        FileClassification first = collector.classify(ref, assigned);
        FileClassification second = collector.classify(ref, assigned);
        FileClassification third = collector.classify(ref, assigned);

        assertThat(((FileClassification.EfOwned) first).firstSeen()).isTrue();
        assertThat(((FileClassification.EfOwned) second).firstSeen()).isFalse();
        assertThat(((FileClassification.EfOwned) third).firstSeen()).isFalse();
        assertThat(((FileClassification.EfOwned) second).archivePath()).isEqualTo("files/1/report.pdf");
        assertThat(((FileClassification.EfOwned) third).archivePath()).isEqualTo("files/1/report.pdf");
        assertThat(assigned).hasSize(1);
    }

    @Test
    @DisplayName("distinct references are numbered in first-seen order")
    void classify_distinctReferences_areNumberedInFirstSeenOrder() {
        when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);
        Map<String, String> assigned = new LinkedHashMap<>();
        String refA = BUCKET_ALIAS + "/datasets/" + datasetId + "/a.pdf";
        String refB = BUCKET_ALIAS + "/datasets/" + datasetId + "/b.pdf";

        FileClassification.EfOwned a = (FileClassification.EfOwned) collector.classify(refA, assigned);
        FileClassification.EfOwned b = (FileClassification.EfOwned) collector.classify(refB, assigned);
        // refA seen again (e.g. a different turn row) must keep n=1, not get renumbered
        FileClassification.EfOwned again = (FileClassification.EfOwned) collector.classify(refA, assigned);

        assertThat(a.archivePath()).isEqualTo("files/1/a.pdf");
        assertThat(b.archivePath()).isEqualTo("files/2/b.pdf");
        assertThat(again.archivePath()).isEqualTo("files/1/a.pdf");
        assertThat(assigned).hasSize(2);
    }
}
