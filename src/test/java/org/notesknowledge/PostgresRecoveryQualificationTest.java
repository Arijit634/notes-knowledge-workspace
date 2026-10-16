package org.notesknowledge;

import static org.assertj.core.api.Assertions.*;
import com.zaxxer.hikari.*;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Disposable real PostgreSQL only. These are coordination/connection recovery
 * characterizations, not provider atomic-egress or production failover claims. */
@Tag("DATABASE") @Tag("SECURITY")
class PostgresRecoveryQualificationTest {
    @Test void actualDatabaseRestartPoisonsOldCoordinationAndRequiresFreshSession()throws Exception {
        try(var postgres=database()){
            postgres.start();
            try(var pool=pool(postgres,postgres.getUsername(),postgres.getPassword())){
                var beans=new DefaultListableBeanFactory();beans.registerSingleton("database",pool);
                try(var coordinator=new DispatchCoordinator(beans.getBeanProvider(javax.sql.DataSource.class))){
                    UUID owner=id(pool),note=id(pool);var old=coordinator.dispatch(owner,note);old.requireDispatchScope(owner,note);
                    DockerClientFactory.instance().client().restartContainerCmd(postgres.getContainerId()).exec();
                    Wait.forSuccessfulCommand("pg_isready -U synthetic_migrator -d recovery_qualification")
                        .withStartupTimeout(Duration.ofSeconds(60)).waitUntilReady(postgres);
                    assertThatThrownBy(()->old.requireDispatchScope(owner,note)).isInstanceOf(ApiFailureException.class).hasNoCause();
                    old.close();assertThat(old.safelyReleased()).isFalse();assertThat(old.state().name()).isEqualTo("POISONED");
                    // Supported planned-recovery boundary recreates coordinator pools;
                    // a stale pool's first acquisition is allowed to report unavailable.
                    coordinator.close();
                    var inspection=DockerClientFactory.instance().client().inspectContainerCmd(postgres.getContainerId()).exec();
                    int mapped=Integer.parseInt(inspection.getNetworkSettings().getPorts().getBindings()
                        .get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec());
                    System.out.println("PostgreSQL restart: ephemeral test mapping changed="+(mapped!=postgres.getMappedPort(5432)));
                    String refreshed=postgres.getJdbcUrl().replaceFirst(":[0-9]+/recovery_qualification",":"+mapped+"/recovery_qualification");
                    try(var restartedPool=pool(refreshed,postgres.getUsername(),postgres.getPassword())){
                        assertThat(id(restartedPool).version()).isEqualTo(7);
                        var restartedBeans=new DefaultListableBeanFactory();restartedBeans.registerSingleton("database",restartedPool);
                        try(var restarted=new DispatchCoordinator(restartedBeans.getBeanProvider(javax.sql.DataSource.class));var fresh=restarted.dispatch(owner,note)){
                            fresh.requireDispatchScope(owner,note);
                        }
                    }
                }
            }
        }
    }
    @Test void passwordRotationDoesNotRevokeOpenConnectionsButOldCredentialCannotReconnect()throws Exception {
        try(var postgres=database()){
            postgres.start();
            try(var admin=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var sql=admin.createStatement()){
                sql.execute("create role synthetic_rotation login password 'synthetic-first-password'");
                try(var oldPool=pool(postgres,"synthetic_rotation","synthetic-first-password");var active=oldPool.getConnection()){
                    sql.execute("alter role synthetic_rotation password 'synthetic-second-password'");
                    try(var probe=active.createStatement();var result=probe.executeQuery("select uuidv7()")){assertThat(result.next()).isTrue();}
                    // Credential changes alone do not cancel already-dispatched effects.
                    assertThatThrownBy(()->DriverManager.getConnection(postgres.getJdbcUrl(),"synthetic_rotation","synthetic-first-password"))
                        .isInstanceOf(SQLException.class).satisfies(failure->assertThat(((SQLException)failure).getSQLState()).isEqualTo("28P01"));
                    oldPool.getHikariPoolMXBean().softEvictConnections();active.close();
                    assertThatThrownBy(oldPool::getConnection).isInstanceOf(SQLException.class);
                }
                try(var restarted=pool(postgres,"synthetic_rotation","synthetic-second-password")){
                    assertThat(id(restarted).version()).isEqualTo(7);
                }
            }
        }
    }
    private static PostgreSQLContainer database(){return new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("recovery_qualification").withUsername("synthetic_migrator").withPassword("synthetic-recovery-password");}
    private static HikariDataSource pool(PostgreSQLContainer postgres,String user,String password){
        return pool(postgres.getJdbcUrl(),user,password);
    }
    private static HikariDataSource pool(String url,String user,String password){
        var configuration=new HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername(user);configuration.setPassword(password);
        configuration.setMaximumPoolSize(2);configuration.setMinimumIdle(0);configuration.setConnectionTimeout(5000);configuration.setValidationTimeout(1000);
        return new HikariDataSource(configuration);
    }
    private static UUID id(javax.sql.DataSource pool)throws SQLException {
        try(var connection=pool.getConnection();var sql=connection.createStatement();var result=sql.executeQuery("select uuidv7()")){
            assertThat(result.next()).isTrue();return result.getObject(1,UUID.class);
        }
    }
}
