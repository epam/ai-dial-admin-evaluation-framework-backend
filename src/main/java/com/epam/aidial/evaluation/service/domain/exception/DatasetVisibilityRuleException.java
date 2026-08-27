package com.epam.aidial.evaluation.service.domain.exception;

import lombok.Getter;

@Getter
public class DatasetVisibilityRuleException extends RuntimeException {

    /**
     * User-facing text for {@link DatasetVisibilityErrorCode#PRIVATE_DATASET_ALREADY_BOUND}. Shared
     * with the web layer, which reports the same rule when the DB trigger
     * {@code tg_test_suites_private_binding_guard} wins a concurrent-binding race.
     */
    public static final String PRIVATE_DATASET_ALREADY_BOUND_MESSAGE =
            "The selected dataset is private and already belongs to another test suite. "
                    + "A private dataset can be bound to a single test suite only.";

    private final DatasetVisibilityErrorCode errorCode;

    public DatasetVisibilityRuleException(DatasetVisibilityErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
