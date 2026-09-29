package org.notesknowledge.identity;

import java.util.Objects;
import java.util.UUID;

/** Public-report scope only; null reportId denotes the bounded public-report queue. */
public record ModerationScope(UUID reportId) {
    public static ModerationScope allPublicReports() {
        return new ModerationScope(null);
    }

    public static ModerationScope publicReport(UUID reportId) {
        return new ModerationScope(Objects.requireNonNull(reportId, "reportId"));
    }
}
