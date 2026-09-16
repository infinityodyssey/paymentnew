package com.yourcompany.payment;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.exception.ApiException;
import com.yourcompany.payment.security.PaymentTokenService;
import com.yourcompany.payment.session.PaymentSession;
import com.yourcompany.payment.session.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * These encode the properties the token is supposed to have. If one starts failing, the
 * token has stopped being an authorisation and become a decorative string.
 */
class PaymentTokenServiceTest {

    private PaymentTokenService service;
    private AppProperties props;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getSecurity().setTokenHmacSecret("unit-test-secret-value-long-enough-32-bytes");
        props.getSecurity().setOtpPepper("unit-test-pepper-value-long-enough-32-bytes");
        props.getSecurity().setPaymentTokenTtlSeconds(900);
        props.getSecurity().setClockSkewToleranceSeconds(5);
        service = new PaymentTokenService(props);
    }

    private PaymentSession session(String id, String agreement) {
        return PaymentSession.builder()
                .sessionId(id)
                .agreementNumber(agreement)
                .state(SessionState.OTP_VERIFIED)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    @Test
    void issuedTokenValidatesAgainstItsOwnSession() {
        PaymentSession s = session("SES_a", "GL0001234567");
        var issued = service.issue(s);
        s.setTokenId(issued.tokenId());
        assertDoesNotThrow(() -> service.validate(s, issued.token()));
    }

    @Test
    void tokenFromAnotherSessionIsRejected() {
        PaymentSession victim = session("SES_victim", "GL0001234567");
        var issued = service.issue(victim);

        PaymentSession attacker = session("SES_attacker", "GL0001234567");
        attacker.setTokenId(issued.tokenId());

        assertThrows(ApiException.class, () -> service.validate(attacker, issued.token()));
    }

    @Test
    void tokenBoundToAnotherAgreementIsRejected() {
        PaymentSession s = session("SES_a", "GL0001234567");
        var issued = service.issue(s);
        s.setTokenId(issued.tokenId());

        s.setAgreementNumber("GL9999999999");
        assertThrows(ApiException.class, () -> service.validate(s, issued.token()));
    }

    @Test
    void tamperedSignatureIsRejected() {
        PaymentSession s = session("SES_a", "GL0001234567");
        var issued = service.issue(s);
        s.setTokenId(issued.tokenId());

        String[] parts = issued.token().split("\\.");
        String flipped = (parts[3].charAt(0) == 'A' ? "B" : "A") + parts[3].substring(1);
        assertThrows(ApiException.class,
                () -> service.validate(s, String.join(".", parts[0], parts[1], parts[2], flipped)));
    }

    @Test
    void supersededTokenIsRejectedOnceANewOneIsIssued() {
        PaymentSession s = session("SES_a", "GL0001234567");
        var first = service.issue(s);
        var second = service.issue(s);
        s.setTokenId(second.tokenId());

        assertThrows(ApiException.class, () -> service.validate(s, first.token()));
        assertDoesNotThrow(() -> service.validate(s, second.token()));
    }

    @Test
    void expiredTokenIsRejected() {
        props.getSecurity().setPaymentTokenTtlSeconds(60);
        PaymentSession s = session("SES_a", "GL0001234567");
        var issued = service.issue(s);
        s.setTokenId(issued.tokenId());

        String[] parts = issued.token().split("\\.");
        long backdated = Long.parseLong(parts[2]) - 120_000L;
        assertThrows(ApiException.class,
                () -> service.validate(s, String.join(".", parts[0], parts[1],
                        Long.toString(backdated), parts[3])));
    }

    @Test
    void missingOrMalformedTokensAreRejected() {
        PaymentSession s = session("SES_a", "GL0001234567");
        assertThrows(ApiException.class, () -> service.validate(s, null));
        assertThrows(ApiException.class, () -> service.validate(s, ""));
        assertThrows(ApiException.class, () -> service.validate(s, "PAY1.abc.123"));
        assertThrows(ApiException.class, () -> service.validate(s, "notatoken"));
    }

    @Test
    void tokenDoesNotLeakTheSecret() {
        PaymentSession s = session("SES_a", "GL0001234567");
        assertFalse(service.issue(s).token().contains(props.getSecurity().getTokenHmacSecret()));
    }
}
