package org.notesknowledge.discovery.spi;

import java.util.List;
import java.util.UUID;
import org.notesknowledge.profile.PublicProfileApi;

/** Consumer-owned public authority seam, implemented by Publishing. Never returns private provenance. */
public interface PublicDiscoveryScope {
    record Current(UUID id,long generation,long snapshot,PublicProfileApi.View author) { }
    List<Current> current(List<UUID> ids,boolean lock);
}
