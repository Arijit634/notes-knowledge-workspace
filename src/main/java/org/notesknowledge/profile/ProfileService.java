package org.notesknowledge.profile;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProfileService {
    private final JpaProfileRepositoryAdapter repository;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;

    ProfileService(JpaProfileRepositoryAdapter repository, DatabaseUuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    ProfileView read(UUID owner) {
        return repository.find(owner).map(Profile::view).orElseGet(ProfileView::absent);
    }

    @Transactional
    ProfileView replace(UUID owner, String name, String biography, String handle) {
        Profile profile = new Profile(ids.generate(), owner, name, biography, handle,
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
        repository.replace(profile);
        return repository.find(owner).orElseThrow().view();
    }
}
