package ru.koren.max;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.koren.max.application.WebhookIntake;
import ru.koren.max.persistence.InboxRepository;

/** Without a configured secret the webhook does not exist at all: an open one is worse than none. */
@Configuration
@Profile("!no-db")
@ConditionalOnExpression("'${koren.max.webhook-secret:}' != ''")
public class MaxConfig {

    @Bean
    InboxRepository inboxRepository(JdbcTemplate jdbc) {
        return new InboxRepository(jdbc);
    }

    @Bean
    WebhookIntake webhookIntake(InboxRepository inbox, PlatformTransactionManager transactions,
                                @Value("${koren.max.webhook-secret}") String secret) {
        return new WebhookIntake(inbox, transactions, secret);
    }
}
