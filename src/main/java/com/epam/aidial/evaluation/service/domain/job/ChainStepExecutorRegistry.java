package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Selects the {@link ChainStepExecutor} for a chain element's {@code type}, following the
 * {@code RequestBodySerializerRegistry} pattern. Duplicate registrations for one type are rejected at
 * startup rather than silently letting bean ordering decide which implementation wins.
 */
@Component
@LogExecution
public class ChainStepExecutorRegistry {

    private final Map<ChainRequestType, ChainStepExecutor> byType = new EnumMap<>(ChainRequestType.class);

    public ChainStepExecutorRegistry(List<ChainStepExecutor> executors) {
        for (ChainStepExecutor executor : executors) {
            final ChainStepExecutor previous = byType.put(executor.supportedType(), executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate ChainStepExecutor for type " + executor.supportedType()
                        + ": " + previous.getClass().getSimpleName() + " and "
                        + executor.getClass().getSimpleName());
            }
        }
    }

    /**
     * The executor for the given type. A null type is treated as {@link ChainRequestType#HTTP}, matching the
     * chain element DTO's {@code defaultImpl}.
     */
    public ChainStepExecutor require(ChainRequestType type) {
        final ChainRequestType effective = type != null ? type : ChainRequestType.HTTP;
        final ChainStepExecutor executor = byType.get(effective);
        if (executor == null) {
            throw new IllegalStateException("No ChainStepExecutor registered for chain request type " + effective);
        }
        return executor;
    }
}
