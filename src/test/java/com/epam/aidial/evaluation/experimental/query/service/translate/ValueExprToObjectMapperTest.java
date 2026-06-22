package com.epam.aidial.evaluation.experimental.query.service.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValueExprToObjectMapperTest {

    private final ValueExprToObjectMapper mapper = new ValueExprToObjectMapper();

    @Test
    @DisplayName("coerces each value_type to its typed Java value")
    void coercesTypedValues() {
        assertThat(mapper.map(new ValueExpr(ValueType.STRING, "hello"))).isEqualTo("hello");
        assertThat(mapper.map(new ValueExpr(ValueType.INTEGER, "42"))).isEqualTo(42);
        assertThat(mapper.map(new ValueExpr(ValueType.LONG, "9000000000"))).isEqualTo(9_000_000_000L);
        assertThat(mapper.map(new ValueExpr(ValueType.DECIMAL, "3.14"))).isEqualTo(new BigDecimal("3.14"));
        assertThat(mapper.map(new ValueExpr(ValueType.BOOLEAN, "true"))).isEqualTo(Boolean.TRUE);
        assertThat(mapper.map(new ValueExpr(ValueType.DATE, "2026-06-12"))).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(mapper.map(new ValueExpr(ValueType.TIMESTAMP, "1700000000000")))
                .isEqualTo(1_700_000_000_000L);
    }

    @Test
    @DisplayName("normalises a uuid literal to its canonical string form")
    void normalisesUuid() {
        String uuid = "0000000A-0000-0000-0000-000000000001";
        assertThat(mapper.map(new ValueExpr(ValueType.UUID, uuid))).isEqualTo(uuid.toLowerCase());
    }

    @Test
    @DisplayName("returns null for the null value_type")
    void coercesNullType() {
        assertThat(mapper.map(new ValueExpr(ValueType.NULL, null))).isNull();
    }

    @Test
    @DisplayName("rejects a malformed literal with a ValidationException")
    void rejectsMalformedLiteral() {
        assertThatThrownBy(() -> mapper.map(new ValueExpr(ValueType.INTEGER, "not-a-number")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid integer");
    }

    @Test
    @DisplayName("rejects a missing value_type")
    void rejectsMissingValueType() {
        assertThatThrownBy(() -> mapper.map(new ValueExpr(null, "x")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("value_type");
    }
}
