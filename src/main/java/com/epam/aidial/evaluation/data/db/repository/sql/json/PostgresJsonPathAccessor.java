package com.epam.aidial.evaluation.data.db.repository.sql.json;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.math.BigDecimal;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class PostgresJsonPathAccessor implements JsonPathAccessor {

    @Override
    public Field<JSONB> jsonbAt(Field<JSONB> column, Field<String> key) {
        return DSL.jsonbGetAttribute(column, key);
    }

    @Override
    public Field<String> jsonbAtAsText(Field<JSONB> column, Field<String> key) {
        return DSL.jsonbGetAttributeAsText(column, key);
    }

    @Override
    public Field<BigDecimal> jsonbAtAsNumeric(Field<JSONB> column, Field<String> key1, Field<String> key2) {
        return DSL.jsonbGetAttributeAsText(DSL.jsonbGetAttribute(column, key1), key2)
                .cast(SQLDataType.NUMERIC);
    }
}
