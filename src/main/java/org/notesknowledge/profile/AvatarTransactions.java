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

    @Transactional
    AvatarAssetRepository.Read current(UUID owner, HttpServletRequest request) {
        eligibility.getObject().requireCurrentOwner(owner,request);
        var profile=assets.lock(owner).orElse(null);
        var asset=profile==null?null:assets.selectedForRead(profile);
        if(asset==null||!"validated".equals(asset.state()))throw org.notesknowledge.websupport.ApiFailureException.of(
            org.notesknowledge.websupport.ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        if(asset.reference()==null||!asset.reference().matches("private-avatar/[0-9a-f]{64}")
                ||!java.util.Set.of("image/png","image/jpeg").contains(asset.type())
                ||asset.size()<1||asset.size()>AvatarValidator.OUTPUT_BYTES
                ||asset.width()<1||asset.height()<1||asset.width()>AvatarValidator.DIMENSION||asset.height()>AvatarValidator.DIMENSION
                ||(long)asset.width()*asset.height()>AvatarValidator.PIXELS)
            throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        return asset;
    }
}
