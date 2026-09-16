package com.yourcompany.payment.security.filter;

import com.yourcompany.payment.exception.ApiException;
import com.yourcompany.payment.security.PaymentTokenService;
import com.yourcompany.payment.security.SecurityHeaders;
import com.yourcompany.payment.session.PaymentSession;
import com.yourcompany.payment.session.SessionService;
import com.yourcompany.payment.session.SessionState;
import com.yourcompany.payment.util.Mask;
import com.yourcompany.payment.web.ApiErrorWriter;
import com.yourcompany.payment.web.ClientContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Resolves the session and enforces the payment token where required.
 *
 * The __Host- prefixed, HttpOnly, Secure, SameSite=Strict cookie is authoritative, and
 * the X-Session-Id header must be present and identical.
 *
 * That pairing is the CSRF control. Spring's CSRF token repository does not fit a
 * cross-origin SPA, so this is a double-submit: a cross-site attacker can make the
 * browser attach the cookie but cannot read it to populate the header, and cannot set
 * a custom header on a simple request without a preflight CORS will refuse. The legacy
 * backends disabled CSRF and put nothing in its place.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTR = "PAYMENT_SESSION";

    private final SessionService sessionService;
    private final PaymentTokenService paymentTokenService;
    private final ClientContext clientContext;
    private final ApiErrorWriter errorWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || "OPTIONS".equals(request.getMethod())
                || path.equals("/api/v1/auth/handshake");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            PaymentSession session = resolveSession(request);

            if (requiresPaymentToken(request.getRequestURI())) {
                sessionService.requireState(session, SessionState.OTP_VERIFIED, SessionState.TOKEN_CONSUMED);
                paymentTokenService.validate(session, extractBearer(request));
            }

            request.setAttribute(SESSION_ATTR, session);

            List<SimpleGrantedAuthority> authorities = switch (session.getState()) {
                case OTP_VERIFIED, TOKEN_CONSUMED -> List.of(new SimpleGrantedAuthority("ROLE_VERIFIED"));
                default -> List.of(new SimpleGrantedAuthority("ROLE_SESSION"));
            };
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(session.getSessionId(), null, authorities));

            chain.doFilter(request, response);

        } catch (ApiException e) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, e);
        }
    }

    private PaymentSession resolveSession(HttpServletRequest request) {
        String cookieValue = readCookie(request);
        String headerValue = request.getHeader(SecurityHeaders.SESSION_ID);

        if (cookieValue == null || headerValue == null) {
            throw sessionRequired();
        }
        if (!MessageDigest.isEqual(cookieValue.getBytes(StandardCharsets.UTF_8),
                headerValue.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Session cookie and header mismatch; possible cross-site request");
            throw sessionRequired();
        }

        PaymentSession session = sessionService.get(cookieValue);

        String fingerprint = clientContext.fingerprint(request);
        if (session.getClientFingerprint() != null && !MessageDigest.isEqual(
                session.getClientFingerprint().getBytes(StandardCharsets.UTF_8),
                fingerprint.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Client fingerprint changed for session {}", Mask.sessionTail(session.getSessionId()));
            sessionService.invalidate(session.getSessionId());
            throw sessionRequired();
        }
        return session;
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (SecurityHeaders.SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader(SecurityHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(SecurityHeaders.BEARER_PREFIX)) {
            return header.substring(SecurityHeaders.BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    /** Payment-phase paths. Listed now so the check exists before the handlers do. */
    private boolean requiresPaymentToken(String path) {
        return path.startsWith("/api/v1/payment/") || path.startsWith("/api/v1/transactions/");
    }

    private ApiException sessionRequired() {
        return new ApiException("SESSION_REQUIRED",
                "Your session has ended. Please reload the page.", HttpStatus.UNAUTHORIZED);
    }
}
