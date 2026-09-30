package org.notesknowledge.notes;

import java.util.Map;
import java.util.Set;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/note-preferences")
final class NotePreferencesController {
    record Preferences(boolean defaultAiEnabledForNewNotes) { }

    private final NotesService notes;

    NotePreferencesController(NotesService notes) { this.notes = notes; }

    @GetMapping
    ResponseEntity<Preferences> get() {
        boolean enabled = notes.preference(NotesActor.owner());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Preferences(enabled));
    }

    @PutMapping
    ResponseEntity<Preferences> put(@RequestBody Map<String, Object> input) {
        if (input == null || !input.keySet().equals(Set.of("defaultAiEnabledForNewNotes"))
                || !(input.get("defaultAiEnabledForNewNotes") instanceof Boolean enabled)) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        notes.setPreference(NotesActor.owner(), enabled);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Preferences(enabled));
    }
}
