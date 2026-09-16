package com.yourcompany.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Full record of every upstream call, the replacement for the legacy goldLogDetails table.
 *
 * Request and response bodies are stored in full because reconciliation and dispute
 * handling need the exact payload the LMS returned. This is the right home for them:
 * an Oracle table with a grant you control has an access model and a retention you
 * set. A log aggregator has neither.
 *
 * The SMS variant of this row stores the outbound URL with the OTP redacted, never the
 * code itself.
 */
@Entity
@Table(name = "LMS_INTERACTION_LOG")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LmsInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "CORRELATION_ID", length = 36, nullable = false)
    private String correlationId;

    /** LMS_FETCH, LMS_TOKEN, SMS_SEND, SMS_TOKEN. */
    @Column(name = "INTERACTION_TYPE", length = 32, nullable = false)
    private String interactionType;

    @Column(name = "ENDPOINT", length = 512, nullable = false)
    private String endpoint;

    @Column(name = "HTTP_METHOD", length = 8, nullable = false)
    private String httpMethod;

    @Column(name = "AGREEMENT_NUMBER", length = 50)
    private String agreementNumber;

    @Lob
    @Column(name = "REQUEST_BODY")
    private String requestBody;

    @Lob
    @Column(name = "RESPONSE_BODY")
    private String responseBody;

    @Column(name = "HTTP_STATUS")
    private Integer httpStatus;

    /** The upstream's own status field, e.g. "Success". */
    @Column(name = "UPSTREAM_STATUS", length = 64)
    private String upstreamStatus;

    @Column(name = "UPSTREAM_CODE", length = 32)
    private String upstreamCode;

    @Column(name = "OUTCOME", length = 16, nullable = false)
    private String outcome;

    @Column(name = "ERROR_DETAIL", length = 512)
    private String errorDetail;

    @Column(name = "DURATION_MS")
    private Long durationMs;

    @Column(name = "REQUESTED_AT", nullable = false)
    private Instant requestedAt;

    @Column(name = "RESPONDED_AT")
    private Instant respondedAt;
}
