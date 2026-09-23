package com.epam.aidial.evaluation.mcp.support;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Model-agnostic guard for the forward-compatible fields MCP models expose but this version does
 * not support (multi-request suites, multi-turn test cases, {@code MCP_TOOL} suites). Suite/test
 * case tool groups call it with their own fields before performing any change (D-F6).
 */
@Component
@LogExecution
public class UnsupportedFeatureGuard {

    /**
     * Rejects the call when {@code value} is non-null.
     */
    public void rejectIfPresent(String feature, @Nullable Object value) {
        if (value != null) {
            throw new UnsupportedFeatureException(feature);
        }
    }

    /**
     * Rejects the call when {@code value} is non-null and non-empty.
     */
    public void rejectIfNotEmpty(String feature, @Nullable Collection<?> value) {
        if (value != null && !value.isEmpty()) {
            throw new UnsupportedFeatureException(feature);
        }
    }
}
