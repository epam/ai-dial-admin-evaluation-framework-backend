package com.epam.aidial.evaluation.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.filter.FilterOperator;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.model.pagination.SortKey;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterFieldDefinition;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterFieldType;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterSpec;
import com.epam.aidial.evaluation.data.db.repository.sql.SortSpec;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class QueryParamDescriptionGeneratorTest {

    @Test
    void filterDescriptionContainsFieldOperatorTable() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "name",
                        FilterFieldDefinition.of(
                                "name", FilterFieldType.STRING, EnumSet.of(FilterOperator.EQ, FilterOperator.CO)),
                "createdAt",
                        FilterFieldDefinition.of(
                                "created_at_ms",
                                FilterFieldType.LONG,
                                EnumSet.of(FilterOperator.GT, FilterOperator.LT))));

        String description = QueryParamDescriptionGenerator.generateFilterDescription(spec);

        assertThat(description).contains("| Field | Type | Operators | Example |");
        assertThat(description).contains("| name |");
        assertThat(description).contains("| createdAt |");
    }

    @Test
    void filterDescriptionContainsFormatAndSemantics() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "name", FilterFieldDefinition.of("name", FilterFieldType.STRING, EnumSet.of(FilterOperator.EQ))));

        String description = QueryParamDescriptionGenerator.generateFilterDescription(spec);

        assertThat(description).contains("field:operator:value");
        assertThat(description).contains("max 32");
        assertThat(description).contains("AND logic");
    }

    @Test
    void filterDescriptionShowsStringTypeLabel() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "name", FilterFieldDefinition.of("name", FilterFieldType.STRING, EnumSet.of(FilterOperator.EQ))));

        String description = QueryParamDescriptionGenerator.generateFilterDescription(spec);

        assertThat(description).contains("| string |");
    }

    @Test
    void filterDescriptionShowsTimestampTypeLabelForCreatedAt() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "createdAt",
                FilterFieldDefinition.of("created_at_ms", FilterFieldType.LONG, EnumSet.of(FilterOperator.GT))));

        String description = QueryParamDescriptionGenerator.generateFilterDescription(spec);

        assertThat(description).contains("| timestamp (epoch ms) |");
    }

    @Test
    void filterDescriptionShowsIntegerTypeLabelForNonTimestampLong() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "runIndex",
                FilterFieldDefinition.of("run_index", FilterFieldType.LONG, EnumSet.of(FilterOperator.EQ))));

        String description = QueryParamDescriptionGenerator.generateFilterDescription(spec);

        assertThat(description).contains("| integer |");
    }

    @Test
    void filterDescriptionShowsBooleanTypeLabel() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "enabled",
                FilterFieldDefinition.of("is_enabled", FilterFieldType.BOOLEAN, EnumSet.of(FilterOperator.EQ))));

        String description = QueryParamDescriptionGenerator.generateFilterDescription(spec);

        assertThat(description).contains("| boolean (true/false) |");
    }

    @Test
    void filterDescriptionShowsUuidTypeLabel() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "testSuiteId",
                FilterFieldDefinition.of("test_suite_id", FilterFieldType.UUID, EnumSet.of(FilterOperator.EQ))));

        String description = QueryParamDescriptionGenerator.generateFilterDescription(spec);

        assertThat(description).contains("| uuid |");
    }

    @Test
    void filterDescriptionShowsJsonbStringTypeLabel() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "testCaseData",
                FilterFieldDefinition.of(
                        "test_case_data", FilterFieldType.JSONB_STRING, EnumSet.of(FilterOperator.CO))));

        String description = QueryParamDescriptionGenerator.generateFilterDescription(spec);

        assertThat(description).contains("| jsonb string |");
    }

    @Test
    void filterExampleUsesCoForStringField() {
        FilterSpec spec = FilterSpec.of(Map.of(
                "name",
                FilterFieldDefinition.of(
                        "name", FilterFieldType.STRING, EnumSet.of(FilterOperator.EQ, FilterOperator.CO))));

        String example = QueryParamDescriptionGenerator.generateFilterExample(spec);

        assertThat(example).isEqualTo("name:co:test");
    }

    @Test
    void sortDescriptionContainsFieldListAndDefault() {
        SortSpec spec = SortSpec.of(
                Map.of("name", DSL.field("name"), "createdAt", DSL.field("created_at_ms")),
                List.of(SortKey.builder()
                        .field("createdAt")
                        .direction(PageRequest.SortDirection.DESC)
                        .build()));

        String description = QueryParamDescriptionGenerator.generateSortDescription(spec);

        assertThat(description).contains("field[,asc|desc]");
        assertThat(description).contains("max 32");
        assertThat(description).contains("createdAt,desc");
        assertThat(description).contains("createdAt");
        assertThat(description).contains("name");
    }

    @Test
    void sortExampleUsesDefaultSort() {
        SortSpec spec = SortSpec.of(
                Map.of("createdAt", DSL.field("created_at_ms")),
                List.of(SortKey.builder()
                        .field("createdAt")
                        .direction(PageRequest.SortDirection.DESC)
                        .build()));

        String example = QueryParamDescriptionGenerator.generateSortExample(spec);

        assertThat(example).isEqualTo("createdAt,desc");
    }

    @Test
    void pageDescriptionContainsDefault() {
        String description = QueryParamDescriptionGenerator.generatePageDescription();

        assertThat(description).contains("0-indexed");
        assertThat(description).contains("Default: 0");
    }

    @Test
    void sizeDescriptionContainsDefaultAndMax() {
        String description = QueryParamDescriptionGenerator.generateSizeDescription(20, 100);

        assertThat(description).contains("Default: 20");
        assertThat(description).contains("max: 100");
    }

    @Test
    void cursorDescriptionContainsNextCursorReference() {
        String description = QueryParamDescriptionGenerator.generateCursorDescription();

        assertThat(description).contains("nextCursor");
        assertThat(description).contains("first page");
    }
}
