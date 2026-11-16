package org.notesknowledge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.zaxxer.hikari.HikariDataSource;
import static org.assertj.core.api.Assertions.*;

@Tag("FAST") @Tag("SECURITY")
class DatabaseIdleConfigurationTest {
    @Test void connectionKeepaliveOrFixedIdleConnectionsCannotSilentlyDefeatQuiescence(){
        try(var pool=new HikariDataSource()){
            pool.setMinimumIdle(0);pool.setKeepaliveTime(0);pool.setIdleTimeout(60000);
            assertThatCode(()->new DatabaseIdleConfiguration(pool)).doesNotThrowAnyException();
            pool.setMinimumIdle(1);assertThatThrownBy(()->new DatabaseIdleConfiguration(pool)).isInstanceOf(IllegalStateException.class);
            pool.setMinimumIdle(0);pool.setKeepaliveTime(120000);assertThatThrownBy(()->new DatabaseIdleConfiguration(pool)).isInstanceOf(IllegalStateException.class);
        }
    }
}
