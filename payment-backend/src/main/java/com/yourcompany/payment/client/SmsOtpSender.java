package com.yourcompany.payment.client;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.exception.UpstreamException;
import com.yourcompany.payment.util.Mask;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;

/**
 * Sends the OTP through the SMS gateway.
 *
 * KNOWN RISK, CARRIED FROM THE EXISTING INTEGRATION. The gateway takes the message as
 * a GET query parameter, so the OTP and the mobile number appear in the request line.
 * Query strings are logged by default by nginx, Apache, most load balancers and most
 * WAFs, which means the code lands in access logs on hops we do not control. Every
 * other part of this service is built so the OTP never reaches a log; this one hop
 * defeats that, and it cannot be fixed from our side.
 *
 * The fix is a POST with the message in the body. Until the provider supports that,
 * this needs a named risk owner and written confirmation of their access-log retention.
 *
 * Two things that were wrong in the legacy implementation and are fixed here:
 *   - appURLsms.replace(" ", "%20") encoded spaces and nothing else, so &, =, + and #
 *     in the message passed through raw. UriComponentsBuilder encodes properly.
 *   - the gateway's response body was logged verbatim. It is now recorded to Oracle
 *     and summarised in the log.
 *
 * The message wording is the DLT-registered template and must not be edited without
 * re-registering it, or the operator will drop the message.
 */
@Slf4j
@Component
public class SmsOtpSender implements OtpSender {

    private static final String TYPE = "SMS_SEND";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final SmsTokenProvider tokenProvider;
    private final InteractionRecorder recorder;

    public SmsOtpSender(@Qualifier("smsRestClient") RestClient restClient,
                        ObjectMapper mapper,
                        AppProperties props,
                        SmsTokenProvider tokenProvider,
                        InteractionRecorder recorder) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.props = props;
        this.tokenProvider = tokenProvider;
        this.recorder = recorder;
    }

    @Override
    @CircuitBreaker(name = "sms", fallbackMethod = "unavailable")
    public void send(String mobileNumber, String otpCode, String agreementNumber) {
        AppProperties.Global g = props.getGlobal();
        String message = g.getSmsMessageTemplate().replace("{otp}", otpCode);

        URI uri = UriComponentsBuilder.fromUriString(g.getSmsSendUrl())
                .queryParam("Lang", g.getSmsLang())
                .queryParam("Phone", mobileNumber)
                .queryParam("Purpose", g.getSmsPurpose())
                .queryParam("Template", g.getSmsTemplate())
                .queryParam("EmpId", g.getSmsEmpId())
                .queryParam("Msg", message)
                .build()
                .encode()
                .toUri();

        // Recorded endpoint has the code and the number removed. The interaction row
        // must never become the place the OTP is retained.
        String recordedEndpoint = redact(uri.toString(), otpCode, mobileNumber);
        Instant requestedAt = Instant.now();
        String correlationId = InteractionRecorder.Interaction.correlation();

        try {
            var response = restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + tokenProvider.token())
                    .retrieve()
                    .toEntity(String.class);

            String raw = response.getBody();
            JsonNode root = mapper.readTree(raw == null ? "{}" : raw);
            JsonNode statusNode = root.get("status");
            String status = statusNode == null || statusNode.isNull() ? null : statusNode.asString();

            boolean accepted = status != null && "SUCCESS".equalsIgnoreCase(status);

            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, recordedEndpoint, "GET", agreementNumber, null,
                    redact(raw, otpCode, mobileNumber),
                    response.getStatusCode().value(), status, null,
                    accepted ? "SUCCESS" : "FAILURE", accepted ? null : "gateway did not accept",
                    requestedAt));

            if (!accepted) {
                log.error("SMS gateway rejected dispatch for {}", Mask.agreement(agreementNumber));
                throw UpstreamException.sms();
            }

            log.info("OTP dispatched to {} for {}", Mask.mobile(mobileNumber), Mask.agreement(agreementNumber));

        } catch (UpstreamException e) {
            throw e;
        } catch (Exception e) {
            log.error("SMS dispatch failed for {}: {}", Mask.agreement(agreementNumber),
                    e.getClass().getSimpleName());
            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, recordedEndpoint, "GET", agreementNumber, null, null, null, null, null,
                    "FAILURE", e.getClass().getSimpleName(), requestedAt));
            // A dispatch failure must reach the borrower. The legacy code swallowed it
            // and left them waiting for a message that was never sent.
            throw UpstreamException.sms();
        }
    }

    private String redact(String value, String otpCode, String mobileNumber) {
        if (value == null) {
            return null;
        }
        return value.replace(otpCode, "[OTP]").replace(mobileNumber, "[MOBILE]");
    }

    @SuppressWarnings("unused")
    private void unavailable(String mobileNumber, String otpCode, String agreementNumber, Throwable t) {
        if (t instanceof UpstreamException e) {
            throw e;
        }
        log.error("SMS circuit open: {}", t.getClass().getSimpleName());
        throw UpstreamException.sms();
    }
}
