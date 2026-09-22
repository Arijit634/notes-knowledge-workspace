package org.notesknowledge.websupport;

import java.util.List;
import java.util.Objects;

/** Generic collection transport; no exact total or authorization claim. */
public record CursorPage<T>(List<T> items, String nextCursor) {
    public CursorPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (nextCursor != null && (nextCursor.isBlank()
                || nextCursor.length() > OpaqueCursorCodec.MAX_TOKEN_LENGTH)) {
            throw new IllegalArgumentException("Invalid next cursor");
        }
    }
}
