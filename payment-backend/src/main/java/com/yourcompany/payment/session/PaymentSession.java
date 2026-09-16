package com.yourcompany.payment.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Volatile session state. Redis is the only store.
 *
 * Deliberately absent, compared with the legacy design: the server ECDH private key
 * (discarded immediately after derivation) and the OTP in any form (its own key, its
 * own TTL). There is no JPA mapping either, so no key material is ever written to
 * Oracle and no database backup can decrypt captured traffic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSession {

    private String sessionId;
    private SessionState state;

    /**
     * Optimistic-concurrency version, incremented on every successful save.
     *
     * Not exposed to the client and never used for anything but the compare-and-swap in
     * SessionService. A session loaded at version n can only be written back if the
     * stored version is still n, which is what stops one request silently overwriting
     * a state transition made by another.
     */
    private long version;

    private String agreementNumber;
    private String customerName;
    private String maskedMobile;

    /** Derived AES-256 key, hex. Present only in Redis, only for the session TTL. */
    private String sessionKeyHex;

    private String clientFingerprint;

    private String tokenId;
    private Long tokenIssuedAt;
    private boolean tokenConsumed;
    private String boundTransactionId;

    private Instant createdAt;
    private Instant expiresAt;

    public boolean isExpired() {
        return expiresAt == null || Instant.now().isAfter(expiresAt);
    }

    public boolean isInState(SessionState... allowed) {
        for (SessionState s : allowed) {
            if (s == state) {
                return true;
            }
        }
        return false;
    }
}
