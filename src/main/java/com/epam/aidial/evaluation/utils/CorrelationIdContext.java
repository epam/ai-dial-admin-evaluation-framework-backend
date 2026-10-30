package com.epam.aidial.evaluation.utils;

import com.epam.aidial.evaluation.constants.SecurityConstants;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/** Captures only the request correlation ID; native OTel span context is propagated separately. */
@UtilityClass
public class CorrelationIdContext {

    public static String currentId() {
        return MDC.get(SecurityConstants.CORRELATION_ID_MDC_KEY);
    }

    public static Runnable wrap(Runnable task) {
        final String captured = currentId();
        return () -> {
            final String previous = currentId();
            set(captured);
            try {
                task.run();
            } finally {
                set(previous);
            }
        };
    }

    private static void set(String value) {
        if (value == null) {
            MDC.remove(SecurityConstants.CORRELATION_ID_MDC_KEY);
        } else {
            MDC.put(SecurityConstants.CORRELATION_ID_MDC_KEY, value);
        }
    }
}
