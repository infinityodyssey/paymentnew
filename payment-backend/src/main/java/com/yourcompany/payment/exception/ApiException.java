package com.yourcompany.payment.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base for everything returned to the client.
 *
 * The message is what the borrower sees, so it must never carry internal state or
 * anything that lets an attacker distinguish "no such agreement" from "wrong details".
 */
@Getter
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public ApiException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }
}
