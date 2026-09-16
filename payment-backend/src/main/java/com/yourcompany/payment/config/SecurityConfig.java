package com.yourcompany.payment.config;

import com.yourcompany.payment.security.filter.AntiReplayFilter;
import com.yourcompany.payment.security.filter.SessionAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * API chain. Ordered after the actuator chain so the management endpoints are matched
 * by their own rules; without that, anyRequest().denyAll() here would deny every
 * Prometheus scrape.
 *
 * Spring Security 7 removes AntPathRequestMatcher and MvcRequestMatcher, so all
 * matching goes through PathPatternRequestMatcher. PathPatternParser also rejects
 * mid-pattern wildcards; every pattern here is a trailing match.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final SessionAuthFilter sessionAuthFilter;
    private final AntiReplayFilter antiReplayFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder matcher = PathPatternRequestMatcher.withDefaults();

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))

            // Spring's CSRF token repository does not fit a cross-origin SPA. The
            // replacement is the cookie/header double-submit in SessionAuthFilter,
            // combined with SameSite=Strict and a CORS policy that will not preflight
            // an unknown origin.
            .csrf(AbstractHttpConfigurer::disable)

            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)

            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .preload(true)
                        .maxAgeInSeconds(63072000))
                .cacheControl(cache -> {}))

            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(matcher.matcher(org.springframework.http.HttpMethod.POST,
                        "/api/v1/auth/handshake")).permitAll()
                .requestMatchers(matcher.matcher("/api/v1/auth/**"))
                        .hasAnyAuthority("ROLE_SESSION", "ROLE_VERIFIED")
                .requestMatchers(matcher.matcher("/api/v1/payment/**")).hasAuthority("ROLE_VERIFIED")
                .requestMatchers(matcher.matcher("/api/v1/transactions/**")).hasAuthority("ROLE_VERIFIED")
                .anyRequest().denyAll())

            .addFilterBefore(antiReplayFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(sessionAuthFilter, AntiReplayFilter.class);

        return http.build();
    }
}
