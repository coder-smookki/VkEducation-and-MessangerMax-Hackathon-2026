package ru.koren.identity;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;


/** Wires MAX login. Without a bot token the validator bean is absent and /auth/max stays unavailable. */
@Configuration
public class IdentityConfig {
    @Bean
    @Profile("!no-db")
    MembershipRepository membershipRepository(JdbcTemplate jdbc) {
        return new MembershipRepository(jdbc);
    }

    @Bean
    @Profile("!no-db")
    MaxUserProvisioner maxUserProvisioner(JdbcTemplate jdbc) {
        return new MaxUserProvisioner(jdbc);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnExpression("'${koren.max.bot-token:}' != ''")
    MaxInitDataValidator maxInitDataValidator(@Value("${koren.max.bot-token}") String botToken, Clock clock) {
        return new MaxInitDataValidator(botToken, clock);
    }

    @Bean
    @Profile("!no-db")
    @ConditionalOnExpression("'${koren.max.bot-token:}' != ''")
    AuthController authController(MaxInitDataValidator validator, MaxUserProvisioner provisioner,
                                  MembershipRepository memberships, SecurityContextRepository contextRepository,
                                  SessionAuthenticationStrategy sessionStrategy) {
        return new AuthController(validator, provisioner, memberships, contextRepository, sessionStrategy);
    }
}
