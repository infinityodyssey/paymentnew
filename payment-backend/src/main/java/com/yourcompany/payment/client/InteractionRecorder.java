package com.yourcompany.payment.client;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.entity.LmsInteraction;
import com.yourcompany.payment.messaging.InteractionEventProducer;
import com.yourcompany.payment.messaging.event.LmsInteractionEvent;
import com.yourcompany.payment.repo.LmsInteractionRepository;
import com.yourcompany.payment.util.Hashing;
import com.yourcompany.payment.util.Mask;
import com.yourcompany.payment.web.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Records every upstream interaction in three places, each with a different purpose.
 *
 *  Oracle  full request and response, for reconciliation and dispute handling. This is
 *          the durable record and the only one that holds payloads.
 *  Kafka   metadata only, for SIEM and operational dashboards.
 *  Logs    metadata only by default. Payloads go to the dedicated LMS_PAYLOAD logger,
 *          which EncryptionProfileGuard refuses to enable outside dev and test, and
 *          which your platform team can drop before Loki without a code change.
 *
 * The split matters because an LMS response carries the borrower's name, mobile number
 * and outstanding dues. A log aggregator's retention and access model are set by
 * whoever runs the platform; an Oracle grant is set by you.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionRecorder {

    private static final Logger PAYLOAD_LOG = LoggerFactory.getLogger("LMS_PAYLOAD");

    private final LmsInteractionRepository repository;
    private final InteractionEventProducer producer;
    private final AppProperties props;

    /**
     * Runs off the request thread. REQUIRES_NEW so a failure to record never rolls back
     * or fails the borrower's journey; the failure is logged loudly instead. Alert on
     * INTERACTION RECORD FAILED.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String correlationId, Interaction interaction) {
        try {
            repository.save(LmsInteraction.builder()
                    .correlationId(correlationId)
                    .interactionType(interaction.type())
                    .endpoint(truncate(interaction.endpoint(), 512))
                    .httpMethod(interaction.method())
                    .agreementNumber(interaction.agreementNumber())
                    .requestBody(interaction.requestBody())
                    .responseBody(interaction.responseBody())
                    .httpStatus(interaction.httpStatus())
                    .upstreamStatus(interaction.upstreamStatus())
                    .upstreamCode(interaction.upstreamCode())
                    .outcome(interaction.outcome())
                    .errorDetail(truncate(interaction.errorDetail(), 512))
                    .durationMs(interaction.durationMs())
                    .requestedAt(interaction.requestedAt())
                    .respondedAt(interaction.respondedAt())
                    .build());
        } catch (Exception e) {
            log.error("INTERACTION RECORD FAILED type={} correlationId={} cause={}",
                    interaction.type(), correlationId, e.getClass().getSimpleName());
        }

        producer.publish(new LmsInteractionEvent(
                correlationId,
                interaction.type(),
                interaction.endpoint(),
                interaction.method(),
                interaction.agreementNumber() == null ? null : Hashing.shortHash(interaction.agreementNumber()),
                interaction.httpStatus(),
                interaction.upstreamStatus(),
                interaction.upstreamCode(),
                interaction.outcome(),
                interaction.durationMs(),
                interaction.requestedAt()));

        log.info("upstream call type={} endpoint={} status={} outcome={} durationMs={} agreement={}",
                interaction.type(), interaction.endpoint(), interaction.httpStatus(),
                interaction.outcome(), interaction.durationMs(),
                Mask.agreement(interaction.agreementNumber()));

        if (props.getLms().isLogPayloads()) {
            PAYLOAD_LOG.debug("type={} correlationId={} request={} response={}",
                    interaction.type(), correlationId, interaction.requestBody(), interaction.responseBody());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Immutable carrier so nothing request-scoped crosses the async boundary. */
    public record Interaction(
            String type,
            String endpoint,
            String method,
            String agreementNumber,
            String requestBody,
            String responseBody,
            Integer httpStatus,
            String upstreamStatus,
            String upstreamCode,
            String outcome,
            String errorDetail,
            Long durationMs,
            Instant requestedAt,
            Instant respondedAt) {

        public static Interaction of(String type, String endpoint, String method, String agreementNumber,
                                     String requestBody, String responseBody, Integer httpStatus,
                                     String upstreamStatus, String upstreamCode, String outcome,
                                     String errorDetail, Instant requestedAt) {
            Instant now = Instant.now();
            return new Interaction(type, endpoint, method, agreementNumber, requestBody, responseBody,
                    httpStatus, upstreamStatus, upstreamCode, outcome, errorDetail,
                    Duration.between(requestedAt, now).toMillis(), requestedAt, now);
        }

        public static String correlation() {
            return CorrelationIdFilter.current();
        }
    }
}
