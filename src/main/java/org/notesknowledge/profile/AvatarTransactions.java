package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account -> session -> Profile lock order matches Identity account deletion. */
@Service
class AvatarTransactions {
    record Swap(ProfileView view, AvatarAsset retired) { }
    private final AvatarAssetRepository assets;
    private final ProfileService profiles;
    private final ObjectProvider<AccountEligibilityApi> eligibility;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;

    AvatarTransactions(AvatarAssetRepository assets, ProfileService profiles,
            ObjectProvider<AccountEligibilityApi> eligibility, DatabaseUuidV7Generator ids, Clock clock) {
        this.assets = assets; this.profiles = profiles; this.eligibility = eligibility; this.ids = ids; this.clock = clock;
    }

    @Transactional
    Swap replace(UUID owner, HttpServletRequest request, String reference, AvatarValidator.Canonical image) {
        eligibility.getObject().requireCurrentOwner(owner, request);
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        UUID profile = assets.lockOrCreate(ids.generate(), owner, now);
        AvatarAsset previous = assets.selected(profile);
        UUID id = ids.generate();
        assets.create(id, profile, reference, image, now);
        assets.select(profile, id, now);
        assets.retire(previous, now);
        return new Swap(profiles.read(owner), previous);
    }

    @Transactional
    AvatarAsset remove(UUID owner, HttpServletRequest request) {
        eligibility.getObject().requireCurrentOwner(owner, request);
        UUID profile = assets.lock(owner).orElse(null);
        if (profile == null) return null;
        AvatarAsset previous = assets.selected(profile);
        if (previous != null) {
            var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            assets.select(profile, null, now);
            assets.retire(previous, now);
        }
        return previous;
    }
}
