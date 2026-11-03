package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMetric;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks the metric references in a suite's {@code overallScore} / {@code testCaseOverallScore} against the
 * names of the suite's metric definitions (exact, case-sensitive). Never throws; findings are returned as
 * warnings. The only supported {@code custom_function} shape is a single {@code roc_auc(field, field)}
 * select item.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ScoreReferenceValidator {

    static final String OVERALL_SCORE_ROOT = "$.overallScore";
    static final String TEST_CASE_OVERALL_SCORE_ROOT = "$.testCaseOverallScore";
    private static final String ROC_AUC_FUNCTION = "roc_auc";
    private static final int ROC_AUC_ARG_COUNT = 2;
    private static final String EXPRESSION_PATH = OVERALL_SCORE_ROOT + ".expression";
    private static final String ARG_PATH_FORMAT = EXPRESSION_PATH + ".select[0].expr.args[%d].name";

    private final JsonbMapper jsonbMapper;
    private final ObjectMapper objectMapper;

    public List<ValidationWarningDto> validate(TestSuite suite, Set<String> metricNames) {
        final List<ValidationWarningDto> warnings = new ArrayList<>();
        validateOverallScore(suite.getOverallScore(), metricNames, warnings);
        validateTestCaseOverallScore(suite.getTestCaseOverallScore(), metricNames, warnings);
        return warnings;
    }

    private void validateOverallScore(String json, Set<String> metricNames, List<ValidationWarningDto> warnings) {
        final OverallScoreDefinition definition;
        try {
            definition = jsonbMapper.mapOverallScore(json);
        } catch (IllegalArgumentException e) {
            log.warn("Unreadable overallScore, reporting as unsupported: {}", e.getMessage(), e);
            warnings.add(unsupported());
            return;
        }
        switch (definition) {
            case WeightedMean weightedMean ->
                checkWeights(weightedMean, OVERALL_SCORE_ROOT, metricNames, warnings);
            case CustomFunction customFunction -> checkCustomFunction(customFunction, metricNames, warnings);
            case null, default -> {}
        }
    }

    private void validateTestCaseOverallScore(
            String json, Set<String> metricNames, List<ValidationWarningDto> warnings) {
        try {
            if (jsonbMapper.mapTestCaseOverallScore(json) instanceof WeightedMean weightedMean) {
                checkWeights(weightedMean, TEST_CASE_OVERALL_SCORE_ROOT, metricNames, warnings);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Unreadable testCaseOverallScore, ignoring: {}", e.getMessage(), e);
        }
    }

    private void checkWeights(
            WeightedMean weightedMean, String root, Set<String> metricNames, List<ValidationWarningDto> warnings) {
        final List<WeightedMetric> weights = weightedMean.weights();
        if (weights == null) {
            return;
        }
        for (int i = 0; i < weights.size(); i++) {
            final WeightedMetric weight = weights.get(i);
            if (weight != null && weight.metricName() != null && !metricNames.contains(weight.metricName())) {
                warnings.add(unresolved(weight.metricName(), root + ".weights[" + i + "].metricName"));
            }
        }
    }

    private void checkCustomFunction(
            CustomFunction customFunction, Set<String> metricNames, List<ValidationWarningDto> warnings) {
        final FnExpr rocAuc = parseSupportedRocAuc(customFunction);
        if (rocAuc == null) {
            warnings.add(unsupported());
            return;
        }
        for (int i = 0; i < rocAuc.args().size(); i++) {
            if (rocAuc.args().get(i) instanceof FieldExpr(String name)
                    && name != null
                    && name.startsWith(EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX)) {
                final String tsmdName = tsmdName(name);
                if (!metricNames.contains(tsmdName)) {
                    warnings.add(unresolved(tsmdName, ARG_PATH_FORMAT.formatted(i)));
                }
            }
        }
    }

    /** The {@code roc_auc(a, b)} call when the expression has exactly the supported shape, else {@code null}. */
    private FnExpr parseSupportedRocAuc(CustomFunction customFunction) {
        final StructuredQuery query;
        try {
            query = objectMapper.convertValue(customFunction.expression(), StructuredQuery.class);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Unconvertible overallScore expression, reporting as unsupported: {}", e.getMessage(), e);
            return null;
        }
        if (query == null || query.select() == null || query.select().size() != 1) {
            return null;
        }
        final OutputColumn column = query.select().get(0);
        final Expr expr = column == null ? null : column.expr();
        if (expr instanceof FnExpr fn
                && ROC_AUC_FUNCTION.equals(fn.name())
                && fn.args() != null
                && fn.args().size() == ROC_AUC_ARG_COUNT) {
            return fn;
        }
        return null;
    }

    private static String tsmdName(String fieldName) {
        final String rest = fieldName.substring(EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX.length());
        final int separator = rest.lastIndexOf(EvalSummaryExportColumnConstants.COLUMN_SEPARATOR);
        return separator < 0 ? rest : rest.substring(0, separator);
    }

    private static ValidationWarningDto unresolved(String metricName, String path) {
        return ValidationWarningDto.builder()
                .fieldName(metricName)
                .path(path)
                .message("Score references metric definition '" + metricName + "' which does not exist in this suite")
                .code(ValidationWarningCode.UNRESOLVED_REFERENCE)
                .build();
    }

    private static ValidationWarningDto unsupported() {
        return ValidationWarningDto.builder()
                .fieldName("overallScore")
                .path(EXPRESSION_PATH)
                .message("overallScore custom_function is not supported; only roc_auc(field, field) is allowed")
                .code(ValidationWarningCode.UNSUPPORTED_SCORE_FUNCTION)
                .build();
    }
}
