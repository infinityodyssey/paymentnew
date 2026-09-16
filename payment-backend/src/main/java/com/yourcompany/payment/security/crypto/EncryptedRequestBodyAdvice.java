package com.yourcompany.payment.security.crypto;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.dto.EncryptedEnvelope;
import com.yourcompany.payment.exception.CryptoException;
import com.yourcompany.payment.security.filter.SessionAuthFilter;
import com.yourcompany.payment.session.PaymentSession;
import com.yourcompany.payment.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Decrypts the request body.
 *
 * Two rules the legacy implementation broke:
 *
 * 1. When encryption is required, a body that is not a valid envelope is REJECTED. The
 *    old advice caught every exception and passed the raw bytes through "in case it was
 *    plaintext during integration testing", so the layer could be skipped by not using it.
 *
 * 2. Decryption failure is fatal for the request. A failed GCM tag check means tampering
 *    or a wrong key; neither is a reason to keep going.
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class EncryptedRequestBodyAdvice extends RequestBodyAdviceAdapter {

    private final SessionService sessionService;
    private final AesGcmCipherService cipherService;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return props.getSecurity().isRequireEncryptedPayloads()
                && !methodParameter.hasMethodAnnotation(SkipEncryption.class)
                && !methodParameter.getDeclaringClass().isAnnotationPresent(SkipEncryption.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {

        PaymentSession session = currentSession();
        if (session == null) {
            throw new CryptoException("Encrypted payload requires an established session");
        }

        byte[] raw = inputMessage.getBody().readAllBytes();
        if (raw.length == 0) {
            throw new CryptoException("Request payload is required");
        }

        EncryptedEnvelope envelope;
        try {
            envelope = objectMapper.readValue(raw, EncryptedEnvelope.class);
        } catch (Exception e) {
            throw new CryptoException("Request payload must be encrypted");
        }
        if (envelope.getCiphertextHex() == null || envelope.getIvHex() == null
                || envelope.getAuthTagHex() == null) {
            throw new CryptoException("Request payload must be encrypted");
        }

        byte[] key = sessionService.sessionKey(session);
        try {
            String plaintext = cipherService.decrypt(envelope, key, session.getSessionId());
            return new PlainInputMessage(inputMessage.getHeaders(),
                    new ByteArrayInputStream(plaintext.getBytes(StandardCharsets.UTF_8)));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private PaymentSession currentSession() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null
                : (PaymentSession) attrs.getRequest().getAttribute(SessionAuthFilter.SESSION_ATTR);
    }

    private record PlainInputMessage(HttpHeaders headers, InputStream body) implements HttpInputMessage {
        @Override
        public InputStream getBody() {
            return body;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
