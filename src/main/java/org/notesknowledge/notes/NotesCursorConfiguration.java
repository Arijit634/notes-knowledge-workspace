package org.notesknowledge.notes;

import java.time.Clock;
import java.util.Base64;

import org.notesknowledge.websupport.CursorKeyRing;
import org.notesknowledge.websupport.OpaqueCursorCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotesCursorProperties.class, NoteCheckpointPolicy.class, NotesSearchProperties.class})
class NotesCursorConfiguration {
    @Bean
    CursorKeyRing notesCursorKeyRing(NotesCursorProperties properties) {
        if (properties.active() == null) {
            throw new IllegalStateException("Notes cursor active key is required");
        }
        var active = decode(properties.active());
        var previous = properties.previous().stream().map(NotesCursorConfiguration::decode).toList();
        var snapshot = new CursorKeyRing.KeySnapshot(active, previous);
        return () -> snapshot;
    }

    @Bean
    OpaqueCursorCodec notesCursorCodec(Clock clock, CursorKeyRing keyRing) {
        return new OpaqueCursorCodec(clock, keyRing);
    }

    private static CursorKeyRing.CursorKey decode(NotesCursorProperties.ConfiguredKey definition) {
        if (definition == null || definition.keyBase64() == null
                || definition.keyBase64().isBlank()) {
            throw new IllegalArgumentException("Invalid Notes cursor key configuration");
        }
        try {
            return new CursorKeyRing.CursorKey(definition.version(),
                    Base64.getDecoder().decode(definition.keyBase64()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Notes cursor key configuration");
        }
    }
}
