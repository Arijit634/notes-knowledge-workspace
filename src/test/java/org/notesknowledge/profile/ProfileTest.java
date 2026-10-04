package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST")
class ProfileTest {
    private static final UUID ID = UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401");

    @Test void preservesPresentationAndNormalizesOnlyHandleEquality() {
        Profile profile = new Profile(ID, ID, "Reader", "বাংলা\n日本語", "Reader_One", Instant.EPOCH);
        assertThat(profile.view().handle()).isEqualTo("Reader_One");
        assertThat(profile.normalizedHandle()).isEqualTo("reader_one");
        assertThat(profile.view().biography()).isEqualTo("বাংলা\n日本語");
        assertThat(profile.toString()).doesNotContain("Reader", "বাংলা");
        assertThat(profile.view().toString()).doesNotContain("Reader", "বাংলা");
        assertThat(PublicHandle.from("Reader_One").toString()).doesNotContain("Reader");
    }

    @Test void acceptsEmptyPresentationAndNullHandleWithoutInventedData() {
        Profile profile = new Profile(ID, ID, "", "", null, Instant.EPOCH);
        assertThat(profile.normalizedHandle()).isNull();
        assertThat(profile.view().handle()).isNull();
        assertThat(ProfileView.absent()).isEqualTo(new ProfileView("", "", null, null, null));
    }

    @Test void contentBoundsMatchPostgresUnicodeCodePointSemantics() {
        new Profile(ID, ID, "😀".repeat(100), "x".repeat(500), "A".repeat(30), Instant.EPOCH);
        assertThatThrownBy(() -> new Profile(ID, ID, "😀".repeat(101), "", null, Instant.EPOCH))
                .isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(() -> new Profile(ID, ID, "", "x".repeat(501), null, Instant.EPOCH))
                .isInstanceOf(ApiFailureException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "ab", "1reader", "a-b", "a.b", "éclair", "Ａbc", "abc/def", "abc\n", " abc", "abc "})
    void rejectsInvalidHandleInsteadOfSilentlyClearingOrFoldingIt(String handle) {
        assertThatThrownBy(() -> PublicHandle.from(handle)).isInstanceOf(ApiFailureException.class);
    }

    @Test void rejectsControlsNullAndMalformedUnicodeWithoutEchoingContent() {
        assertThatThrownBy(() -> new Profile(ID, ID, "bad\nname", "", null, Instant.EPOCH)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(() -> new Profile(ID, ID, "", "bad\u0000bio", null, Instant.EPOCH)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(() -> new Profile(ID, ID, "\uD800", "", null, Instant.EPOCH)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(() -> new Profile(ID, ID, null, "", null, Instant.EPOCH)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(() -> new Profile(ID, ID, "", null, null, Instant.EPOCH)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(() -> PublicHandle.from("a".repeat(31))).isInstanceOf(ApiFailureException.class);
    }
}
