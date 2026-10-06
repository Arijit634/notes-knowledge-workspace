package org.notesknowledge.knowledge;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Provider-owned consistency seam: source owner calls inside its already-authorized mutation transaction. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class KnowledgeInvalidationApi {
    private final PrivateRepresentationRepository representations;
    KnowledgeInvalidationApi(PrivateRepresentationRepository representations) { this.representations=representations; }
    public void noteChanged(UUID owner,UUID note) { representations.invalidate(owner,note,null); }
    public void attachmentChanged(UUID owner,UUID note,UUID attachment) { representations.invalidate(owner,note,attachment); }
}
