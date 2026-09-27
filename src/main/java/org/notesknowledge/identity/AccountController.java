package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@IdentityCoreEnabled
@RequestMapping("/api/me/account")
final class AccountController {
    record Confirmation(Boolean confirmAccountDeletion) { }

    private final AccountDeletionService deletion;
    private final MfaRateControl rates;

    AccountController(AccountDeletionService deletion, MfaRateControl rates) {
        this.deletion = deletion;
        this.rates = rates;
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@RequestBody Confirmation input, HttpServletRequest request) {
        var userId = IdentitySessionState.principal("ROLE_USER");
        rates.check("ACCOUNT_DELETE", userId, request);
        if (input == null || !Boolean.TRUE.equals(input.confirmAccountDeletion())) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        deletion.delete(userId, true, request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
