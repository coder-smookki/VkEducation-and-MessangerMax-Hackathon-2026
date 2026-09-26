package ru.koren.max;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.koren.max.application.HttpMaxSender;
import ru.koren.max.application.MaxSender;
import ru.koren.max.application.MaxTls;
import ru.koren.max.persistence.JdbcMaxRecipients;

/** The sender needs the bot token, not the webhook secret: sending out needs no public address. */
@Configuration
@Profile("!no-db")
@ConditionalOnExpression("'${koren.max.bot-token:}' != ''")
public class MaxSenderConfig {

    /**
     * Two seconds per request: a batch of 20 that all time out still finishes inside the 60-second
     * outbox lease, so a slow MAX cannot make another worker take the same rows mid-send.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    MaxSender maxSender(JdbcTemplate jdbc,
                        @Value("${koren.max.bot-token}") String token,
                        @Value("${koren.max.api-url}") String apiUrl,
                        @Value("${koren.max.bot-name:}") String botName,
                        @Value("${koren.case-number-prefix:}") String numberPrefix) {
        HttpClient http = HttpClient.newBuilder()
            .sslContext(MaxTls.sslContext())
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return new HttpMaxSender(http, apiUrl, token, botName, numberPrefix,
            new JdbcMaxRecipients(jdbc), REQUEST_TIMEOUT);
    }
}
