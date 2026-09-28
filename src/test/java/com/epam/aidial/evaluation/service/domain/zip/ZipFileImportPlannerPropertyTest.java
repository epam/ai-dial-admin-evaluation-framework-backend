package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.service.domain.FileService;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Randomised invariants for {@link ZipFileImportPlanner} (seeded, so failures reproduce). Name pools are
 * tiny on purpose: they force same-name groups, names that sanitize to the same value, suffix candidates
 * that already exist in the listing or are another group's base name, and names at the length cap.
 */
@DisplayName("ZipFileImportPlanner — randomised naming invariants")
class ZipFileImportPlannerPropertyTest {

    private static final long SEED = 20260926L;
    private static final int RUNS = 500;
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final String OWN_PREFIX = "@ef/datasets/" + DATASET_ID + "/";

    private static final List<String> STEMS =
            List.of("a", "a_1", "a_2", "b", "b!", "b_", "x y", "report", "report_1", "z".repeat(252));
    private static final List<String> EXTS = List.of(".png", ".txt", "");

    @Test
    @DisplayName("every plan: all paths planned, names unique and ≤255, suffixes never overwrite, "
            + "the dataset's own file keeps its name, capacity counts exactly the new names, order-independent")
    void randomPlansSatisfyInvariants() {
        Random random = new Random(SEED);
        for (int run = 0; run < RUNS; run++) {
            Scenario scenario = randomScenario(random);
            String context = "run " + run + ": " + scenario;

            Planned first = plan(scenario, scenario.paths());
            List<String> shuffled = new ArrayList<>(scenario.paths());
            Collections.shuffle(shuffled, random);
            Planned second = plan(scenario, shuffled);

            Map<String, ZipFileImportPlanner.PlannedFile> byPath = first.plan().byPath();
            assertThat(byPath.keySet()).as(context).containsExactlyInAnyOrderElementsOf(scenario.paths());

            List<String> names = byPath.values().stream()
                    .map(ZipFileImportPlanner.PlannedFile::filename)
                    .toList();
            assertThat(new HashSet<>(names)).as("unique names, " + context).hasSameSizeAs(names);
            assertThat(names)
                    .as("length cap, " + context)
                    .allSatisfy(name -> assertThat(name.length())
                            .isLessThanOrEqualTo(ZipEntryFilenameSanitizer.MAX_FILENAME_LENGTH));

            byPath.forEach((path, planned) -> {
                String base = ZipEntryFilenameSanitizer.sanitize(path.substring(path.lastIndexOf('/') + 1));
                boolean suffixed = !planned.filename().equals(base);
                assertThat(planned.isNew())
                        .as("isNew matches listing for %s, %s", path, context)
                        .isEqualTo(!scenario.existing().contains(planned.filename()));
                if (suffixed) {
                    assertThat(planned.isNew())
                            .as("suffixed name never overwrites, %s, %s", path, context)
                            .isTrue();
                }
                assertThat(planned.ref()).isEqualTo(OWN_PREFIX + planned.filename());
                if ((OWN_PREFIX + base).equals(scenario.sourceRefs().get(path))) {
                    assertThat(planned.filename())
                            .as("own file keeps its name, %s, %s", path, context)
                            .isEqualTo(base);
                }
            });

            long newCount = byPath.values().stream()
                    .filter(ZipFileImportPlanner.PlannedFile::isNew)
                    .count();
            verify(first.fileService()).checkDatasetFileCapacity(DATASET_ID, (int) newCount);

            assertThat(second.plan().byPath())
                    .as("order-independent, " + context)
                    .isEqualTo(byPath);
        }
    }

    private static Scenario randomScenario(Random random) {
        List<String> candidates = new ArrayList<>();
        for (String stem : STEMS) {
            for (String ext : EXTS) {
                candidates.add(stem + ext);
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        int pathCount = 1 + random.nextInt(8);
        while (paths.size() < pathCount) {
            paths.add("files/" + (1 + random.nextInt(12)) + "/" + candidates.get(random.nextInt(candidates.size())));
        }
        Set<String> existing = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (random.nextInt(4) == 0) {
                existing.add(ZipEntryFilenameSanitizer.sanitize(candidate));
            }
        }
        // At most one path per sanitized name claims to be the dataset's own file, as a real export writes.
        Map<String, String> sourceRefs = new LinkedHashMap<>();
        Set<String> claimedBases = new HashSet<>();
        for (String path : paths) {
            String base = ZipEntryFilenameSanitizer.sanitize(path.substring(path.lastIndexOf('/') + 1));
            if (random.nextInt(3) == 0 && claimedBases.add(base)) {
                sourceRefs.put(path, OWN_PREFIX + base);
            }
        }
        return new Scenario(List.copyOf(paths), existing, sourceRefs);
    }

    private static Planned plan(Scenario scenario, List<String> pathOrder) {
        FileService fileService = mock(FileService.class);
        DialFileRefResolver resolver = mock(DialFileRefResolver.class);
        when(fileService.listByDataset(DATASET_ID))
                .thenReturn(scenario.existing().stream()
                        .map(name -> FileMetadataDto.builder().filename(name).build())
                        .toList());
        when(resolver.buildDatasetEfRef(eq(DATASET_ID), anyString()))
                .thenAnswer(inv -> OWN_PREFIX + inv.getArgument(1, String.class));
        ZipFileImportPlanner planner = new ZipFileImportPlanner(fileService, resolver);
        return new Planned(
                planner.plan(DATASET_ID, new LinkedHashSet<>(pathOrder), scenario.sourceRefs()), fileService);
    }

    private record Scenario(List<String> paths, Set<String> existing, Map<String, String> sourceRefs) {}

    private record Planned(ZipFileImportPlanner.ImportFilePlan plan, FileService fileService) {}
}
