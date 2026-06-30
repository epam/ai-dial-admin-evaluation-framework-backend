package com.epam.aidial.evaluation.experimental.query.service.translate;

import com.epam.aidial.evaluation.experimental.query.service.translate.function.BuiltInQueryFunctions;
import com.epam.aidial.evaluation.experimental.query.service.translate.function.QueryFunction;
import com.epam.aidial.evaluation.experimental.query.service.translate.function.QueryFunctionRegistry;
import java.util.List;

/** Assembles a {@link QueryFunctionRegistry} with the full function catalog for non-Spring unit tests. */
final class QueryFunctionTestSupport {

    private QueryFunctionTestSupport() {}

    static QueryFunctionRegistry registry(ValueExprToObjectMapper valueExprToObjectMapper) {
        final BuiltInQueryFunctions builtIns = new BuiltInQueryFunctions(valueExprToObjectMapper);
        final List<QueryFunction> functions = List.of(
                builtIns.lowerFunction(),
                builtIns.upperFunction(),
                builtIns.lengthFunction(),
                builtIns.trimFunction(),
                builtIns.absFunction(),
                builtIns.widthBucketFunction(),
                builtIns.countFunction(),
                builtIns.sumFunction(),
                builtIns.avgFunction(),
                builtIns.minFunction(),
                builtIns.maxFunction(),
                builtIns.percentileContFunction(),
                builtIns.percentileDiscFunction());
        return new QueryFunctionRegistry(functions);
    }
}
