package com.yourcompany.payment.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP clients for the two upstreams.
 *
 * The LMS read timeout matches their stated 30 seconds. That number has consequences
 * the browser has to account for: the Angular client needs a slightly longer timeout or
 * it will show a failure while the backend is still waiting, and the LMS circuit
 * breaker is configured on slow-call rate rather than failure rate alone, because 30
 * seconds of held connections will exhaust the pool long before enough calls fail to
 * trip a failure-rate breaker.
 *
 * Connect timeouts stay short: a slow response is the upstream working, an unreachable
 * host is not, and there is no reason to wait 30 seconds to discover a closed port.
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final AppProperties props;

    @Bean("lmsRestClient")
    public RestClient lmsRestClient() {
        return build(props.getGlobal().getLmsTimeoutSeconds());
    }

    @Bean("smsRestClient")
    public RestClient smsRestClient() {
        return build(props.getGlobal().getSmsTimeoutSeconds());
    }

    private RestClient build(int readTimeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        return RestClient.builder().requestFactory(factory).build();
    }
}
