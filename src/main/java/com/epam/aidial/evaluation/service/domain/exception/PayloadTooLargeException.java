package com.epam.aidial.evaluation.service.domain.exception;

import java.io.IOException;

public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
