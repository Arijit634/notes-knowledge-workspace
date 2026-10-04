package org.notesknowledge.profile;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Profile-internal bridge for the Identity-owned consequence SPI adapter. */
@Service
public class AccountDeletionAvatarConsequence {
    private final AvatarAssetRepository assets;
    private final Clock clock;
    AccountDeletionAvatarConsequence(AvatarAssetRepository assets, Clock clock) { this.assets = assets; this.clock = clock; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void makeIneligible(UUID owner) {
        UUID profile = assets.lock(owner).orElse(null);
        if (profile == null) return;
        AvatarAsset selected = assets.selected(profile);
        if (selected == null) return;
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        assets.select(profile, null, now);
        assets.retire(selected, now);
        // Durable removed/cleaned state is picked up by Profile reconciliation.
        // No storage I/O or after-commit callback runs inside Identity's transaction.
    }
}
