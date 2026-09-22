package org.notesknowledge;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Obtains a PostgreSQL-native identifier on the caller's normal datasource/transaction. */
@Component
public final class DatabaseUuidV7Generator {

    private final ObjectProvider<JdbcClient> jdbc;

    public DatabaseUuidV7Generator(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    public UUID generate() {
        return jdbc.getObject().sql("select uuidv7()")
                .query(UUID.class)
                .single();
    }
}
