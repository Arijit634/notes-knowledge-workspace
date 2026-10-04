package org.notesknowledge.profile;

import java.time.Instant;

/** Owner-visible allowlist; no locator, asset identity, filename, or storage authority. */
record AvatarSummary(String mediaType, int width, int height, long byteSize, Instant createdAt) { }
