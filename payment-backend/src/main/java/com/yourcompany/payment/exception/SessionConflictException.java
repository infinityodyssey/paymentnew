package com.yourcompany.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * A concurrent request already advanced this session.
 *
 * Deliberately not retried. In a payment flow "someone else moved this session while I
 * was working" is a reason to stop: retrying would re-apply a transition against state
 * that is no longer what the caller read, which is how a session ends up bound to two
 * agreements or issuing two live tokens.
 */
public class SessionConflictException extends ApiException {
    public SessionConflictException() {
        super("SESSION_CONFLICT",
              "Another request is already in progress. Please reload the page and try again.",
              HttpStatus.CONFLICT);
    }
}
