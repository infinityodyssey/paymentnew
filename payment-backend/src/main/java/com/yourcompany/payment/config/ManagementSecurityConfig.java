package com.yourcompany.payment.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Actuator chain, at @Order(1) so it matches before the API chain's denyAll.
 *
 * Metrics are not public. Even on a separate port bound to the internal interface, an
 * unauthenticated /actuator/prometheus leaks endpoint names, traffic volumes, error
 * rates and JVM internals to anything that reaches the pod network - which in most
 * clusters is every other pod.
 *
 * Liveness and readiness are left open because the orchestrator probes them before any
 * credential is available, and they return nothing but UP or DOWN.
 */
@Configuration
@RequiredArgsConstructor
public class ManagementSecurityConfig {

    private final AppProperties props;

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(EndpointRequest.to("health")).permitAll()
                .anyRequest().hasRole("ACTUATOR"))
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public UserDetailsService actuatorUserDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User
                .withUsername(props.getSecurity().getActuatorUsername())
                .password(encoder.encode(props.getSecurity().getActuatorPassword()))
                .roles("ACTUATOR")
                .build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
