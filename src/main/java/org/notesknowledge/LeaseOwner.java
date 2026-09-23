package org.notesknowledge;

/** Bounded operational alias only; never a UserId, session, host authority, or permission. */
public record LeaseOwner(String alias) {

    public LeaseOwner {
        if (alias == null || !alias.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid lease-owner alias");
        }
    }
}
