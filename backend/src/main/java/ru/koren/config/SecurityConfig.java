package ru.koren.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Closed by default. MAX authentication and house membership belong to task K1/S1. */
@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
            if (environment.matchesProfiles("demo")) {
                auth.requestMatchers(HttpMethod.GET, "/api/v1/demo/**").permitAll();
            }
            auth.anyRequest().denyAll();
        });
        // No form login, no Basic login, no blanket CSRF disable, no trusted client user ID.
        return http.build();
    }
}
