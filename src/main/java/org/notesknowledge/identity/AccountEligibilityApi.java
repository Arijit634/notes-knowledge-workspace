package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes owner-module mutations with current Account/session revocation. */
@Service
@IdentityCoreEnabled
public class AccountEligibilityApi {
    private final IdentityPersistence identity;
    private final SpringSessionAuthorityAdapter sessions;

    AccountEligibilityApi(IdentityPersistence identity, SpringSessionAuthorityAdapter sessions) {
        this.identity = identity;
        this.sessions = sessions;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrentOwner(UUID userId, HttpServletRequest request) {
        var session = request.getSession(false);
        if (!userId.equals(IdentitySessionState.principal("ROLE_USER")) || session == null
                || !identity.lockActiveAccount(userId)
                || sessions.lockCurrent(userId, session.getId()) == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
    }

    /** Current Account fact for background revalidation; not browser/session or source authority. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isEligible(UUID expectedUserId) {
        return identity.lockActiveAccount(expectedUserId);
    }

    /** Non-locking current fact for anonymous public resolution; no private Account fields leave Identity. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isPubliclyEligible(UUID expectedUserId) {
        return identity.isActiveAccount(expectedUserId);
    }
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public java.util.Set<UUID> publiclyEligibleBatch(java.util.List<UUID> ids) {
        if(ids.size()>100)throw new IllegalArgumentException("Public eligibility batch exceeds bound");
        return ids.isEmpty()?java.util.Set.of():identity.activePublicSubjects(ids);
    }
}
