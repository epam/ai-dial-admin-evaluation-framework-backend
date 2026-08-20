package com.epam.aidial.evaluation.query.service.translate;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.COLUMN_SEPARATOR;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.DATA_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_INFO_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.RESPONSE_COLUMN_PREFIX;

import com.epam.aidial.evaluation.data.db.repository.sql.json.JsonPathAccessor;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Resolves the <em>flattened</em> field names a complex entity's detailed schema publishes — the
 * {@code data::}/{@code response::}/{@code metric::}/{@code metricInfo::} families
 * ({@link com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants}) — into jOOQ JSONB
 * path expressions over the entity's backing JSONB columns. This is the execution-side counterpart
 * of {@code EvalSummariesSchemaProvider}: the schema endpoint advertises the flat names, this turns
 * a referenced name back into the path it denotes so projection/filter/sort/aggregate work on it.
 *
 * <p>Also handles the {@code deployment_ref::} and {@code mcp_deployment_ref::} families for the
 * {@code test_suites} entity, resolving them to text extractions over the respective JSONB columns.
 *
 * <p>Resolution is keyed off the backing JSONB column being present in the supplied bindings, so it
 * activates only for entities that actually have it; on an entity without the column a prefixed name
 * returns {@code null} and the caller rejects it as unknown. Paths mirror the production analytics
 * layer: metric values are addressed by a two-level {@code metric_values -> '<metric>' ->> '<field>'}
 * cast to {@code numeric} (every metric output value is a number);
 * {@code data::}/{@code response::}/{@code deployment_ref::}/{@code mcp_deployment_ref::} extract
 * text; {@code metricInfo::} keeps the raw JSONB object. Key components are bound parameters, never
 * concatenated into SQL.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class JsonbFieldResolver {

    private static final String TEST_CASE_DATA_FIELD = "test_case_data";
    private static final String EXTRACTED_COLUMNS_FIELD = "extracted_columns";
    private static final String METRIC_VALUES_FIELD = "metric_values";
    private static final String METRIC_INFOS_FIELD = "metric_infos";
    private static final String DEPLOYMENT_REF_FIELD = "deployment_ref";
    private static final String MCP_DEPLOYMENT_REF_FIELD = "mcp_deployment_ref";
    private static final String DEPLOYMENT_REF_PREFIX = DEPLOYMENT_REF_FIELD + COLUMN_SEPARATOR;
    private static final String MCP_DEPLOYMENT_REF_PREFIX = MCP_DEPLOYMENT_REF_FIELD + COLUMN_SEPARATOR;

    private final JsonPathAccessor jsonPathAccessor;

    /**
     * Returns the JSONB path field for a flattened {@code name}, or {@code null} when {@code name} is
     * not a flattened family or the backing column is absent from {@code bindings} (so the caller can
     * reject it as an unknown field).
     */
    public Field<?> resolve(String name, Map<String, QueryFieldBinding> bindings) {
        if (name == null) {
            return null;
        }
        if (name.startsWith(DATA_COLUMN_PREFIX)) {
            return textPath(bindings, TEST_CASE_DATA_FIELD, suffix(name, DATA_COLUMN_PREFIX), name);
        }
        if (name.startsWith(RESPONSE_COLUMN_PREFIX)) {
            return textPath(bindings, EXTRACTED_COLUMNS_FIELD, suffix(name, RESPONSE_COLUMN_PREFIX), name);
        }
        if (name.startsWith(METRIC_INFO_COLUMN_PREFIX)) {
            return jsonbPath(bindings, METRIC_INFOS_FIELD, suffix(name, METRIC_INFO_COLUMN_PREFIX), name);
        }
        if (name.startsWith(METRIC_COLUMN_PREFIX)) {
            return metricPath(bindings, suffix(name, METRIC_COLUMN_PREFIX), name);
        }
        if (name.startsWith(DEPLOYMENT_REF_PREFIX)) {
            return textPath(bindings, DEPLOYMENT_REF_FIELD, suffix(name, DEPLOYMENT_REF_PREFIX), name);
        }
        if (name.startsWith(MCP_DEPLOYMENT_REF_PREFIX)) {
            return textPath(bindings, MCP_DEPLOYMENT_REF_FIELD, suffix(name, MCP_DEPLOYMENT_REF_PREFIX), name);
        }
        return null;
    }

    private Field<?> textPath(Map<String, QueryFieldBinding> bindings, String baseField, String key, String fullName) {
        final Field<JSONB> column = jsonbColumn(bindings, baseField);
        if (column == null) {
            return null;
        }
        requireKey(key, fullName);
        return jsonPathAccessor.jsonbAtAsText(column, DSL.val(key));
    }

    private Field<?> jsonbPath(Map<String, QueryFieldBinding> bindings, String baseField, String key, String fullName) {
        final Field<JSONB> column = jsonbColumn(bindings, baseField);
        if (column == null) {
            return null;
        }
        requireKey(key, fullName);
        return jsonPathAccessor.jsonbAt(column, DSL.val(key));
    }

    private Field<?> metricPath(Map<String, QueryFieldBinding> bindings, String suffix, String fullName) {
        final Field<JSONB> column = jsonbColumn(bindings, METRIC_VALUES_FIELD);
        if (column == null) {
            return null;
        }
        final int separator = suffix.lastIndexOf(COLUMN_SEPARATOR);
        if (separator <= 0 || separator == suffix.length() - COLUMN_SEPARATOR.length()) {
            throw new ValidationException(
                    "metric field must be of the form 'metric::<metricName>::<outputField>': '" + fullName + "'");
        }
        final String metricName = suffix.substring(0, separator);
        final String outputField = suffix.substring(separator + COLUMN_SEPARATOR.length());
        return jsonPathAccessor.jsonbAtAsNumeric(column, DSL.val(metricName), DSL.val(outputField));
    }

    private static String suffix(String name, String prefix) {
        return name.substring(prefix.length());
    }

    private static void requireKey(String key, String fullName) {
        if (key.isEmpty()) {
            throw new ValidationException("flattened field must name a key: '" + fullName + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private static Field<JSONB> jsonbColumn(Map<String, QueryFieldBinding> bindings, String baseField) {
        final QueryFieldBinding binding = bindings.get(baseField);
        if (binding == null || !binding.type().isJsonb()) {
            return null;
        }
        return (Field<JSONB>) binding.field();
    }
}
