package com.epam.aidial.evaluation.query.service.translate.function;

import com.epam.aidial.evaluation.data.db.repository.sql.DialectAwareSql;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/**
 * {@code roc_auc(label, probability)} — computes the ROC AUC score (rank-sum / Mann-Whitney
 * formulation) over the matching rows. Both columns are aggregated via {@code array_agg} (index-aligned
 * so {@code label[i]}/{@code probability[i]} correspond to the same row) and the ranking computation is
 * delegated to the {@code roc_auc_score} stored function (analytics DB,
 * {@code V1.11__CreateRocAucScoreFunction.sql}), since ranking rows before aggregating cannot be
 * expressed as a single jOOQ-built-in {@link Field}.
 *
 * <p>This function serves the analytics datasource only, which is Postgres or ClickHouse depending on
 * {@code datasource.analytics.vendor} — see {@link DialectAwareSql} for why the choice is rendered at
 * query-render time rather than baked into a vendor-gated bean. The ClickHouse branch delegates to the
 * built-in {@code arrayAUC(scores, labels)} function instead of a stored function; note it takes
 * {@code (scores, labels)} — the argument order is swapped relative to {@code roc_auc_score(labels,
 * scores)}.
 */
@Component
@LogExecution
public class RocAucFunction implements QueryFunction {

    @Override
    public String name() {
        return "roc_auc";
    }

    @Override
    public Field<?> translate(FnExpr fn, FunctionContext ctx) {
        final List<Expr> args = ctx.args(fn);
        if (args.size() != 2) {
            throw new ValidationException("function 'roc_auc' expects exactly two arguments (label, probability)");
        }
        final Field<Double> label = ctx.toField(args.get(0)).cast(Double.class);
        final Field<Double> probability = ctx.toField(args.get(1)).cast(Double.class);
        return DialectAwareSql.field(
                "roc_auc",
                SQLDataType.DOUBLE,
                family -> family == SQLDialect.CLICKHOUSE
                        ? DSL.function(
                                "arrayAUC",
                                Double.class,
                                DSL.function("groupArray", Object.class, probability),
                                DSL.function("groupArray", Object.class, label))
                        : DSL.function("roc_auc_score", Double.class, DSL.arrayAgg(label), DSL.arrayAgg(probability)));
    }
}
