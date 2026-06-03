package com.epam.aidial.evaluation.data.db.repository.sql.json;

import java.math.BigDecimal;
import org.jooq.Field;
import org.jooq.JSONB;

public interface JsonPathAccessor {

    Field<JSONB> jsonbAt(Field<JSONB> column, Field<String> key);

    Field<String> jsonbAtAsText(Field<JSONB> column, Field<String> key);

    Field<BigDecimal> jsonbAtAsNumeric(Field<JSONB> column, Field<String> key1, Field<String> key2);
}
