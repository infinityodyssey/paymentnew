package com.yourcompany.payment.client;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.exception.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * Bearer token for the LMS, cached cluster-wide in Redis.
 *
 * ONE THING TO CHECK BEFORE THIS RUNS. The legacy getEXApiToken(fiid) implementation
 * was not shared, so the request shape below is the obvious reading of the call site
 * rather than something derived from your code: POST to the token URL with {"fiid": n}
 * as the body, reading the token from a configurable JSON field
 * (app.global.lms-token-json-field).
 *
 * If your token endpoint differs, replace the body of requestToken() only. Everything
 * around it - the Redis cache, early expiry, interaction recording and the 401 evict
 * path - is independent of the request shape and does not need to change.
 */
@Slf4j
@Component
public class LmsTokenProvider {

    private static final String NAME = "lms";
    private static final String TYPE = "LMS_TOKEN";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final UpstreamTokenCache cache;
    private final InteractionRecorder recorder;

    public LmsTokenProvider(@Qualifier("lmsRestClient") RestClient restClient,
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
        return cache.get(NAME, props.getGlobal().getLmsTokenTtlSeconds(), this::requestToken);
    }

    public void invalidate() {
        cache.evict(NAME);
    }

    private String requestToken() {
        String url = props.getGlobal().getLmsTokenUrl();
        String fiid = props.getGlobal().getFiid();
        String requestBody = mapper.writeValueAsString(Map.of("fiid", fiid));
        Instant requestedAt = Instant.now();
        String correlationId = InteractionRecorder.Interaction.correlation();

        try {
            var response = restClient.post()
                    .uri(url)
                    .header("fiid", fiid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);

            String raw = response.getBody();
            JsonNode root = mapper.readTree(raw == null ? "{}" : raw);
            JsonNode tokenNode = root.get(props.getGlobal().getLmsTokenJsonField());

            if (tokenNode == null || tokenNode.isNull() || tokenNode.asString().isBlank()) {
                // The token value is never recorded. Only the fact that the field was
                // absent, which is what you need to diagnose a contract change.
                recorder.record(correlationId, InteractionRecorder.Interaction.of(
                        TYPE, url, "POST", null, requestBody, null,
                        response.getStatusCode().value(), null, null, "FAILURE",
                        "token field '" + props.getGlobal().getLmsTokenJsonField() + "' absent", requestedAt));
                throw UpstreamException.lms();
            }

            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, url, "POST", null, requestBody, null,
                    response.getStatusCode().value(), null, null, "SUCCESS", null, requestedAt));

            return tokenNode.asString();

        } catch (UpstreamException e) {
            throw e;
        } catch (Exception e) {
            log.error("LMS token request failed: {}", e.getClass().getSimpleName());
            recorder.record(correlationId, InteractionRecorder.Interaction.of(
                    TYPE, url, "POST", null, requestBody, null, null, null, null,
                    "FAILURE", e.getClass().getSimpleName(), requestedAt));
            throw UpstreamException.lms();
        }
    }
}
