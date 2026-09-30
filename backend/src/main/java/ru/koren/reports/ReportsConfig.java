package ru.koren.reports;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.koren.reports.persistence.ReportRepository;

@Configuration
@Profile("!no-db")
public class ReportsConfig {

    @Bean
    ReportRepository reportRepository(JdbcTemplate jdbc) {
        return new ReportRepository(jdbc);
    }
}
