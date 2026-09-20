-- Adapted only for the approved identity schema and physical naming from
-- org.springframework.session:spring-session-jdbc:4.1.1
-- org/springframework/session/jdbc/schema-postgresql.sql
-- SHA-256: d5333e408d24d210020c3a60d5e1e3f4b52e858b904d47d00b462dde20c24954

CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1
    ON identity.spring_session (session_id);
CREATE INDEX spring_session_ix2
    ON identity.spring_session (expiry_time);
CREATE INDEX spring_session_ix3
    ON identity.spring_session (principal_name);

CREATE TABLE identity.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk
        PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk
        FOREIGN KEY (session_primary_id)
        REFERENCES identity.spring_session (primary_id)
        ON DELETE CASCADE
);
