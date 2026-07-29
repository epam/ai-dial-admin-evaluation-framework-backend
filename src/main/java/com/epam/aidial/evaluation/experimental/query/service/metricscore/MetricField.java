package com.epam.aidial.evaluation.experimental.query.service.metricscore;

/**
 * A flattened numeric metric column of the {@code eval_summaries} entity.
 *
 * @param flattenedName the DSL field name, {@code metric::<tsmd>::<field>}
 * @param metricName the stored {@code metric_name}, {@code <tsmd>.<field>}
 */
public record MetricField(String flattenedName, String metricName) {}
