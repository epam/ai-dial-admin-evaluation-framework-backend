package com.epam.aidial.evaluation.experimental.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SchemaFieldTypeMapper")
class SchemaFieldTypeMapperTest {

    private final SchemaFieldTypeMapper mapper = new SchemaFieldTypeMapper();

    @Test
    @DisplayName("maps each dataset schema type to the DSL field-type vocabulary")
    void mapsEachType() {
        assertThat(mapper.map(SchemaFieldType.STRING)).isEqualTo(QueryFieldType.STRING);
        assertThat(mapper.map(SchemaFieldType.FILE)).isEqualTo(QueryFieldType.STRING);
        assertThat(mapper.map(SchemaFieldType.INTEGER)).isEqualTo(QueryFieldType.LONG);
        assertThat(mapper.map(SchemaFieldType.NUMBER)).isEqualTo(QueryFieldType.DECIMAL);
        assertThat(mapper.map(SchemaFieldType.BOOLEAN)).isEqualTo(QueryFieldType.BOOLEAN);
        assertThat(mapper.map(SchemaFieldType.OBJECT)).isEqualTo(QueryFieldType.OBJECT);
        assertThat(mapper.map(SchemaFieldType.ARRAY)).isEqualTo(QueryFieldType.ARRAY);
    }

    @Test
    @DisplayName("null type defaults to string")
    void nullDefaultsToString() {
        assertThat(mapper.map(null)).isEqualTo(QueryFieldType.STRING);
    }
}
