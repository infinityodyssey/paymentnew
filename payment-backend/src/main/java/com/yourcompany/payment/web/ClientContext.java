package com.yourcompany.payment.web;

import com.yourcompany.payment.util.Hashing;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Client IP and a coarse fingerprint.
 *
 * The IP is used for the audit trail only. Rate limiting by IP is handled at the API
 * gateway, which is the right place for it; note that the gateway cannot enforce the
 * per-agreement OTP send budget or the attempt cap, because those key on a value
 * inside an encrypted body. Those live in OtpService and must stay there.
 *
 * X-Forwarded-For is trusted because server.forward-headers-strategy=native is set and
 * the service sits behind a gateway that overwrites the header. If that stops being
 * true, this value becomes attacker-controlled.
 */
@Component
public class ClientContext {

    public String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    public String hashIp(HttpServletRequest request) {
        return Hashing.keyHash(clientIp(request));
    }

    /**
     * Deliberately weak: User-Agent and Accept-Language, no IP, because mobile users
     * change IP mid-journey. It breaks trivial cookie replay from another tool; it will
     * not stop an attacker who already holds the cookie.
     */
    public String fingerprint(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        String lang = request.getHeader("Accept-Language");
        return Hashing.shortHash((ua == null ? "" : ua) + "|" + (lang == null ? "" : lang));
    }
}
