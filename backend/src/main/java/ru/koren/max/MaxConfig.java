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
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import ru.koren.max.application.InboxWorker;
import ru.koren.max.application.NoRouteUpdateHandler;
import ru.koren.max.application.UpdateHandler;

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

    @Bean
    UpdateHandler updateHandler() {
        return new NoRouteUpdateHandler();
    }

    @Bean
    InboxWorker inboxWorker(InboxRepository inbox, UpdateHandler handler,
                            PlatformTransactionManager transactions, Clock clock) {
        return new InboxWorker(inbox, handler, transactions, clock);
    }

    /**
     * Polling is off unless asked for. A schedule that starts itself in tests would touch rows in
     * the middle of other checks, and those tests would fail once in a while for no visible reason.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "koren.max.workers-enabled", havingValue = "true")
    static class Workers {

        private final InboxWorker worker;

        Workers(InboxWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${koren.max.inbox-poll-ms:2000}")
        void drainInbox() {
            worker.drainOnce();
        }
    }
}
