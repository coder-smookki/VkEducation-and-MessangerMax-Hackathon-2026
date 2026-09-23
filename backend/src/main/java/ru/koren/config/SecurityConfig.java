package ru.koren.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.http.HttpStatus;

/** Closed by default. A route opens only together with authentication and object authorization. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        http.securityContext(context -> context.securityContextRepository(securityContextRepository()));
        // Cookie commands stay CSRF protected; the token is readable by the Mini App script.
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        http.exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, e) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/problem+json");
            response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Нужен вход через MAX\","
                    + "\"status\":401,\"code\":\"AUTH_REQUIRED\"}");
        }));
        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
            if (environment.matchesProfiles("demo")) {
                auth.requestMatchers(HttpMethod.GET, "/api/v1/demo/**").permitAll();
            }
            // Login itself cannot require a session; the signature is the credential.
            auth.requestMatchers(HttpMethod.GET, "/api/v1/csrf").permitAll();
            auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/max").permitAll();
            auth.requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated();
            auth.requestMatchers("/api/v1/cases", "/api/v1/cases/**").authenticated();
            auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated();
            // Case routes stay closed until object authorization is wired in the next step.
            auth.anyRequest().denyAll();
        });
        // No form login, no Basic login, no blanket CSRF disable, no trusted client user ID.
        return http.build();
    }
}
