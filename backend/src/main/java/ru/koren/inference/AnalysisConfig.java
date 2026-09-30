package ru.koren.inference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import ru.koren.cases.persistence.CaseRepository;
import ru.koren.inference.application.AnalysisWorker;
import ru.koren.inference.persistence.AnalysisRepository;
import ru.koren.reports.persistence.ReportRepository;

/** The repository and worker always exist; only the schedule and the queueing follow the switch. */
@Configuration
@Profile("!no-db")
public class AnalysisConfig {

    @Bean
    AnalysisRepository analysisRepository(JdbcTemplate jdbc) {
        return new AnalysisRepository(jdbc);
    }

    @Bean
    AnalysisWorker analysisWorker(AnalysisRepository analyses, ReportRepository reports, CaseRepository cases,
                                  InferenceClient inference, PlatformTransactionManager transactions) {
        return new AnalysisWorker(analyses, reports, cases, inference, transactions);
    }

    /**
     * The first run waits one full interval too, so a test context can switch analysis on and still
     * drive the worker by hand: with a long interval the schedule never touches its rows.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "koren.analysis.enabled", havingValue = "true")
    static class Schedule {

        private final AnalysisWorker worker;

        Schedule(AnalysisWorker worker) {
            this.worker = worker;
        }

        @Scheduled(initialDelayString = "${koren.analysis.poll-ms:2000}",
            fixedDelayString = "${koren.analysis.poll-ms:2000}")
        void drain() {
            worker.drainOnce();
        }
    }
}
