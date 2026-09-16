package com.yourcompany.payment.security;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.exception.ApiException;
import com.yourcompany.payment.session.PaymentSession;
import com.yourcompany.payment.util.Mask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and validates the post-OTP payment authorisation token.
 *
 * Format: PAY1.{tokenId}.{issuedAtEpochMillis}.{base64url(HMAC-SHA256)}
 *
 * The signature covers a length-prefixed encoding of version, session id, agreement
 * number, token id and issue time, so no rearrangement of field values produces a
 * different but equally valid message. Plain ":"-separated concatenation is ambiguous
 * the moment any field can contain the separator.
 *
 * The token proves the holder completed OTP verification in this session, for this
 * loan, at a known time. It is not a session id and carries no authority alone: the
 * session must also resolve and match.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTokenService {

    private static final String VERSION = "PAY1";
    private static final String HMAC_ALG = "HmacSHA256";

    private final AppProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    public IssuedToken issue(PaymentSession session) {
        byte[] raw = new byte[16];
        secureRandom.nextBytes(raw);
        String tokenId = HexFormat.of().formatHex(raw);
        long issuedAt = Instant.now().toEpochMilli();

        String signature = sign(session.getSessionId(), session.getAgreementNumber(), tokenId, issuedAt);
        String token = String.join(".", VERSION, tokenId, Long.toString(issuedAt), signature);

        return new IssuedToken(token, tokenId, issuedAt, props.getSecurity().getPaymentTokenTtlSeconds());
    }

    /**
     * Cheap structural checks, then expiry, then the HMAC, then the session binding.
     * Every failure returns the same error code so response differences do not reveal
     * which check failed.
     */
    public void validate(PaymentSession session, String presented) {
        if (presented == null || presented.isBlank()) {
            throw invalid();
        }

        String[] parts = presented.split("\\.");
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw invalid();
        }

        String tokenId = parts[1];
        if (!tokenId.matches("[0-9a-f]{32}")) {
            throw invalid();
        }

        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw invalid();
        }

        long ageMs = Instant.now().toEpochMilli() - issuedAt;
        long skewMs = props.getSecurity().getClockSkewToleranceSeconds() * 1000L;
        long ttlMs = props.getSecurity().getPaymentTokenTtlSeconds() * 1000L;
        if (ageMs > ttlMs || ageMs < -skewMs) {
            throw invalid();
        }

        String expected = sign(session.getSessionId(), session.getAgreementNumber(), tokenId, issuedAt);
        boolean signatureOk = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8));

        // Constant-time on the id too, so a superseded-but-well-formed token is not
        // distinguishable by timing from a forged one.
        boolean idOk = session.getTokenId() != null && MessageDigest.isEqual(
                session.getTokenId().getBytes(StandardCharsets.UTF_8),
                tokenId.getBytes(StandardCharsets.UTF_8));

        if (!signatureOk || !idOk) {
            log.warn("Payment token rejected for session {}", Mask.sessionTail(session.getSessionId()));
            throw invalid();
        }
    }

    private String sign(String sessionId, String agreementNumber, String tokenId, long issuedAt) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(
                    props.getSecurity().getTokenHmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            mac.update(lengthPrefixed(VERSION));
            mac.update(lengthPrefixed(sessionId));
            mac.update(lengthPrefixed(agreementNumber == null ? "" : agreementNumber));
            mac.update(lengthPrefixed(tokenId));
            mac.update(lengthPrefixed(Long.toString(issuedAt)));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (Exception e) {
            throw new ApiException("INTERNAL_ERROR", "Unable to process request",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** 4-byte big-endian length then UTF-8 bytes: unambiguous concatenation. */
    private byte[] lengthPrefixed(String value) {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + body.length];
        out[0] = (byte) (body.length >>> 24);
        out[1] = (byte) (body.length >>> 16);
        out[2] = (byte) (body.length >>> 8);
        out[3] = (byte) body.length;
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    private ApiException invalid() {
        return new ApiException("PAYMENT_TOKEN_INVALID",
                "Your authorisation has expired. Please verify the code again.", HttpStatus.UNAUTHORIZED);
    }

    public record IssuedToken(String token, String tokenId, long issuedAt, long expiresInSeconds) {}
}
