package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.service.domain.SuiteSnapshotBuilder;
import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotDatasetMissingException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.UnsupportedSnapshotVersionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("TestSuiteEvaluationJob")
@ExtendWith(MockitoExtension.class)
class TestSuiteEvaluationJobTest {

    @Mock
    private TestSuiteRunRepository repository;

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseRunInputRepository testCaseRunInputRepository;

    @Mock
    private TestSuiteRunSseService sseService;

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @Mock
    private SuiteSnapshotBuilder suiteSnapshotBuilder;

    @Mock
    private EvaluationExecutor evaluationExecutor;

    @Mock
    private TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;

    @Mock
    private MetricEvaluationProperties metricEvaluationProperties;

    @Mock
    private MetricEvaluationExecutor metricEvaluationExecutor;

    @Mock
    private Clock clock;

    @Mock
    private PlatformTransactionManager metaTransactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestSuiteEvaluationJob job;

    @BeforeEach
    void setUp() {
        job = new TestSuiteEvaluationJob(
                repository,
                testSuiteRepository,
                datasetRepository,
                testCaseRepository,
                testCaseRunInputRepository,
                sseService,
                evaluationRunProperties,
                objectMapper,
                suiteSnapshotBuilder,
                evaluationExecutor,
                testSuiteMetricDefinitionService,
                metricEvaluationProperties,
                metricEvaluationExecutor,
                clock,
                metaTransactionManager);
    }

    @Nested
    @DisplayName("resolveSnapshot (via buildContext)")
    class ResolveSnapshot {

        @Test
        @DisplayName(
                "deserializes persisted snapshot when suite_snapshot is non-null and version matches CURRENT_VERSION")
        void deserializesPersistedSnapshot() throws Exception {
            SuiteSnapshotDto expected = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .build();
            String snapshotJson = objectMapper.writeValueAsString(expected);

            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(snapshotJson)
                    .build();

            SuiteSnapshotDto result = invokeResolveSnapshot(run);

            assertThat(result.getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
            assertThat(result.getSuiteType()).isEqualTo("DEPLOYMENT");
        }

        @Test
        @DisplayName("throws UnsupportedSnapshotVersionException when persisted snapshot has older version")
        void throwsForLegacyVersionOneSnapshot() {
            // Legacy v1 snapshots predate the dataset entity. CURRENT_VERSION is "2"; the resolver
            // rejects any version that is not equal to CURRENT_VERSION.
            String snapshotJson = """
                    {"snapshotVersion":"1","suiteType":"DEPLOYMENT"}
                    """;

            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(snapshotJson)
                    .build();

            assertThatThrownBy(() -> invokeResolveSnapshot(run))
                    .isInstanceOf(UnsupportedSnapshotVersionException.class)
                    .hasMessageContaining("1");
        }

        @Test
        @DisplayName("defaults missing snapshotVersion to CURRENT_VERSION during deserialization")
        void defaultsMissingSnapshotVersionToCurrentVersion() {
            // JSON without snapshotVersion field — resolver defaults to CURRENT_VERSION
            String snapshotJson = """
                    {"suiteType":"DEPLOYMENT"}
                    """;

            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(snapshotJson)
                    .build();

            SuiteSnapshotDto result = invokeResolveSnapshot(run);

            assertThat(result.getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
            assertThat(result.getSuiteType()).isEqualTo("DEPLOYMENT");
        }

        @Test
        @DisplayName("throws UnsupportedSnapshotVersionException for unknown snapshot version")
        void throwsForUnknownSnapshotVersion() {
            String snapshotJson = """
                    {"snapshotVersion":"99","suiteType":"DEPLOYMENT"}
                    """;

            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(snapshotJson)
                    .build();

            assertThatThrownBy(() -> invokeResolveSnapshot(run))
                    .isInstanceOf(UnsupportedSnapshotVersionException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName(
                "synthesizes transient snapshot from live (suite, dataset) when suite_snapshot is null (legacy run)")
        void synthesizesSnapshotForLegacyRunWithNullSnapshot() {
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(suiteId)
                    .suiteSnapshot(null)
                    .build();

            TestSuite liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .disabledTestCaseIds("[]")
                    .deploymentRef("{}")
                    .endpointRef("{}")
                    .requestTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .build();
            Dataset liveDataset = Dataset.builder()
                    .id(datasetId)
                    .name("Legacy Dataset")
                    .version(1L)
                    .testCaseSchema("[]")
                    .build();

            SuiteSnapshotDto builtSnapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .build();

            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(liveDataset));
            when(suiteSnapshotBuilder.build(liveSuite, liveDataset)).thenReturn(builtSnapshot);

            SuiteSnapshotDto result = invokeResolveSnapshot(run);

            assertThat(result).isEqualTo(builtSnapshot);
            verify(suiteSnapshotBuilder).build(liveSuite, liveDataset);
        }

        @Test
        @DisplayName("throws SnapshotSuiteMissingException when legacy run references deleted suite")
        void throwsSnapshotSuiteMissingWhenLegacySuiteDeleted() {
            UUID suiteId = UUID.randomUUID();
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(suiteId)
                    .suiteSnapshot(null)
                    .build();

            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> invokeResolveSnapshot(run))
                    .isInstanceOf(SnapshotSuiteMissingException.class)
                    .hasMessageContaining(suiteId.toString());
        }

        @Test
        @DisplayName("throws SnapshotDatasetMissingException when legacy run references suite whose dataset is gone")
        void throwsSnapshotDatasetMissingWhenLegacyDatasetDeleted() {
            UUID runId = UUID.randomUUID();
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(runId)
                    .testSuiteId(suiteId)
                    .suiteSnapshot(null)
                    .build();

            TestSuite liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .disabledTestCaseIds("[]")
                    .build();

            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> invokeResolveSnapshot(run))
                    .isInstanceOf(SnapshotDatasetMissingException.class)
                    .hasMessageContaining(datasetId.toString());
        }
    }

    private SuiteSnapshotDto invokeResolveSnapshot(TestSuiteRun run) {
        return (SuiteSnapshotDto) ReflectionTestUtils.invokeMethod(job, "resolveSnapshot", run);
    }
}
