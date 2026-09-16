package com.yourcompany.payment.messaging.event;

import java.time.Instant;

/**
 * Metadata-only stream record for SIEM and dashboards.
 *
 * Note what is absent: request and response bodies, customer name, mobile number and
 * the agreement number in clear. The agreement appears as a hash so events for one
 * borrower can be correlated without the topic becoming a copy of the loan book.
 * Payloads live in Oracle, where the grant and the retention are yours.
 */
public record LmsInteractionEvent(
        String correlationId,
        String interactionType,
        String endpoint,
        String httpMethod,
        String agreementHash,
        Integer httpStatus,
        String upstreamStatus,
        String upstreamCode,
        String outcome,
        Long durationMs,
        Instant occurredAt) {
}
