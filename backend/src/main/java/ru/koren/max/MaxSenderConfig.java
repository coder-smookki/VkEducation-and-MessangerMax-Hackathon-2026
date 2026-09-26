package ru.koren.max;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import ru.koren.max.application.MaxSender;
import ru.koren.max.application.UnconfiguredMaxSender;

/** The sender needs the bot token, not the webhook secret: sending out needs no public address. */
@Configuration
@Profile("!no-db")
@ConditionalOnExpression("'${koren.max.bot-token:}' != ''")
public class MaxSenderConfig {

    @Bean
    MaxSender maxSender() {
        return new UnconfiguredMaxSender();
    }
}
