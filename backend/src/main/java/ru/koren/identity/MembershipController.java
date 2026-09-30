package ru.koren.identity;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.koren.identity.application.InvitationService;
import ru.koren.identity.persistence.MembershipRepository;

/** Membership comes only from a one-time server-issued invitation, never from a requested role. */
@RestController
@RequestMapping("/api/v1/memberships")
@Profile("!no-db")
@ConditionalOnExpression("'${koren.max.bot-token:}' != ''")
public class MembershipController {

    private final InvitationService invitations;
    private final MembershipRepository memberships;

    public MembershipController(InvitationService invitations, MembershipRepository memberships) {
        this.invitations = invitations;
        this.memberships = memberships;
    }

    @PostMapping("/claim")
    public MeView claim(Authentication authentication, @RequestBody ClaimRequest body) {
        UUID userId = (UUID) authentication.getPrincipal();
        invitations.claim(userId, body == null ? null : body.invitationCode());
        return memberships.loadMe(userId, MaxProfile.from(authentication));
    }
}
