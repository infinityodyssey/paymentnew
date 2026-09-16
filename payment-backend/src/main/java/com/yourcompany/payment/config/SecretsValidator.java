package com.yourcompany.payment.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Refuses startup on a missing, short or placeholder secret.
 *
 * The worst configuration defect in the legacy backend was a working fallback secret
 * in an @Value annotation: a missing environment variable produced a running service
 * signing payment tokens with a key that was in the git history.
 */
@Component
@RequiredArgsConstructor
public class SecretsValidator {

    private static final int MIN_SECRET_BYTES = 32;
    private static final Set<String> FORBIDDEN = Set.of(
            "changeme", "change-me", "placeholder", "secret_key", "example", "dummy",
            "test-secret", "tvs_credit_payment_token_secret");

    private final AppProperties props;

    @PostConstruct
    void validate() {
        check("app.security.token-hmac-secret", props.getSecurity().getTokenHmacSecret());
        check("app.security.otp-pepper", props.getSecurity().getOtpPepper());
        if (props.getSecurity().getActuatorPassword() == null
                || props.getSecurity().getActuatorPassword().isBlank()) {
            throw new IllegalStateException(
                    "app.security.actuator-password is not configured. The metrics endpoint "
                            + "must not be reachable without credentials. Refusing to start.");
        }
    }

    private void check(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured. Refusing to start.");
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    name + " must be at least " + MIN_SECRET_BYTES + " bytes; got " + bytes + ".");
        }
        String lower = value.toLowerCase();
        for (String bad : FORBIDDEN) {
            if (lower.contains(bad)) {
                throw new IllegalStateException(name + " looks like a placeholder. Refusing to start.");
            }
        }
    }
}
