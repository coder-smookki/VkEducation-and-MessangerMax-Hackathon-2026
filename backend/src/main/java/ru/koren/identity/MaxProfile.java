package ru.koren.identity;

import java.io.Serializable;
import org.springframework.security.core.Authentication;

/** Display-only data from signed MAX initData. Never grants identity, roles or house access. */
public record MaxProfile(String displayName, String username, String avatarUrl) implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final MaxProfile EMPTY = new MaxProfile(null, null, null);

    /** Older sessions and authentication created without a MAX profile still have valid permissions. */
    public static MaxProfile from(Authentication authentication) {
        return authentication.getDetails() instanceof MaxProfile profile ? profile : EMPTY;
    }
}
