package ru.koren.notifications;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import ru.koren.cases.application.CaseService;
import ru.koren.max.application.MaxSender;
import ru.koren.notifications.application.OutboxWorker;
import ru.koren.notifications.persistence.OutboxRepository;

/** Durability lives here; the transport lives in max/. Same switch as the inbox worker. */
@Configuration
@Profile("!no-db")
@ConditionalOnExpression("'${koren.max.bot-token:}' != ''")
public class NotificationsConfig {

    @Bean
    OutboxRepository outboxRepository(JdbcTemplate jdbc) {
        return new OutboxRepository(jdbc);
    }

    @Bean
    OutboxWorker outboxWorker(OutboxRepository outbox, CaseService cases, MaxSender sender,
                              PlatformTransactionManager transactions, Clock clock) {
        return new OutboxWorker(outbox, cases, sender, transactions, clock);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "koren.max.workers-enabled", havingValue = "true")
    static class Workers {

        private final OutboxWorker worker;

        Workers(OutboxWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${koren.max.outbox-poll-ms:2000}")
        void drainOutbox() {
            worker.drainOnce();
        }
    }
}
