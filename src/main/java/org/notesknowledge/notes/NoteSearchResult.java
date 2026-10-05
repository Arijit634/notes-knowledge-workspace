package org.notesknowledge.notes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record NoteSearchResult(UUID id, String title, String lifecycle, boolean pinned,
        List<String> tags, Instant updatedAt, String snippet, List<String> matchLabels) { }
