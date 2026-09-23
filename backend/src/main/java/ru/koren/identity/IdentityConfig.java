package ru.koren.identity;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;


/** Wires MAX login. Without a bot token the validator bean is absent and /auth/max stays unavailable. */
@Configuration
public class IdentityConfig {
    @Bean
    @Profile("!no-db")
    MembershipRepository membershipRepository(JdbcTemplate jdbc) {
        return new MembershipRepository(jdbc);
    }

    @Bean
    @ConditionalOnExpression("'${koren.max.bot-token:}' != ''")
    MaxInitDataValidator maxInitDataValidator(@Value("${koren.max.bot-token}") String botToken) {
        return new MaxInitDataValidator(botToken, Clock.systemUTC());
    }
}
