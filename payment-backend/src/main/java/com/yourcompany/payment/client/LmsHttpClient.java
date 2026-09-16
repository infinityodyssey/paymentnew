package com.yourcompany.payment.client;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.dto.lms.LoanDetails;
import com.yourcompany.payment.exception.ApiException;
import com.yourcompany.payment.exception.UpstreamException;
import com.yourcompany.payment.util.Mask;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Gold-loan agreement lookup, ported from the legacy getGoldDueDetails.
 *
 * Contract as observed in the existing implementation:
 *   POST to the fetch URL, headers tkn (bearer from the LMS token endpoint) and fiid.
 *   Body {fiid, accountnumber, vehiclenumber}.
 *   Always HTTP 200. Success is status == "Success" in the body, data at
 *   response.success[]. Anything else is a failure.
 *
 * Only four fields are read here: customername, mobilenumber, accountnumber and
 * accountStatus. The dues arithmetic, address handling, charge breakdowns and the
 * loss-amount branch belong to the payment phase. Nothing is lost by deferring them:
 * the full payload is persisted by InteractionRecorder.
 */
@Slf4j
@Component
public class LmsHttpClient implements LmsClient {

    private static final String TYPE = "LMS_FETCH";
    private static final String TOKEN_NAME = "lms";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final LmsTokenProvider tokenProvider;
    private final InteractionRecorder recorder;

    public LmsHttpClient(@Qualifier("lmsRestClient") RestClient restClient,
                         ObjectMapper mapper,
                         AppProperties props,
                         LmsTokenProvider tokenProvider,
                         InteractionRecorder recorder) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.props = props;
        this.tokenProvider = tokenProvider;
        this.recorder = recorder;
    }

    @Override
    @CircuitBreaker(name = "lms", fallbackMethod = "unavailable")
    public Optional<LoanDetails> findByAgreementNumber(String agreementNumber) {
        String correlationId = InteractionRecorder.Interaction.correlation();
        String url = props.getGlobal().getLmsFetchUrl();
        String fiid = props.getGlobal().getFiid();

        String requestBody = mapper.writeValueAsString(java.util.Map.of(
                "fiid", fiid,
                "accountnumber", agreementNumber,
                // The legacy call supports registration-number lookup. This portal takes
                // an agreement number only, so the field is present but empty.
                "vehiclenumber", ""));

        Instant requestedAt = Instant.now();
        String rawResponse = null;
        Integer httpStatus = null;

        try {
            var response = restClient.post()
                    .uri(url)
                    .header("tkn", tokenProvider.token())
                    .header("fiid", fiid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);

            httpStatus = response.getStatusCode().value();
            rawResponse = response.getBody();

            JsonNode root = mapper.readTree(rawResponse == null ? "{}" : rawResponse);
            String status = text(root, "status");
            String code = text(root, "code");

            if (!"Success".equalsIgnoreCase(status)) {
                // Includes "agreement not found". Recorded as a business failure, not an
                // error, and surfaced to the caller as an empty Optional.
                recorder.record(correlationId, InteractionRecorder.Interaction.of(
                        TYPE, url, "POST", agreementNumber, requestBody, rawResponse,
                        httpStatus, status, code, "NOT_FOUND", null, requestedAt));
                return Optional.empty();
            }

            JsonNode successArray = root.path("response").path("success");
            if (!successArray.isArray() || successArray.isEmpty()) {
                recorder.record(correlationId, InteractionRecorder.Interaction.of(
                        TYPE, url, "POST", agreementNumber, requestBody, rawResponse,
                        httpStatus, status, code, "NOT_FOUND", "empty success array", requestedAt));
                return Optional.empty();
            }

            LoanDetails details = extract(successArray, agreementNumber);

            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, url, "POST", details.getAgreementNumber(), requestBody, rawResponse,
                    httpStatus, status, code, "SUCCESS", null, requestedAt));

            return Optional.of(details);

        } catch (ApiException e) {
            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, url, "POST", agreementNumber, requestBody, rawResponse,
                    httpStatus, null, null, "FAILURE", e.getErrorCode(), requestedAt));
            throw e;
        } catch (Exception e) {
            log.error("LMS lookup failed for {}: {}", Mask.agreement(agreementNumber),
                    e.getClass().getSimpleName());
            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, url, "POST", agreementNumber, requestBody, rawResponse,
                    httpStatus, null, null, "FAILURE", e.getClass().getSimpleName(), requestedAt));
            throw UpstreamException.lms();
        }
    }

    /**
     * Pulls the authentication fields out of success[].
     *
     * The array can hold several accounts for one customer. For OTP dispatch exactly
     * one mobile number is needed, so distinct numbers are collected across all
     * entries. If more than one distinct number appears, the lookup is rejected rather
     * than silently using the first: sending to success[0] when the numbers differ
     * would deliver one borrower's code to a different person's phone.
     *
     * If your LMS guarantees one number per customer this branch never fires. If it can
     * fire, you find out here and in the audit table rather than in a complaint.
     */
    private LoanDetails extract(JsonNode successArray, String requestedAgreement) {
        Set<String> mobiles = new LinkedHashSet<>();
        for (JsonNode entry : successArray) {
            String mobile = text(entry, "mobilenumber");
            if (mobile != null && !mobile.isBlank()) {
                mobiles.add(mobile.trim());
            }
        }

        if (mobiles.isEmpty()) {
            log.warn("LMS returned no mobile number for {}", Mask.agreement(requestedAgreement));
            throw new ApiException("AGREEMENT_NO_CONTACT",
                    "We do not have a mobile number on record for this account. "
                            + "Please contact customer support.", HttpStatus.CONFLICT);
        }
        if (mobiles.size() > 1) {
            log.warn("LMS returned {} distinct mobile numbers for {}; refusing to guess",
                    mobiles.size(), Mask.agreement(requestedAgreement));
            throw new ApiException("AGREEMENT_AMBIGUOUS",
                    "We could not verify those details. Please contact customer support.",
                    HttpStatus.CONFLICT);
        }

        JsonNode first = successArray.get(0);
        String mobile = mobiles.iterator().next();
        String accountNumber = text(first, "accountnumber");

        return LoanDetails.builder()
                .agreementNumber(accountNumber == null || accountNumber.isBlank()
                        ? requestedAgreement : accountNumber)
                .customerName(text(first, "customername"))
                .mobileNumber(mobile)
                .maskedMobile(Mask.mobile(mobile))
                .accountStatus(text(first, "accountStatus"))
                .agreementCount(successArray.size())
                .build();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    @SuppressWarnings("unused")
    private Optional<LoanDetails> unavailable(String agreementNumber, Throwable t) {
        if (t instanceof ApiException e) {
            throw e;
        }
        log.error("LMS circuit open or call failed: {}", t.getClass().getSimpleName());
        throw UpstreamException.lms();
    }
}
