package com.yourcompany.payment.session;

/**
 * Explicit state machine. Every endpoint declares the states it accepts, so a client
 * cannot skip a step by calling endpoints out of order.
 *
 * HANDSHAKE_COMPLETE -> OTP_SENT -> OTP_VERIFIED -> TOKEN_CONSUMED (payment phase)
 */
public enum SessionState {
    HANDSHAKE_COMPLETE,
    OTP_SENT,
    OTP_VERIFIED,
    TOKEN_CONSUMED,
    LOCKED
}
