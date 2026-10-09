package org.notesknowledge.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;

/** Test-only persisted browser proof; never packaged with the application. */
public final class ModerationBrowserFixture {
    private ModerationBrowserFixture(){ }
    public static void authenticate(Session session,UUID user,Instant recent,boolean full) {
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority(full?"ROLE_USER":"ROLE_MFA_PENDING"))));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        if(recent!=null)session.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,new IdentitySessionState.RecentAuthentication(user,recent,"password"));
    }
}
