package com.yourcompany.payment.config;

import com.yourcompany.payment.security.filter.AntiReplayFilter;
import com.yourcompany.payment.security.filter.SessionAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stops the security filters running twice.
 *
 * A filter annotated @Component is picked up by servlet auto-registration AND inserted
 * into the Spring Security chain. Without these overrides the anti-replay filter would
 * run first, consume the nonce, and the second execution inside the chain would see a
 * duplicate and reject every single request.
 */
@Configuration
public class FilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<AntiReplayFilter> disableAntiReplayAutoRegistration(AntiReplayFilter filter) {
        FilterRegistrationBean<AntiReplayFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<SessionAuthFilter> disableSessionAuthAutoRegistration(SessionAuthFilter filter) {
        FilterRegistrationBean<SessionAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
