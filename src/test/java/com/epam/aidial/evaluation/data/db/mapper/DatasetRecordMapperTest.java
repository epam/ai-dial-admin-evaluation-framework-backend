package com.epam.aidial.evaluation.data.db.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.DatasetsRecord;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DatasetRecordMapper")
class DatasetRecordMapperTest {

    private final DatasetRecordMapper mapper = new DatasetRecordMapper();

    @Test
    @DisplayName("maps every column from generated record to domain model")
    void mapsAllFields() {
        UUID id = UUID.randomUUID();
        DatasetsRecord record = new DatasetsRecord();
        record.setId(id.toString());
        record.setName("My Dataset");
        record.setDescription("desc");
        record.setTestCaseSchema(JSONB.valueOf("[{\"name\":\"q\",\"type\":\"STRING\"}]"));
        record.setIsValid(true);
        record.setValidationWarnings(JSONB.valueOf("[]"));
        record.setVisibility("PUBLIC");
        record.setVersion(7L);
        record.setCreatedBy("alice@example.com");
        record.setCreatedAtMs(1_000L);
        record.setUpdatedAtMs(2_000L);

        Dataset dataset = mapper.map(record);

        assertThat(dataset.getId()).isEqualTo(id);
        assertThat(dataset.getName()).isEqualTo("My Dataset");
        assertThat(dataset.getDescription()).isEqualTo("desc");
        assertThat(dataset.getTestCaseSchema()).isEqualTo("[{\"name\":\"q\",\"type\":\"STRING\"}]");
        assertThat(dataset.isValid()).isTrue();
        assertThat(dataset.getValidationWarnings()).isEqualTo("[]");
        assertThat(dataset.getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
        assertThat(dataset.getVersion()).isEqualTo(7L);
        assertThat(dataset.getCreatedBy()).isEqualTo("alice@example.com");
        assertThat(dataset.getCreatedAt()).isEqualTo(1_000L);
        assertThat(dataset.getUpdatedAt()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("maps null JSONB columns to null strings")
    void mapsNullJsonbColumnsAsNull() {
        UUID id = UUID.randomUUID();
        DatasetsRecord record = new DatasetsRecord();
        record.setId(id.toString());
        record.setName("Dataset");
        record.setTestCaseSchema(null);
        record.setIsValid(false);
        record.setValidationWarnings(null);
        record.setVisibility("PUBLIC");
        record.setVersion(1L);
        record.setCreatedAtMs(0L);
        record.setUpdatedAtMs(0L);

        Dataset dataset = mapper.map(record);

        assertThat(dataset.getTestCaseSchema()).isNull();
        assertThat(dataset.getValidationWarnings()).isNull();
        assertThat(dataset.isValid()).isFalse();
    }
}
