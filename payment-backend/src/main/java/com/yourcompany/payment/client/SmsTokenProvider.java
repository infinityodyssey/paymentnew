package com.yourcompany.payment.client;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.exception.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Bearer token for the SMS gateway, cached cluster-wide.
 *
 * Same caveat as LmsTokenProvider: getSmsAccessToken()'s request shape was not shared,
 * so this issues a bare POST to the configured token URL and reads the token from
 * app.global.sms-token-json-field. Replace requestToken() if your endpoint differs;
 * nothing else needs to change.
 */
@Slf4j
@Component
public class SmsTokenProvider {

    private static final String NAME = "sms";
    private static final String TYPE = "SMS_TOKEN";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final UpstreamTokenCache cache;
    private final InteractionRecorder recorder;

    public SmsTokenProvider(@Qualifier("smsRestClient") RestClient restClient,
                            ObjectMapper mapper,
                            AppProperties props,
                            UpstreamTokenCache cache,
                            InteractionRecorder recorder) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.props = props;
        this.cache = cache;
        this.recorder = recorder;
    }

    public String token() {
        return cache.get(NAME, props.getGlobal().getSmsTokenTtlSeconds(), this::requestToken);
    }

    public void invalidate() {
        cache.evict(NAME);
    }

    private String requestToken() {
        String url = props.getGlobal().getSmsTokenUrl();
        Instant requestedAt = Instant.now();
        String correlationId = InteractionRecorder.Interaction.correlation();

        try {
            var response = restClient.post().uri(url).retrieve().toEntity(String.class);

            String raw = response.getBody();
            JsonNode root = mapper.readTree(raw == null ? "{}" : raw);
            JsonNode tokenNode = root.get(props.getGlobal().getSmsTokenJsonField());

            if (tokenNode == null || tokenNode.isNull() || tokenNode.asString().isBlank()) {
                recorder.record(correlationId, InteractionRecorder.Interaction.of(
                        TYPE, url, "POST", null, null, null,
                        response.getStatusCode().value(), null, null, "FAILURE",
                        "token field absent", requestedAt));
                throw UpstreamException.sms();
            }

            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, url, "POST", null, null, null,
                    response.getStatusCode().value(), null, null, "SUCCESS", null, requestedAt));

            return tokenNode.asString();

        } catch (UpstreamException e) {
            throw e;
        } catch (Exception e) {
            log.error("SMS token request failed: {}", e.getClass().getSimpleName());
            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, url, "POST", null, null, null, null, null, null,
                    "FAILURE", e.getClass().getSimpleName(), requestedAt));
            throw UpstreamException.sms();
        }
    }
}
