package com.yourcompany.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Redis is unreachable.
 *
 * The legacy backends fell back to a node-local map here, which silently disabled
 * nonce de-duplication, OTP attempt counting and single-use token enforcement across
 * the cluster. This service fails closed: a payment journey that cannot be secured is
 * refused, not served insecurely.
 */
public class StateStoreUnavailableException extends ApiException {
    public StateStoreUnavailableException() {
        super("SERVICE_UNAVAILABLE",
              "The service is temporarily unavailable. Please try again in a few minutes.",
              HttpStatus.SERVICE_UNAVAILABLE);
    }
}
