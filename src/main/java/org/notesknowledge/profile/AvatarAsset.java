package org.notesknowledge.profile;

import java.util.UUID;

/** Internal cleanup identity; physical storage never grants Profile eligibility. */
record AvatarAsset(UUID id, String reference) {
    @Override public String toString() { return "AvatarAsset[REDACTED]"; }
}
