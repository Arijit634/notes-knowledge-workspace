package org.notesknowledge;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fail startup rather than enable idle mode with a pool that keeps waking PostgreSQL. */
@Component
@org.springframework.scheduling.annotation.EnableScheduling
@ConditionalOnProperty(name="operations.database-idle.enabled",havingValue="true")
final class DatabaseIdleConfiguration {
    DatabaseIdleConfiguration(HikariDataSource pool){
        if(pool.getMinimumIdle()!=0||pool.getKeepaliveTime()!=0||pool.getIdleTimeout()<10000||pool.getIdleTimeout()>120000)
            throw new IllegalStateException("Database idle mode requires an explicitly quiescent pool");
    }
}
