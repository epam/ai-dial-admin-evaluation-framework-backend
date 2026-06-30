package com.epam.aidial.evaluation.data.db.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the metric-score seed migration against drift from the StructuredQuery Jackson wire contract and
 * the {@link MetricScoreConstants} names: every seeded {@code ::jsonb} expression MUST deserialize
 * into a {@link StructuredQuery} over {@code eval_summaries} in aggregate mode, selecting a single
 * {@code value} alias and referencing the run-scoping params plus either the per-metric
 * {@code metricField} (leaf statistics) or the run-level {@code metricAvgs} ({@code overall}). A clean
 * compile does not prove the seed JSON binds — only deserialization does.
 */
class MetricScoreSeedGuardTest {

    private static final String SEED_RESOURCE = "/db/migration/meta/POSTGRES/V1.24__SeedMetricScoreDefinitions.sql";

    private static final Pattern JSONB_LITERAL = Pattern.compile("'(\\{.*?})'::jsonb");

    private final JsonMapper mapper = new JsonMapperConfiguration().objectMapper();

    @Test
    void everySeededExpressionDeserializesIntoStructuredQuery() throws IOException {
        final List<String> expressions = extractJsonbLiterals();
        // 5 DEFAULT per-metric statistics (AVG, P10, P90, MIN, MAX). The overall score is no longer
        // seeded — it is a per-suite property (test_suites.overall_score) defaulting to a Java constant.
        assertThat(expressions).hasSize(5);

        for (final String expression : expressions) {
            final StructuredQuery query = mapper.readValue(expression, StructuredQuery.class);
            assertThat(query.entity()).isEqualTo(MetricScoreConstants.ENTITY_EVAL_SUMMARIES);
            assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
            assertThat(query.select()).hasSize(1);
            assertThat(query.select().getFirst().as()).isEqualTo(MetricScoreConstants.VALUE_ALIAS);
            assertThat(expression)
                    .contains(MetricScoreConstants.PARAM_RUN_ID)
                    .contains(MetricScoreConstants.PARAM_COMPUTATION_ID)
                    .contains(MetricScoreConstants.PARAM_METRIC_FIELD);
        }
    }

    private List<String> extractJsonbLiterals() throws IOException {
        final String sql;
        try (InputStream in = getClass().getResourceAsStream(SEED_RESOURCE)) {
            assertThat(in).as("seed migration on classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final Matcher matcher = JSONB_LITERAL.matcher(sql);
        final List<String> literals = new ArrayList<>();
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }
}
