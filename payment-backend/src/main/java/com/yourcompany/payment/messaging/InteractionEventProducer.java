package com.yourcompany.payment.messaging;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.messaging.event.LmsInteractionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes interaction metadata.
 *
 * Fire and forget with a logged failure callback. Kafka is a fan-out for observability,
 * never the system of record: at-least-once delivery, finite retention and consumer lag
 * mean gaps and duplicates, which is fine for a dashboard and unacceptable for
 * something a regulator might ask to see. The Oracle row is the record.
 *
 * The producer is configured with max.block.ms=2000 so an unreachable broker cannot
 * stall the caller. Even so this runs on the async path only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AppProperties props;

    public void publish(LmsInteractionEvent event) {
        try {
            kafkaTemplate.send(props.getLms().getTopic(), event.correlationId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Interaction event not published type={} correlationId={} cause={}",
                                    event.interactionType(), event.correlationId(),
                                    ex.getClass().getSimpleName());
                        }
                    });
        } catch (Exception e) {
            log.warn("Interaction event publish rejected: {}", e.getClass().getSimpleName());
        }
    }
}
