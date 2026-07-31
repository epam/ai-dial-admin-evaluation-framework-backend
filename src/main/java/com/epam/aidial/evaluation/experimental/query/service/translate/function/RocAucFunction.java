package com.epam.aidial.evaluation.experimental.query.service.translate.function;

import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * {@code roc_auc(label, probability)} — computes the ROC AUC score (rank-sum / Mann-Whitney
 * formulation) over the matching rows. Both columns are aggregated via {@code array_agg} (index-aligned
 * so {@code label[i]}/{@code probability[i]} correspond to the same row) and the ranking computation is
 * delegated to the {@code roc_auc_score} stored function (analytics DB,
 * {@code V1.11__CreateRocAucScoreFunction.sql}), since ranking rows before aggregating cannot be
 * expressed as a single jOOQ-built-in {@link Field}.
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
        return DSL.function("roc_auc_score", Double.class, DSL.arrayAgg(label), DSL.arrayAgg(probability));
    }
}
