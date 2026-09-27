package ru.koren.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.identity.InvitationException;
import ru.koren.identity.persistence.InvitationRepository;

/** Exchanges a one-time code for RESIDENT membership. The code itself is never stored or logged. */
public final class InvitationService {

    private final InvitationRepository invitations;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public InvitationService(InvitationRepository invitations, JdbcTemplate jdbc,
                             PlatformTransactionManager transactions) {
        this.invitations = Objects.requireNonNull(invitations);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    }

    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(code.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Returns the house the user joined. The invitation and the membership are one transaction. */
    public UUID claim(UUID userId, String invitationCode) {
        if (invitationCode == null || invitationCode.isBlank()) {
            throw new InvitationException("INVALID_FIELDS", 422);
        }
        String codeHash = hash(invitationCode);
        UUID house = transaction.execute(status -> {
            UUID houseId = invitations.consume(codeHash, userId);
            if (houseId == null) {
                return null;
            }
            // A uniqueness error would abort this PostgreSQL transaction before a catch could UPDATE.
            // Preserve active privileges, but a resident invitation cannot restore revoked staff access.
            jdbc.update("""
                    INSERT INTO membership(user_id, house_id, role) VALUES (?, ?, 'RESIDENT')
                    ON CONFLICT (user_id, house_id) DO UPDATE
                    SET role = CASE WHEN membership.revoked_at IS NULL THEN membership.role ELSE 'RESIDENT' END,
                        revoked_at = NULL
                    """, userId, houseId);
            return houseId;
        });
        if (house == null) {
            // Counting the miss outside the failed transaction keeps the attempt recorded.
            invitations.countFailedAttempt(codeHash);
            // One code for used, expired and unknown alike: the reason is not disclosed to a guesser.
            throw new InvitationException("INVITATION_REJECTED", 403);
        }
        return house;
    }
}
