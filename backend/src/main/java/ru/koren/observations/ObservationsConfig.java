package ru.koren.observations;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.koren.observations.persistence.ObservationRepository;

@Configuration
@Profile("!no-db")
public class ObservationsConfig {

    @Bean
    ObservationRepository observationRepository(JdbcTemplate jdbc) {
        return new ObservationRepository(jdbc);
    }
}
