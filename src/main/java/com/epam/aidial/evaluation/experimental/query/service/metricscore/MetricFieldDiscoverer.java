package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.COLUMN_SEPARATOR;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Flattens a run's metric snapshots into the numeric {@code metric::<tsmd>::<field>} columns that metric
 * scores are computed over.
 *
 * <p>Sole owner of the flattening rule for metric-score discovery, so that two callers cannot disagree about
 * which fields a run has. That divergence is invisible to an output-parity test: two discoverers could find
 * different field sets and still agree on every value they both produced.
 *
 * <p>The rule composes {@code metric_name} as {@code <tsmd>.<field>}, but the reverse is <strong>not</strong>
 * recoverable by splitting on {@code .} — a tsmd name may itself contain dots. Callers needing both halves
 * must take them from here or from the snapshot, never from the composed name.
 *
 * <p>Snapshots are a parameter, not something this component loads: callers already hold them, and passing
 * them keeps discovery free of a repository dependency.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class MetricFieldDiscoverer {

    private static final String METRIC_NAME_SEPARATOR = ".";

    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor;

    /**
     * @return one entry per numeric output field of every supplied snapshot, in snapshot order; empty when
     *     the run declared no metric fields
     */
    public List<MetricField> discover(List<RunMetricSnapshot> snapshots) {
        final List<MetricField> fields = new ArrayList<>();
        for (final RunMetricSnapshot snapshot : snapshots) {
            for (final String outputField : outputSchemaFieldExtractor.extractFieldNames(snapshot.getOutputSchema())) {
                final String flattenedName =
                        METRIC_COLUMN_PREFIX + snapshot.getTsmdName() + COLUMN_SEPARATOR + outputField;
                final String metricName = snapshot.getTsmdName() + METRIC_NAME_SEPARATOR + outputField;
                fields.add(new MetricField(flattenedName, metricName));
            }
        }
        return fields;
    }
}
