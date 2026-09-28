package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.service.domain.FileService;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ZipFileImportPlanner")
@ExtendWith(MockitoExtension.class)
class ZipFileImportPlannerTest {

    @Mock
    private FileService fileService;

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    private ZipFileImportPlanner planner;
    private final UUID datasetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        planner = new ZipFileImportPlanner(fileService, dialFileRefResolver);
    }

    private void stubExisting(String... filenames) {
        when(fileService.listByDataset(datasetId))
                .thenReturn(List.of(filenames).stream()
                        .map(name -> FileMetadataDto.builder().filename(name).build())
                        .toList());
    }

    private void stubRefResolver() {
        lenient()
                .when(dialFileRefResolver.buildDatasetEfRef(eq(datasetId), anyString()))
                .thenAnswer(inv -> "@ef/datasets/" + datasetId + "/" + inv.getArgument(1, String.class));
    }

    @Test
    @DisplayName("a same-name file already in the dataset is planned as overwrite")
    void plan_sameNameAsExisting_isOverwrite() {
        stubExisting("report.pdf");
        stubRefResolver();

        ZipFileImportPlanner.ImportFilePlan plan = planner.plan(datasetId, Set.of("files/1/report.pdf"), Map.of());

        ZipFileImportPlanner.PlannedFile planned = plan.get("files/1/report.pdf");
        assertThat(planned.filename()).isEqualTo("report.pdf");
        assertThat(planned.isNew()).isFalse();
        assertThat(planned.ref()).isEqualTo("@ef/datasets/" + datasetId + "/report.pdf");
    }

    @Test
    @DisplayName("the dataset's own file keeps its name when a suite file comes first in archive order")
    void plan_datasetOwnFileKeepsName_whenSuiteFileComesFirst() {
        stubExisting("report.pdf");
        stubRefResolver();
        UUID suiteId = UUID.randomUUID();
        Set<String> referenced = new LinkedHashSet<>(List.of("files/1/report.pdf", "files/2/report.pdf"));
        Map<String, String> manifestSourceRefByPath = Map.of(
                "files/1/report.pdf", "@ef/suites/" + suiteId + "/report.pdf",
                "files/2/report.pdf", "@ef/datasets/" + datasetId + "/report.pdf");

        ZipFileImportPlanner.ImportFilePlan plan = planner.plan(datasetId, referenced, manifestSourceRefByPath);

        // The dataset's own file (files/2) keeps "report.pdf" and overwrites; the suite file (files/1),
        // despite being first in archive order, gets a new suffixed name.
        assertThat(plan.get("files/2/report.pdf").filename()).isEqualTo("report.pdf");
        assertThat(plan.get("files/2/report.pdf").isNew()).isFalse();
        assertThat(plan.get("files/1/report.pdf").filename()).isEqualTo("report_1.pdf");
        assertThat(plan.get("files/1/report.pdf").isNew()).isTrue();
    }

    @Test
    @DisplayName("a suffix skips a name that already exists in the dataset")
    void plan_suffix_skipsExistingName() {
        stubExisting("report_1.pdf");
        stubRefResolver();
        Set<String> referenced = new LinkedHashSet<>(List.of("files/1/report.pdf", "files/2/report.pdf"));

        ZipFileImportPlanner.ImportFilePlan plan = planner.plan(datasetId, referenced, Map.of());

        assertThat(plan.get("files/1/report.pdf").filename()).isEqualTo("report.pdf");
        assertThat(plan.get("files/1/report.pdf").isNew()).isTrue();
        assertThat(plan.get("files/2/report.pdf").filename()).isEqualTo("report_2.pdf");
        assertThat(plan.get("files/2/report.pdf").isNew()).isTrue();
    }

    @Test
    @DisplayName("a sanitized name overwrites a same-name existing file")
    void plan_sanitizedName_overwritesExistingFile() {
        stubExisting("report_final.pdf");
        stubRefResolver();

        ZipFileImportPlanner.ImportFilePlan plan =
                planner.plan(datasetId, Set.of("files/1/report#final.pdf"), Map.of());

        ZipFileImportPlanner.PlannedFile planned = plan.get("files/1/report#final.pdf");
        assertThat(planned.filename()).isEqualTo("report_final.pdf");
        assertThat(planned.isNew()).isFalse();
    }

    @Test
    @DisplayName("a later group's own base name never collides with an earlier group's suffix")
    void plan_laterGroupsBaseName_neverCollidesWithEarlierGroupsSuffix() {
        stubExisting();
        stubRefResolver();
        // Two entries named "x.png" (files/1, files/2) and one entry that is *already* named "x_1.png"
        // (files/3). Naively suffixing files/2 to "x_1.png" (the first free-looking name) before files/3's
        // own base name is reserved would make files/3 collide with it once its group is processed.
        Set<String> referenced = new LinkedHashSet<>(List.of("files/1/x.png", "files/2/x.png", "files/3/x_1.png"));

        ZipFileImportPlanner.ImportFilePlan plan = planner.plan(datasetId, referenced, Map.of());

        String name1 = plan.get("files/1/x.png").filename();
        String name2 = plan.get("files/2/x.png").filename();
        String name3 = plan.get("files/3/x_1.png").filename();

        // Every planned name is distinct: no two archive entries end up sharing one dataset file.
        assertThat(Set.of(name1, name2, name3)).hasSize(3);
        // files/1 is the sole primary for "x.png" (lowest archive order, no manifest tie-break) and keeps it.
        assertThat(name1).isEqualTo("x.png");
        // files/3 is the sole primary for "x_1.png" and keeps its own name.
        assertThat(name3).isEqualTo("x_1.png");
        // files/2 must be suffixed to something other than "x_1.png" (already claimed by files/3).
        assertThat(name2).isNotEqualTo("x_1.png");

        assertThat(plan.get("files/1/x.png").isNew()).isTrue();
        assertThat(plan.get("files/2/x.png").isNew()).isTrue();
        assertThat(plan.get("files/3/x_1.png").isNew()).isTrue();
        verify(fileService).checkDatasetFileCapacity(datasetId, 3);
    }

    @Test
    @DisplayName("a suffix on an already-255-char name is truncated in the stem, keeping the extension and the cap")
    void plan_suffixOn255CharName_truncatesStemToStayWithinFilenameLengthCap() {
        stubExisting();
        stubRefResolver();
        // Already exactly MAX_FILENAME_LENGTH (255) chars, all valid, so sanitize() leaves it unchanged.
        String name255 = "a".repeat(251) + ".pdf";
        assertThat(name255).hasSize(ZipEntryFilenameSanitizer.MAX_FILENAME_LENGTH);
        Set<String> referenced = new LinkedHashSet<>(List.of("files/1/" + name255, "files/2/" + name255));

        ZipFileImportPlanner.ImportFilePlan plan = planner.plan(datasetId, referenced, Map.of());

        // files/1 (lowest archive order, no manifest tie-break) keeps the name as-is.
        assertThat(plan.get("files/1/" + name255).filename()).isEqualTo(name255);
        // files/2 must be suffixed, but the result must still respect the 255-char cap: the stem is
        // truncated (never the ".pdf" extension) rather than growing past it.
        String suffixed = plan.get("files/2/" + name255).filename();
        assertThat(suffixed).hasSizeLessThanOrEqualTo(ZipEntryFilenameSanitizer.MAX_FILENAME_LENGTH);
        assertThat(suffixed).endsWith("_1.pdf");
        assertThat(suffixed).isNotEqualTo(name255);
    }

    @Test
    @DisplayName("a full dataset allows an import that only overwrites existing names")
    void plan_fullDataset_allowsOverwriteOnly() {
        stubExisting("report.pdf");
        stubRefResolver();

        // Only overwrites an existing name: zero new files, so checkDatasetFileCapacity(datasetId, 0) must be
        // the call made (left un-stubbed: the default mock behaviour is to do nothing, i.e. not throw).
        ZipFileImportPlanner.ImportFilePlan plan = planner.plan(datasetId, Set.of("files/1/report.pdf"), Map.of());

        assertThat(plan.get("files/1/report.pdf").isNew()).isFalse();
        verify(fileService).checkDatasetFileCapacity(datasetId, 0);
    }

    @Test
    @DisplayName("a full dataset rejects an import that would add new files")
    void plan_fullDataset_rejectsNewFiles() {
        stubExisting("existing.pdf");
        stubRefResolver();
        doThrow(new ValidationException("Maximum number of files per dataset (1) reached"))
                .when(fileService)
                .checkDatasetFileCapacity(datasetId, 1);

        assertThatThrownBy(() -> planner.plan(datasetId, Set.of("files/1/new.pdf"), Map.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Maximum number of files per dataset");
    }

    @Test
    @DisplayName("no referenced paths plans nothing and makes no listing call")
    void plan_noReferencedPaths_plansNothing() {
        ZipFileImportPlanner.ImportFilePlan plan = planner.plan(datasetId, Set.of(), Map.of());

        assertThat(plan.byPath()).isEmpty();
        verifyNoInteractions(fileService, dialFileRefResolver);
    }
}
