package ru.koren.max;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.koren.max.application.WebhookIntake;

/** Separate root path, own secret, no session and no CSRF: MAX carries neither. */
@RestController
@RequestMapping("/max")
@Profile("!no-db")
@ConditionalOnBean(WebhookIntake.class)
public class MaxWebhookController {

    /** The header MAX itself sends with every webhook when the subscription has a secret. */
    public static final String SECRET_HEADER = "X-Max-Bot-Api-Secret";

    private final WebhookIntake intake;

    public MaxWebhookController(WebhookIntake intake) {
        this.intake = intake;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
        @RequestHeader(value = SECRET_HEADER, required = false) String secret,
        @RequestBody(required = false) byte[] body) {
        // A duplicate is answered 200 on purpose: MAX must stop redelivering what we already hold.
        return switch (intake.accept(secret, body)) {
            case ACCEPTED, DUPLICATE -> ResponseEntity.ok().build();
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case TOO_LARGE -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
            case MALFORMED -> ResponseEntity.badRequest().build();
        };
    }
}
