package org.notesknowledge.profile;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.notesknowledge.websupport.ApiFailureException;

/** Private presentation root; never an Account, public projection, or authority credential. */
@Entity
@Table(name = "profile", schema = "profile")
class Profile {
    @Id @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "display_name", nullable = false)
    private String displayName;
    @Column(name = "biography", nullable = false)
    private String biography;
    @Column(name = "public_handle_original")
    private String handleOriginal;
    @Column(name = "public_handle_normalized")
    private String handleNormalized;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Profile() { }

    Profile(UUID id, UUID owner, String name, String biography, String handle, Instant now) {
        validateText(name, 100, false);
        validateText(biography, 500, true);
        PublicHandle value = PublicHandle.from(handle);
        this.id = id;
        this.userId = owner;
        this.displayName = name;
        this.biography = biography;
        this.handleOriginal = value == null ? null : value.original();
        this.handleNormalized = value == null ? null : value.normalized();
        this.updatedAt = now;
    }

    // Content limits count Unicode code points, matching PostgreSQL char_length.
    private static void validateText(String value, int limit, boolean multiline) {
        if (value == null || value.codePointCount(0, value.length()) > limit
                || value.codePoints().anyMatch(c -> (c >= 0xD800 && c <= 0xDFFF)
                    || (Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t'))))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
    }

    UUID id() { return id; }
    UUID userId() { return userId; }
    String normalizedHandle() { return handleNormalized; }
    ProfileView view() { return new ProfileView(displayName, biography, handleOriginal, updatedAt); }
    @Override public String toString() { return "Profile[REDACTED]"; }
}
