package com.epam.aidial.evaluation.service.domain.exception;

import lombok.Getter;

@Getter
public class DatasetVisibilityRuleException extends RuntimeException {

    private final DatasetVisibilityErrorCode errorCode;

    public DatasetVisibilityRuleException(DatasetVisibilityErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
