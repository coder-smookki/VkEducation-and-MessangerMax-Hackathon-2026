package ru.koren.identity;

import java.util.List;
import java.util.UUID;

/** Verified MAX user and active memberships. Empty memberships means a verified account without house access. */
public record MeView(UUID userId, String displayName, String username, String avatarUrl,
                     List<MembershipView> memberships) {
    public MeView {
        memberships = List.copyOf(memberships);
    }

}
