package org.notesknowledge.profile.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.websupport.CursorPage;

/** Profile-owned public-only author navigation; no private source composition. */
public interface ActiveAuthorPublications {
    CursorPage<Item> page(UUID projectionId,long projectionGeneration,Integer limit,String cursor);
    record Item(UUID id,String title,List<String> tags,Instant publishedAt,Instant updatedAt) { }
}
