package ru.koren.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;

/** MAX login and session. The internal user id never comes from the request body. */
@RestController
@RequestMapping("/api/v1")
@Profile("!no-db")
@ConditionalOnBean(MaxInitDataValidator.class)
public class AuthController {

    private final MaxInitDataValidator validator;
    private final MaxUserProvisioner provisioner;
    private final MembershipRepository memberships;
    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;

    public AuthController(MaxInitDataValidator validator, MaxUserProvisioner provisioner,
                          MembershipRepository memberships, SecurityContextRepository contextRepository,
                          SessionAuthenticationStrategy sessionStrategy) {
        this.validator = validator;
        this.provisioner = provisioner;
        this.memberships = memberships;
        this.contextRepository = contextRepository;
        this.sessionStrategy = sessionStrategy;
    }

    @PostMapping("/auth/max")
    public MeView login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        MaxVerifiedUser verified = validator.validate(body == null ? null : body.initData());
        UUID userId = provisioner.findOrCreate(verified.maxUserId());
        Authentication authentication =
            new UsernamePasswordAuthenticationToken(userId, null, List.of());
        // A new session id on login closes session fixation.
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Without this the login survives only the current thread, not the next request.
        contextRepository.saveContext(context, request, response);
        return memberships.loadMe(userId, verified.displayName());
    }

    @GetMapping("/me")
    public MeView me(Authentication authentication) {
        return memberships.loadMe((UUID) authentication.getPrincipal(), null);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
