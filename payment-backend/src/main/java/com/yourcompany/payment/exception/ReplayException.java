package com.yourcompany.payment.exception;

import org.springframework.http.HttpStatus;

public class ReplayException extends ApiException {
    public ReplayException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.FORBIDDEN);
    }
}
