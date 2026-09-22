package org.notesknowledge.websupport;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Strong validators for committed aggregate cores, never for volatile projections. */
@Component
public final class StrongCoreEtagCodec {

    private static final byte FORMAT_VERSION = 1;
    private static final byte NOTE_KIND = 1;
    private static final byte ATTACHMENT_KIND = 2;
    private static final byte PUBLICATION_KIND = 3;

    public String encode(NoteCoreVersion version) {
        return encode(NOTE_KIND, version.noteId(), version.revision());
    }

    public String encode(AttachmentCoreVersion version) {
        return encode(ATTACHMENT_KIND, version.attachmentId(), version.revision());
    }

    public String encode(PublicationCoreVersion version) {
        return encode(PUBLICATION_KIND, version.publicationId(),
                version.snapshotRevision(), version.publicationGeneration());
    }

    private String encode(byte resourceKind, UUID resourceId, long... versionNumbers) {
        // Fixed-width big-endian fields make kind, identity, and tuple boundaries unambiguous.
        ByteBuffer canonical = ByteBuffer.allocate(2 + 16 + Long.BYTES * versionNumbers.length);
        canonical.put(FORMAT_VERSION).put(resourceKind);
        canonical.putLong(resourceId.getMostSignificantBits());
        canonical.putLong(resourceId.getLeastSignificantBits());
        for (long number : versionNumbers) {
            canonical.putLong(number);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.array());
            return '"' + Base64.getUrlEncoder().withoutPadding().encodeToString(digest) + '"';
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
