package org.notesknowledge.profile;

import java.time.Instant;

/** The entire private response allowlist; no internal identity or Account/security state. */
record ProfileView(String displayName, String biography, String handle, Instant updatedAt, AvatarSummary avatar) {
    static ProfileView absent() { return new ProfileView("", "", null, null, null); }
    ProfileView withAvatar(AvatarSummary summary) { return new ProfileView(displayName, biography, handle, updatedAt, summary); }
    @Override public String toString() { return "ProfileView[REDACTED]"; }
}
