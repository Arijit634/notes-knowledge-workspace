package org.notesknowledge.knowledge;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProcessingPolicyRepository {
    record Policy(UUID id,String code,long version,String fingerprint,String disclosureRevision,Instant effectiveAt) {
        @Override public String toString() { return "ProcessingPolicyIdentity[REDACTED]"; }
    }
    private final ObjectProvider<JdbcClient> clients;
    ProcessingPolicyRepository(ObjectProvider<JdbcClient> clients) { this.clients=clients; }
    Optional<Policy> current(String code) {
        var jdbc=clients.getObject();
        jdbc.sql("select pg_advisory_xact_lock_shared(hashtextextended(:code,719))").param("code",code).query(Object.class).single();
        return jdbc.sql("""
                select processing_policy_id,policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at
                from knowledge.processing_policy where policy_code=:code and effective_at<=clock_timestamp()
                and (retired_at is null or retired_at>clock_timestamp()) order by policy_version desc limit 1
                """).param("code",code).query((r,i)->new Policy(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),
                    r.getString(4),r.getString(5),r.getTimestamp(6).toInstant())).optional();
    }
    boolean acknowledged(UUID user,Policy policy) {
        return clients.getObject().sql("""
                select exists(select 1 from knowledge.processing_policy_acknowledgement
                    where user_id=:user and processing_policy_id=:policy and disclosure_revision=:revision)
                """).param("user",user).param("policy",policy.id()).param("revision",policy.disclosureRevision()).query(Boolean.class).single();
    }
    void acknowledge(UUID user,Policy policy) {
        clients.getObject().sql("""
                insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision)
                values(:user,:policy,:revision) on conflict(user_id,processing_policy_id) do nothing
                """).param("user",user).param("policy",policy.id()).param("revision",policy.disclosureRevision()).update();
    }
}
