package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProfileService {
    private final JpaProfileRepositoryAdapter repository;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    private final AvatarAssetRepository avatars;
    private final ObjectProvider<AccountEligibilityApi> eligibility;

    ProfileService(JpaProfileRepositoryAdapter repository, DatabaseUuidV7Generator ids, Clock clock, AvatarAssetRepository avatars,
            ObjectProvider<AccountEligibilityApi> eligibility) {
        this.repository = repository;
        this.ids = ids;
        this.clock = clock;
        this.avatars = avatars;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    ProfileView read(UUID owner) {
        return repository.find(owner).map(p -> p.view().withAvatar(avatars.summary(p.id()))).orElseGet(ProfileView::absent);
    }

    @Transactional
    ProfileView replace(UUID owner, String name, String biography, String handle, HttpServletRequest request) {
        // Filter admission is only a snapshot. Serialize the write with committed
        // Account/session revocation using Identity's supported transaction boundary.
        var guard=eligibility.getIfAvailable();
        if(guard==null)throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        guard.requireCurrentOwner(owner,request);
        Profile profile = new Profile(ids.generate(), owner, name, biography, handle,
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
        repository.replace(profile);
        return read(owner);
    }
}
