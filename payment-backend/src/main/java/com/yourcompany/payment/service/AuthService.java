package com.yourcompany.payment.service;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.dto.auth.AuthDtos;
import com.yourcompany.payment.dto.lms.LoanDetails;
import com.yourcompany.payment.exception.ApiException;
import com.yourcompany.payment.security.PaymentTokenService;
import com.yourcompany.payment.security.crypto.EcdhKeyService;
import com.yourcompany.payment.session.PaymentSession;
import com.yourcompany.payment.session.SessionService;
import com.yourcompany.payment.session.SessionState;
import com.yourcompany.payment.util.AuditEventType;
import com.yourcompany.payment.util.Mask;
import com.yourcompany.payment.web.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.util.Arrays;
import java.util.Optional;

/**
 * Sequences the four authentication steps and owns the state transitions.
 * All security decisions live in the collaborators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final EcdhKeyService ecdhKeyService;
    private final SessionService sessionService;
    private final LmsService lmsService;
    private final OtpService otpService;
    private final PaymentTokenService paymentTokenService;
    private final AuditService auditService;
    private final ClientContext clientContext;
    private final AppProperties props;

    /** Step 0: ephemeral key agreement. */
    public AuthDtos.HandshakeResponse handshake(AuthDtos.HandshakeRequest request, HttpServletRequest http) {
        var clientPublicKey = ecdhKeyService.parseAndValidatePublicKey(request.getClientPublicKeyHex());
        KeyPair serverKeyPair = ecdhKeyService.generateEphemeralKeyPair();

        // Minted first because the id is the HKDF salt: the derived key is unique to
        // this session even if either side's RNG repeats.
        String sessionId = sessionService.mintSessionId();
        byte[] sessionKey = ecdhKeyService.deriveSessionKey(
                serverKeyPair.getPrivate(), clientPublicKey, sessionId);

        PaymentSession session;
        try {
            session = sessionService.create(sessionId, sessionKey, clientContext.fingerprint(http));
        } finally {
            // The server private key is now unreferenced and the key buffer wiped.
            // Neither is written to Redis or Oracle, so a database compromise cannot
            // decrypt traffic captured today.
            Arrays.fill(sessionKey, (byte) 0);
        }

        auditService.record(AuditEventType.HANDSHAKE, AuditEventType.SUCCESS, null,
                session.getSessionId(), null, http);

        return AuthDtos.HandshakeResponse.builder()
                .sessionId(session.getSessionId())
                .serverPublicKeyHex(ecdhKeyService.exportPublicKeyHex(serverKeyPair.getPublic()))
                .expiresInSeconds(props.getSecurity().getSessionTtlSeconds())
                .build();
    }

    /** Step 1: agreement lookup and OTP dispatch. */
    public AuthDtos.VerifyAgreementResponse verifyAgreement(PaymentSession session,
                                                            AuthDtos.VerifyAgreementRequest request,
                                                            HttpServletRequest http) {
        sessionService.requireState(session, SessionState.HANDSHAKE_COMPLETE, SessionState.OTP_SENT);

        String agreementNumber = request.getAgreementNumber().toUpperCase();
        Optional<LoanDetails> found = lmsService.lookup(agreementNumber);

        if (found.isEmpty()) {
            auditService.record(AuditEventType.AGREEMENT_LOOKUP, AuditEventType.FAILURE,
                    "AGREEMENT_NOT_FOUND", session.getSessionId(), agreementNumber, http);
            log.info("Agreement lookup miss for {}", Mask.agreement(agreementNumber));
            // The LMS returns the same shape for "no such agreement" and "lookup
            // failed", so this response cannot confirm whether the number is real.
            throw new ApiException("AGREEMENT_LOOKUP_FAILED",
                    "We could not verify those details. Please check and try again.",
                    HttpStatus.UNAUTHORIZED);
        }

        LoanDetails details = found.get();

        // Rebinding an established session to a different loan is not a legitimate
        // flow; it is how one verified session would be reused across accounts.
        if (session.getAgreementNumber() != null
                && !session.getAgreementNumber().equals(details.getAgreementNumber())) {
            sessionService.invalidate(session.getSessionId());
            throw new ApiException("SESSION_REBIND_REJECTED",
                    "Please reload the page and start again.", HttpStatus.CONFLICT);
        }

        otpService.issue(session.getSessionId(), details.getAgreementNumber(), details.getMobileNumber());

        session.setAgreementNumber(details.getAgreementNumber());
        session.setCustomerName(details.getCustomerName());
        session.setMaskedMobile(details.getMaskedMobile());
        session.setState(SessionState.OTP_SENT);
        sessionService.save(session);

        auditService.record(AuditEventType.OTP_ISSUED, AuditEventType.SUCCESS, null,
                session.getSessionId(), details.getAgreementNumber(), http);

        return response(details.getMaskedMobile());
    }

    /** Step 1b: resend, under the same cooldown and budget as the first send. */
    public AuthDtos.VerifyAgreementResponse resendOtp(PaymentSession session, HttpServletRequest http) {
        sessionService.requireState(session, SessionState.OTP_SENT);

        Optional<LoanDetails> found = lmsService.lookup(session.getAgreementNumber());
        if (found.isEmpty()) {
            throw new ApiException("AGREEMENT_LOOKUP_FAILED",
                    "We could not verify those details. Please start again.", HttpStatus.UNAUTHORIZED);
        }
        LoanDetails details = found.get();

        otpService.issue(session.getSessionId(), details.getAgreementNumber(), details.getMobileNumber());

        auditService.record(AuditEventType.OTP_RESEND, AuditEventType.SUCCESS, null,
                session.getSessionId(), session.getAgreementNumber(), http);

        return response(details.getMaskedMobile());
    }

    /** Step 2: verification and payment-token issuance. */
    public AuthDtos.VerifyOtpResponse verifyOtp(PaymentSession session,
                                                AuthDtos.VerifyOtpRequest request,
                                                HttpServletRequest http) {
        sessionService.requireState(session, SessionState.OTP_SENT);

        try {
            otpService.verify(session.getSessionId(), request.getOtp());
        } catch (ApiException e) {
            auditService.record(AuditEventType.OTP_VERIFY, AuditEventType.FAILURE, e.getErrorCode(),
                    session.getSessionId(), session.getAgreementNumber(), http);
            throw e;
        }

        // Second line of defence. The OTP DEL already elects a single winner, and the
        // OTP_SENT state check blocks a second verification afterwards, so in normal
        // operation this never fires. It is here because token issuance is the point
        // where two winners would be expensive, and because the payment phase needs the
        // same primitive for token consumption and initiation - proving it out on the
        // cheap case first.
        if (!sessionService.claimOnce(session.getSessionId(), "token-issue")) {
            auditService.record(AuditEventType.TOKEN_ISSUED, AuditEventType.FAILURE,
                    "TOKEN_ALREADY_ISSUED", session.getSessionId(), session.getAgreementNumber(), http);
            throw new ApiException("TOKEN_ALREADY_ISSUED",
                    "This code has already been verified. Please continue in the original tab.",
                    HttpStatus.CONFLICT);
        }

        PaymentTokenService.IssuedToken issued;
        try {
            issued = paymentTokenService.issue(session);
        } catch (RuntimeException e) {
            sessionService.releaseClaim(session.getSessionId(), "token-issue");
            throw e;
        }

        session.setState(SessionState.OTP_VERIFIED);
        session.setTokenId(issued.tokenId());
        session.setTokenIssuedAt(issued.issuedAt());
        session.setTokenConsumed(false);
        session.setBoundTransactionId(null);

        try {
            sessionService.save(session);
        } catch (RuntimeException e) {
            // The session moved under us, so the token that was just signed is bound to
            // a version that was never stored. Release the claim so a clean retry is
            // possible and let the conflict surface: silently returning a token whose
            // tokenId is not the one on the stored session would hand the borrower a
            // credential that fails on first use.
            sessionService.releaseClaim(session.getSessionId(), "token-issue");
            throw e;
        }

        auditService.record(AuditEventType.OTP_VERIFY, AuditEventType.SUCCESS, null,
                session.getSessionId(), session.getAgreementNumber(), http);
        auditService.record(AuditEventType.TOKEN_ISSUED, AuditEventType.SUCCESS, null,
                session.getSessionId(), session.getAgreementNumber(), http);

        // The token goes to the client inside the encrypted envelope and is never logged.
        return AuthDtos.VerifyOtpResponse.builder()
                .customerName(session.getCustomerName())
                .paymentAuthToken(issued.token())
                .tokenType("Bearer")
                .expiresInSeconds(issued.expiresInSeconds())
                .build();
    }

    private AuthDtos.VerifyAgreementResponse response(String maskedMobile) {
        return AuthDtos.VerifyAgreementResponse.builder()
                .maskedMobile(maskedMobile)
                .otpExpiresInSeconds(props.getOtp().getTtlSeconds())
                .resendAvailableInSeconds(props.getOtp().getResendCooldownSeconds())
                .build();
    }
}
