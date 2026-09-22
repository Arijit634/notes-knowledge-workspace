package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST")
class StrongCoreEtagCodecTest {

    private static final UUID ID = UUID.fromString("01991800-0000-7000-8000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("01991800-0000-7000-8000-000000000002");

    private final StrongCoreEtagCodec codec = new StrongCoreEtagCodec();

    @Test
    void noteAndAttachmentTagsAreDeterministicAndRevisionBound() {
        String note = codec.encode(new NoteCoreVersion(ID, 1));
        String attachment = codec.encode(new AttachmentCoreVersion(ID, 1));

        assertThat(codec.encode(new NoteCoreVersion(ID, 1))).isEqualTo(note);
        assertThat(codec.encode(new NoteCoreVersion(ID, 2))).isNotEqualTo(note);
        assertThat(codec.encode(new AttachmentCoreVersion(ID, 1))).isEqualTo(attachment);
        assertThat(codec.encode(new AttachmentCoreVersion(ID, 2))).isNotEqualTo(attachment);
        assertThat(codec.encode(new NoteCoreVersion(OTHER_ID, 1))).isNotEqualTo(note);
        assertThat(codec.encode(new AttachmentCoreVersion(OTHER_ID, 1)))
                .isNotEqualTo(attachment);
    }

    @Test
    void publicationTagBindsBothApprovedVersionComponents() {
        String original = codec.encode(new PublicationCoreVersion(ID, 1, 1));

        assertThat(codec.encode(new PublicationCoreVersion(ID, 1, 1))).isEqualTo(original);
        assertThat(codec.encode(new PublicationCoreVersion(ID, 2, 1))).isNotEqualTo(original);
        assertThat(codec.encode(new PublicationCoreVersion(ID, 1, 2))).isNotEqualTo(original);
        assertThat(codec.encode(new PublicationCoreVersion(OTHER_ID, 1, 1)))
                .isNotEqualTo(original);
    }

    @Test
    void resourceKindsAreDomainSeparatedEvenForSameIdAndNumbers() {
        String note = codec.encode(new NoteCoreVersion(ID, 1));
        String attachment = codec.encode(new AttachmentCoreVersion(ID, 1));
        String publication = codec.encode(new PublicationCoreVersion(ID, 1, 1));

        assertThat(note).isNotEqualTo(attachment).isNotEqualTo(publication);
        assertThat(attachment).isNotEqualTo(publication);
    }

    @Test
    void tagsAreStrongQuotedAsciiOpaqueAndBounded() {
        String tag = codec.encode(new PublicationCoreVersion(ID, 12345, 67890));

        assertThat(tag).matches("\"[A-Za-z0-9_-]{43}\"");
        assertThat(tag).doesNotStartWith("W/")
                .doesNotContain(ID.toString(), "12345", "67890", ":");
        assertThat(tag).hasSize(45);
    }

    @Test
    void timestampsAndVolatileProjectionStateCannotAffectCoreTag() {
        NoteCoreVersion note = new NoteCoreVersion(ID, 7);
        AttachmentCoreVersion attachment = new AttachmentCoreVersion(ID, 3);
        PublicationCoreVersion publication = new PublicationCoreVersion(ID, 4, 9);
        String noteTag = codec.encode(note);
        String attachmentTag = codec.encode(attachment);
        String publicationTag = codec.encode(publication);

        SyntheticVolatileProjection before = new SyntheticVolatileProjection(
                Instant.parse("2026-01-01T00:00:00Z"), "pending");
        SyntheticVolatileProjection after = new SyntheticVolatileProjection(
                Instant.parse("2026-12-31T23:59:59Z"), "completed");
        assertThat(before).isNotEqualTo(after);
        assertThat(codec.encode(note)).isEqualTo(noteTag);
        assertThat(codec.encode(attachment)).isEqualTo(attachmentTag);
        assertThat(codec.encode(publication)).isEqualTo(publicationTag);
    }

    @Test
    void invalidVersionTuplesFailBeforeTagEmission() {
        assertThatThrownBy(() -> new NoteCoreVersion(null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NoteCoreVersion(ID, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttachmentCoreVersion(null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AttachmentCoreVersion(ID, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicationCoreVersion(null, 1, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PublicationCoreVersion(ID, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicationCoreVersion(ID, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record SyntheticVolatileProjection(Instant updatedAt, String state) {
    }
}
