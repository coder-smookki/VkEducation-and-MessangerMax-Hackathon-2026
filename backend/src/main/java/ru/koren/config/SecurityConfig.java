package ru.koren.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;

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
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .ignoringRequestMatchers("/max/webhook"));
        http.exceptionHandling(handling -> handling
            .authenticationEntryPoint((request, response, e) -> ApiErrorController.write(response,
                HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Нужен вход через MAX"))
            // Only two things reach here: a failed CSRF check, and a route this API does not have.
            // Object-level refusals are CaseException 403s with their own codes and never come this way.
            .accessDeniedHandler((request, response, e) -> {
                if (e instanceof CsrfException) {
                    ApiErrorController.write(response, HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID",
                        "Проверка запроса не пройдена, обновите страницу");
                } else {
                    ApiErrorController.write(response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Не найдено");
                }
            }));
        http.authorizeHttpRequests(auth -> {
            // A forward to /error after a failure is not a new request: without this every 400, 404
            // and 500 of an open route was rewritten into 401. A direct GET /error is still denied.
            auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
            auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
            auth.requestMatchers(HttpMethod.GET, "/actuator/info").permitAll();
            if (environment.matchesProfiles("demo")) {
                auth.requestMatchers(HttpMethod.GET, "/api/v1/demo/**").permitAll();
            }
            // Login itself cannot require a session; the signature is the credential.
            auth.requestMatchers(HttpMethod.GET, "/api/v1/csrf").permitAll();
            auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/max").permitAll();
            auth.requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated();
            auth.requestMatchers("/api/v1/cases", "/api/v1/cases/**").authenticated();
            auth.requestMatchers("/api/v1/items/**").authenticated();
            auth.requestMatchers("/api/v1/reports/**").authenticated();
            auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated();
            auth.requestMatchers(HttpMethod.POST, "/api/v1/memberships/claim").authenticated();
            auth.requestMatchers(HttpMethod.POST, "/max/webhook").permitAll();
            // Anything not listed does not exist for a client: 401 without a session, 404 with one.
            auth.anyRequest().denyAll();
        });
        // No form login, no Basic login, no blanket CSRF disable, no trusted client user ID.
        return http.build();
    }
}
