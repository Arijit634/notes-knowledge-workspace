package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION") @Testcontainers
class QualificationProcessRestartTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("synthetic_process_restart").withUsername("synthetic_migrator").withPassword("synthetic-restart-password");
    @TempDir Path directory;
    @Test void actualAbruptApplicationDeathResumesWithoutLosingRootsOrRefundingCalls()throws Exception {
        assertThat(run("interrupt")).as(Files.readString(directory.resolve("interrupt-safe.txt"))).isEqualTo(77);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where state='ready'",Integer.class)).isEqualTo(75);
        // Deterministically simulate the passage of the lease interval; no sleeps or production-policy changes.
        jdbc.update("update knowledge.knowledge_work_intent set lease_until=clock_timestamp()-interval '1 second' where state='claimed'");
        assertThat(run("resume")).isZero();assertThat(run("verify")).isZero();
        var json=new ObjectMapper();var resumed=json.readTree(Files.readAllBytes(directory.resolve("resume.json")));var verified=json.readTree(Files.readAllBytes(directory.resolve("verify.json")));
        assertThat(resumed.get("reusedReadyRoots").asInt()).isEqualTo(75);assertThat(verified.get("reusedReadyRoots").asInt()).isEqualTo(80);
        assertThat(verified.get("newFakeCalls").asInt()).isZero();assertThat(verified.get("liveCalls").asInt()).isZero();
        Path report=Path.of("target/retrieval-evaluation/actual-process-recovery.json");Files.createDirectories(report.getParent());
        Files.writeString(report,json.writeValueAsString(java.util.Map.of("abruptExit",77,"resumed",resumed,"restartedAgain",verified,"fixtureLeaseExpiry",true)));
    }
    private int run(String mode)throws Exception {
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Duser.timezone=UTC","-cp",System.getProperty("java.class.path"),
            QualificationProcessHarness.class.getName(),postgres.getJdbcUrl(),directory.toString(),mode)
            .redirectOutput(directory.resolve(mode+"-safe.txt").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key->key.startsWith("SPRING_AI_")||key.startsWith("SPRING_CONFIG_")||key.startsWith("GOOGLE_OIDC_")||key.startsWith("SPRING_MAIL_"));
        var child=builder.start();if(!child.waitFor(90,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Offline child did not terminate");}return child.exitValue();
    }
}
