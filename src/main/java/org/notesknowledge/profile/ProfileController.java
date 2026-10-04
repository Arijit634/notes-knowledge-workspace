package org.notesknowledge.profile;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.websupport.ApiFailureException;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/profile")
final class ProfileController {
    private static final Set<String> FIELDS = Set.of("displayName", "biography", "handle");
    private final ProfileService profiles;

    ProfileController(ProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    ResponseEntity<ProfileView> read() {
        UUID owner = owner();
        try { return response(profiles.read(owner)); }
        catch (DataAccessException | CannotCreateTransactionException exception) { throw unavailable(); }
    }

    @PutMapping
    ResponseEntity<ProfileView> replace(@RequestBody Map<String, Object> input) {
        UUID owner = owner();
        if (input == null || !input.keySet().equals(FIELDS)
                || !(input.get("displayName") instanceof String name)
                || !(input.get("biography") instanceof String biography)
                || (input.get("handle") != null && !(input.get("handle") instanceof String))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        try { return response(profiles.replace(owner, name, biography, (String) input.get("handle"))); }
        catch (DataIntegrityViolationException exception) {
            // Translate only the expected handle collision after the transaction rolls back.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof PSQLException postgres && "23505".equals(postgres.getSQLState())
                        && postgres.getServerErrorMessage() != null
                        && "ux_profile_normalized_handle".equals(postgres.getServerErrorMessage().getConstraint())) {
                    throw ApiFailureException.of(ApiFailureException.Kind.PROFILE_HANDLE_UNAVAILABLE);
                }
            }
            throw unavailable();
        } catch (DataAccessException | CannotCreateTransactionException exception) { throw unavailable(); }
    }

    private ResponseEntity<ProfileView> response(ProfileView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    private UUID owner() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof IdentitySessionPrincipal principal)
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
        return principal.userId();
    }

    private ApiFailureException unavailable() {
        return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
    }
}
