package com.epam.aidial.evaluation.data.db.repository.sql.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PostgresJsonPathAccessor numeric extraction")
class PostgresJsonPathAccessorTest {

    private final PostgresJsonPathAccessor accessor = new PostgresJsonPathAccessor();
    private final DSLContext postgres = DSL.using(SQLDialect.POSTGRES);
    private final Field<JSONB> column = DSL.field(DSL.name("payload"), SQLDataType.JSONB);

    @ParameterizedTest
    @ValueSource(strings = {"score", "score::value", "score' OR true --"})
    @DisplayName("one-key numeric extraction casts JSONB text to BigDecimal and binds the key as a parameter")
    void shouldExtractNumericWithParameterizedKey(String key) {
        final Field<BigDecimal> field = accessor.jsonbAtAsNumeric(column, DSL.val(key));

        assertThat(field.getType()).isEqualTo(BigDecimal.class);
        assertThat(postgres.render(field)).contains("\"payload\"->>?").contains("as numeric");
        assertThat(DSL.select(field).getBindValues()).containsExactly(key);
    }

    @Test
    @DisplayName("two-key numeric extraction traverses JSONB before casting text and binds both keys")
    void shouldExtractNumericWithTwoParameterizedKeys() {
        final Field<BigDecimal> field = accessor.jsonbAtAsNumeric(column, DSL.val("metric"), DSL.val("score"));

        assertThat(field.getType()).isEqualTo(BigDecimal.class);
        assertThat(postgres.render(field))
                .contains("\"payload\"->?")
                .contains("->>?")
                .contains("as numeric");
        assertThat(DSL.select(field).getBindValues()).containsExactly("metric", "score");
    }
}
