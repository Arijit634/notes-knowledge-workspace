package org.notesknowledge.identity;

/** The only assignable privileged actions in the initial product. */
public enum ModerationCapability {
    REVIEW("moderation.review"),
    ENFORCE("moderation.enforce");

    private final String code;

    ModerationCapability(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }
}
