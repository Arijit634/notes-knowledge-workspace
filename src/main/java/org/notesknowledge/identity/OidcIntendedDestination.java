package org.notesknowledge.identity;

import java.util.Set;
import java.util.regex.Pattern;

/** Exact first-party route grammar. No URL decoding, trimming or authority inference. */
final class OidcIntendedDestination {
    private static final Set<String> STATIC=Set.of("/notes","/notes/new","/ask","/settings/profile",
        "/settings/privacy","/settings/security","/settings/security/mfa","/settings/security/sessions",
        "/settings/public-profile","/publications","/explore","/explore/search","/moderation/reports");
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern DYNAMIC=Pattern.compile("/(?:notes|publication|publications|moderation/reports)/"+UUID+"|/profile/[a-z][a-z0-9_]{2,29}");
    static boolean allowed(String target){return target!=null&&target.length()<=180&&(STATIC.contains(target)||DYNAMIC.matcher(target).matches());}
    static String safe(String target){return allowed(target)?target:"/notes";}
    private OidcIntendedDestination(){ }
}
