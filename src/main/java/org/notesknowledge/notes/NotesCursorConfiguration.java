package org.notesknowledge.notes;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

import org.notesknowledge.websupport.CursorKeyRing;
import org.notesknowledge.websupport.OpaqueCursorCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class NotesCursorConfiguration {
    @Bean
    OpaqueCursorCodec notesCursorCodec(Clock clock,
            @Value("${notes.cursor.key-base64:}") String configuredKey) {
        byte[] material;
        if (configuredKey.isBlank()) {
            // Local single-process cursors expire on restart. A deployment can supply a stable key.
            material = new byte[32];
            new SecureRandom().nextBytes(material);
        } else {
            try {
                material = Base64.getDecoder().decode(configuredKey);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Notes cursor key is malformed");
            }
        }
        CursorKeyRing.CursorKey active = new CursorKeyRing.CursorKey("n1", material);
        CursorKeyRing ring = () -> new CursorKeyRing.KeySnapshot(active, List.of());
        return new OpaqueCursorCodec(clock, ring);
    }
}
