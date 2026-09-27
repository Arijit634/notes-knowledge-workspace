package org.notesknowledge.identity;

/** A deliberately small display vocabulary; the untrusted header is never persisted. */
final class SessionClientLabel {
    private SessionClientLabel() { }

    static String from(String userAgent) {
        if (userAgent == null || userAgent.length() > 2048) return "Unknown browser";
        String browser = userAgent.contains("Edg/") ? "Edge"
                : userAgent.contains("Firefox/") ? "Firefox"
                : userAgent.contains("Chrome/") ? "Chrome"
                : userAgent.contains("Safari/") ? "Safari" : "Browser";
        String platform = userAgent.contains("Android") ? "Android"
                : userAgent.contains("iPhone") || userAgent.contains("iPad") ? "iOS"
                : userAgent.contains("Windows") ? "Windows"
                : userAgent.contains("Macintosh") ? "macOS"
                : userAgent.contains("Linux") ? "Linux" : null;
        return platform == null ? "Unknown browser" : browser + " on " + platform;
    }
}
