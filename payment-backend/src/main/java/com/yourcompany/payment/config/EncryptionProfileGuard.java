package com.yourcompany.payment.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Ties the plaintext-payload flag and the LMS payload logger to the dev and test profiles.
 *
 * Both exist for developer convenience and both are how an encryption bypass or a PII
 * leak reaches production: someone flips a flag to debug an integration, the change
 * survives a merge, and nothing visibly breaks. Binding them to the profile makes
 * disabling them in a deployed environment a reviewable act rather than a silent one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EncryptionProfileGuard {

    private static final List<String> PERMITTED = List.of("dev", "test");

    private final AppProperties props;
    private final Environment environment;

    @PostConstruct
    void validate() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        boolean permitted = !active.isEmpty() && PERMITTED.containsAll(active);

        if (!props.getSecurity().isRequireEncryptedPayloads() && !permitted) {
            throw new IllegalStateException(
                    "app.security.require-encrypted-payloads is false under profiles " + active
                            + ". Permitted only under " + PERMITTED + ". Refusing to start.");
        }
        if (props.getLms().isLogPayloads() && !permitted) {
            throw new IllegalStateException(
                    "app.lms.log-payloads is true under profiles " + active
                            + ". LMS payloads carry borrower name, mobile number and dues. "
                            + "Permitted only under " + PERMITTED + ". Refusing to start.");
        }

        if (!props.getSecurity().isRequireEncryptedPayloads()) {
            log.warn("PAYLOAD ENCRYPTION DISABLED under profiles {}. Bodies are plaintext.", active);
        }
        if (props.getLms().isLogPayloads()) {
            log.warn("LMS PAYLOAD LOGGING ENABLED under profiles {}. Borrower data will reach the log pipeline.", active);
        }
    }
}
