package com.yourcompany.payment.controller;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.dto.ApiResponse;
import com.yourcompany.payment.dto.auth.AuthDtos;
import com.yourcompany.payment.security.SecurityHeaders;
import com.yourcompany.payment.security.crypto.SkipEncryption;
import com.yourcompany.payment.security.filter.SessionAuthFilter;
import com.yourcompany.payment.service.AuthService;
import com.yourcompany.payment.session.PaymentSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The four authentication endpoints. No business logic: the controller adapts HTTP to
 * AuthService and owns the session cookie, nothing else.
 *
 * Note the absence of any rate-limiting call. Per-IP and per-route limits belong to the
 * API gateway. The limits that cannot live there - OTP attempts and the per-agreement
 * send budget - are enforced in OtpService, because they key on a value inside an
 * encrypted body that the gateway cannot read.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AppProperties props;

    /** The only unauthenticated endpoint and the only one exempt from encryption. */
    @PostMapping("/handshake")
    @SkipEncryption
    public ResponseEntity<ApiResponse<AuthDtos.HandshakeResponse>> handshake(
            @Valid @RequestBody AuthDtos.HandshakeRequest request,
            HttpServletRequest http,
            HttpServletResponse httpResponse) {

        AuthDtos.HandshakeResponse response = authService.handshake(request, http);

        // The __Host- prefix is accepted by browsers only with Secure set, Path "/" and
        // no Domain attribute, which host-locks the cookie: a compromised or
        // attacker-controlled sibling subdomain cannot overwrite it.
        ResponseCookie cookie = ResponseCookie.from(SecurityHeaders.SESSION_COOKIE, response.getSessionId())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(props.getSecurity().getSessionTtlSeconds())
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/verify-agreement")
    public ResponseEntity<ApiResponse<AuthDtos.VerifyAgreementResponse>> verifyAgreement(
            @Valid @RequestBody AuthDtos.VerifyAgreementRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(
                authService.verifyAgreement(currentSession(http), request, http)));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<ApiResponse<AuthDtos.VerifyAgreementResponse>> resendOtp(HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(authService.resendOtp(currentSession(http), http)));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<AuthDtos.VerifyOtpResponse>> verifyOtp(
            @Valid @RequestBody AuthDtos.VerifyOtpRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(
                authService.verifyOtp(currentSession(http), request, http)));
    }

    private PaymentSession currentSession(HttpServletRequest http) {
        return (PaymentSession) http.getAttribute(SessionAuthFilter.SESSION_ATTR);
    }
}
