package com.epam.aidial.evaluation.web.pagination;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a repeatable {@code filter} query parameter to a controller method parameter of type
 * {@code List<String>} without Spring's {@code StringToCollectionConverter} comma-splitting.
 * Commas inside a single {@code ?filter=...} value are preserved verbatim; multiple conditions
 * must be submitted via repeated {@code ?filter=} parameters.
 *
 * <p>Enforces an upper bound on the number of repeated parameters via {@link #max()}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface FilterParam {

    /**
     * Query parameter name. Defaults to {@code "filter"}.
     */
    String name() default "filter";

    /**
     * Maximum number of repeated parameter occurrences per request.
     */
    int max() default ValidationConstants.MAX_LIST_FILTER_PARAMS;
}
