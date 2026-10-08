package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestCaseRepository.renameDataFields Functional Tests")
public abstract class TestCaseRenameDataFieldsFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetId() {
        Dataset dataset = metaTestDataHelper.createDataset("Rename-DS-" + UUID.randomUUID());
        return dataset.getId();
    }

    private JsonNode data(UUID datasetId, UUID id) {
        TestCase tc = testCaseRepository.findByIdAndDatasetId(id, datasetId).orElseThrow();
        return objectMapper.readTree(tc.getData());
    }

    private JsonNode turns(UUID datasetId, UUID id) {
        TestCase tc = testCaseRepository.findByIdAndDatasetId(id, datasetId).orElseThrow();
        return objectMapper.readTree(tc.getMultiTurnData());
    }

    @Test
    @DisplayName("plain rename moves the value to the new key and drops the old key")
    void plainRename() {
        UUID ds = newDatasetId();
        UUID id = metaTestDataHelper.seedTestCaseInDataset(ds, "tc", "{\"a\":\"1\",\"keep\":\"k\"}");

        int rows = testCaseRepository.renameDataFields(ds, Map.of("a", "b"));

        assertThat(rows).isEqualTo(1);
        assertThat(data(ds, id)).isEqualTo(objectMapper.readTree("{\"b\":\"1\",\"keep\":\"k\"}"));
    }

    @Test
    @DisplayName("swap a<->b is simultaneous, values exchanged")
    void swap() {
        UUID ds = newDatasetId();
        UUID id = metaTestDataHelper.seedTestCaseInDataset(ds, "tc", "{\"a\":\"A\",\"b\":\"B\"}");

        testCaseRepository.renameDataFields(ds, Map.of("a", "b", "b", "a"));

        assertThat(data(ds, id)).isEqualTo(objectMapper.readTree("{\"a\":\"B\",\"b\":\"A\"}"));
    }

    @Test
    @DisplayName("chain a->b, b->c reads every value from the original object")
    void chain() {
        UUID ds = newDatasetId();
        UUID id = metaTestDataHelper.seedTestCaseInDataset(ds, "tc", "{\"a\":\"A\",\"b\":\"B\"}");

        Map<String, String> renames = new LinkedHashMap<>();
        renames.put("a", "b");
        renames.put("b", "c");
        testCaseRepository.renameDataFields(ds, renames);

        assertThat(data(ds, id)).isEqualTo(objectMapper.readTree("{\"b\":\"A\",\"c\":\"B\"}"));
    }

    @Test
    @DisplayName("per-turn values are renamed in every turn of multi_turn_data")
    void perTurnRenamedInEveryTurn() {
        UUID ds = newDatasetId();
        UUID id = metaTestDataHelper.seedMultiTurnTestCaseInDataset(
                ds, "mt", "[{\"a\":\"1\",\"x\":\"p\"},{\"a\":\"2\"},{\"x\":\"q\"}]");

        int rows = testCaseRepository.renameDataFields(ds, Map.of("a", "b"));

        assertThat(rows).isEqualTo(1);
        assertThat(turns(ds, id))
                .isEqualTo(objectMapper.readTree("[{\"b\":\"1\",\"x\":\"p\"},{\"b\":\"2\"},{\"x\":\"q\"}]"));
    }

    @Test
    @DisplayName("explicit JSON null value is preserved under the new key; absent key stays absent")
    void nullPreservedAbsentStaysAbsent() {
        UUID ds = newDatasetId();
        UUID withNull = metaTestDataHelper.seedTestCaseInDataset(ds, "n", "{\"a\":null,\"z\":\"1\"}");
        UUID withoutKey = metaTestDataHelper.seedTestCaseInDataset(ds, "o", "{\"b\":\"B\",\"z\":\"1\"}");

        testCaseRepository.renameDataFields(ds, Map.of("a", "c", "b", "d"));

        JsonNode nullRow = data(ds, withNull);
        assertThat(nullRow.has("c")).isTrue();
        assertThat(nullRow.get("c").isNull()).isTrue();
        assertThat(nullRow.has("a")).isFalse();
        assertThat(nullRow.has("d")).isFalse();
        JsonNode otherRow = data(ds, withoutKey);
        assertThat(otherRow.has("c")).as("absent old key must not materialize").isFalse();
        assertThat(otherRow).isEqualTo(objectMapper.readTree("{\"d\":\"B\",\"z\":\"1\"}"));
    }

    @Test
    @DisplayName("non-array / non-object multi_turn_data shapes are left untouched")
    void unusualMultiTurnShapesUntouched() {
        UUID ds = newDatasetId();
        UUID scalar = metaTestDataHelper.seedTestCaseInDataset(ds, "scalar", "{\"a\":\"1\"}");
        UUID jsonNull = metaTestDataHelper.seedTestCaseInDataset(ds, "jnull", "{\"a\":\"1\"}");
        UUID objectShape = metaTestDataHelper.seedTestCaseInDataset(ds, "obj", "{\"a\":\"1\"}");
        UUID mixed = metaTestDataHelper.seedTestCaseInDataset(ds, "mixed", "{\"a\":\"1\"}");
        metaTestDataHelper.forceRawMultiTurnData(scalar, "\"a\"");
        metaTestDataHelper.forceRawMultiTurnData(jsonNull, "null");
        metaTestDataHelper.forceRawMultiTurnData(objectShape, "{\"a\":\"keep\"}");
        metaTestDataHelper.forceRawMultiTurnData(mixed, "[{\"a\":\"1\"},5,\"s\"]");

        testCaseRepository.renameDataFields(ds, Map.of("a", "b"));

        assertThat(turns(ds, scalar)).isEqualTo(objectMapper.readTree("\"a\""));
        assertThat(turns(ds, jsonNull).isNull()).isTrue();
        assertThat(turns(ds, objectShape)).isEqualTo(objectMapper.readTree("{\"a\":\"keep\"}"));
        assertThat(turns(ds, mixed)).isEqualTo(objectMapper.readTree("[{\"b\":\"1\"},5,\"s\"]"));
        assertThat(data(ds, scalar)).isEqualTo(objectMapper.readTree("{\"b\":\"1\"}"));
    }

    @Test
    @DisplayName("only rows holding an old key are rewritten (returned row count)")
    void onlyRowsWithOldKeyRewritten() {
        UUID ds = newDatasetId();
        UUID holds = metaTestDataHelper.seedTestCaseInDataset(ds, "h", "{\"a\":\"1\"}");
        UUID holdsPerTurn = metaTestDataHelper.seedMultiTurnTestCaseInDataset(ds, "t", "[{\"a\":\"1\"}]");
        UUID none = metaTestDataHelper.seedTestCaseInDataset(ds, "n", "{\"x\":\"1\"}");

        int rows = testCaseRepository.renameDataFields(ds, Map.of("a", "b"));

        assertThat(rows).as("the row without an old key is not in the UPDATE").isEqualTo(2);
        assertThat(data(ds, holds)).isEqualTo(objectMapper.readTree("{\"b\":\"1\"}"));
        assertThat(turns(ds, holdsPerTurn)).isEqualTo(objectMapper.readTree("[{\"b\":\"1\"}]"));
        assertThat(data(ds, none)).isEqualTo(objectMapper.readTree("{\"x\":\"1\"}"));
    }

    @Test
    @DisplayName("other datasets are untouched; empty map is a no-op")
    void otherDatasetsUntouchedAndEmptyNoop() {
        UUID ds = newDatasetId();
        UUID other = newDatasetId();
        UUID mine = metaTestDataHelper.seedTestCaseInDataset(ds, "m", "{\"a\":\"1\"}");
        UUID theirs = metaTestDataHelper.seedTestCaseInDataset(other, "t", "{\"a\":\"1\"}");

        assertThat(testCaseRepository.renameDataFields(ds, Map.of())).isZero();
        assertThat(data(ds, mine)).isEqualTo(objectMapper.readTree("{\"a\":\"1\"}"));

        assertThat(testCaseRepository.renameDataFields(ds, Map.of("a", "b"))).isEqualTo(1);
        assertThat(data(ds, mine)).isEqualTo(objectMapper.readTree("{\"b\":\"1\"}"));
        assertThat(data(other, theirs)).isEqualTo(objectMapper.readTree("{\"a\":\"1\"}"));
    }
}
