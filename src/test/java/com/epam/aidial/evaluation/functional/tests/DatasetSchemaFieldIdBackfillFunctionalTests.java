package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Functional verification of the V1.36 dataset schema field-id backfill script. */
@DisplayName("Dataset schema field id backfill (V1.36)")
public abstract class DatasetSchemaFieldIdBackfillFunctionalTests extends BaseFunctionalTest {

    private static final String EXISTING_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    @DisplayName("fills missing and null ids, keeps order and existing ids, skips non-array, and is idempotent")
    void backfillsIdsIdempotently() {
        final Dataset mixed = metaTestDataHelper.createDataset(
                "backfill-mixed",
                "[{\"name\":\"a\",\"type\":\"STRING\"},{\"name\":\"b\",\"type\":\"STRING\",\"id\":null},"
                        + "{\"name\":\"c\",\"type\":\"STRING\",\"id\":\"" + EXISTING_ID + "\"}]");
        final Dataset withScalars = metaTestDataHelper.createDataset(
                "backfill-non-object-elements", "[\"x\",{\"name\":\"d\",\"type\":\"STRING\"},null]");
        final String nonArraySchema = "{\"not\":\"an array\"}";
        final Dataset nonArray = metaTestDataHelper.createDataset("backfill-non-array", nonArraySchema);

        metaTestDataHelper.applyV1_36FieldIdBackfill();

        final JsonNode first = objectMapper.readTree(metaTestDataHelper.findDatasetSchemaJson(mixed.getId()));
        assertThat(names(first)).containsExactly("a", "b", "c");
        first.forEach(f -> assertThat(f.get("id").isString()).isTrue());
        assertThat(first.get(2).get("id").asString()).isEqualTo(EXISTING_ID);
        final JsonNode scalars = objectMapper.readTree(metaTestDataHelper.findDatasetSchemaJson(withScalars.getId()));
        assertThat(scalars.size()).isEqualTo(3);
        assertThat(scalars.get(0).isString()).isTrue();
        assertThat(scalars.get(0).asString()).isEqualTo("x");
        assertThat(scalars.get(1).get("name").asString()).isEqualTo("d");
        assertThat(scalars.get(1).get("id").isString()).isTrue();
        assertThat(scalars.get(2).isNull()).isTrue();
        assertThat(objectMapper.readTree(metaTestDataHelper.findDatasetSchemaJson(nonArray.getId())))
                .isEqualTo(objectMapper.readTree(nonArraySchema));

        metaTestDataHelper.applyV1_36FieldIdBackfill();

        final JsonNode second = objectMapper.readTree(metaTestDataHelper.findDatasetSchemaJson(mixed.getId()));
        assertThat(second).isEqualTo(first);
        assertThat(objectMapper.readTree(metaTestDataHelper.findDatasetSchemaJson(withScalars.getId())))
                .isEqualTo(scalars);
    }

    private static List<String> names(JsonNode schema) {
        final List<String> names = new ArrayList<>();
        schema.forEach(f -> names.add(f.get("name").asString()));
        return names;
    }
}
