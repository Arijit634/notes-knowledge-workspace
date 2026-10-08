package org.notesknowledge.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.session.Session;

/** Test-only construction of persisted application proof; never production authority. */
public final class SyntheticRecentProof {
    private SyntheticRecentProof(){ }
    public static void password(Session session,UUID owner,Instant time){
        session.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,new IdentitySessionState.RecentAuthentication(owner,time,"password"));
    }
}
