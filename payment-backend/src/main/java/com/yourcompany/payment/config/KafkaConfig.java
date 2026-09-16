package com.yourcompany.payment.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Producer for the interaction metadata stream.
 *
 * Transport security comes from spring.kafka.security.protocol and the SASL properties;
 * the application refuses nothing here, so if someone sets PLAINTEXT this will happily
 * ship metadata in the clear. That is a deployment review item, not something the code
 * can enforce, and it is worth putting on the checklist.
 *
 * ADD_TYPE_INFO_HEADERS is off: type headers couple every consumer to our package
 * names and let a malicious producer steer consumer deserialization.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildProducerProperties(null);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
