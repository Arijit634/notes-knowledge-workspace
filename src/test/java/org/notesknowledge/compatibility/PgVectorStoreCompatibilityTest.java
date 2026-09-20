package org.notesknowledge.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
class PgVectorStoreCompatibilityTest {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("vector_compatibility")
            .withUsername("compatibility")
            .withPassword("compatibility");

    JdbcTemplate jdbc;

    @BeforeEach
    void createCompatibilityOnlyShape() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create extension if not exists vector");
        jdbc.execute("create schema compatibility");
        jdbc.execute("""
                create table compatibility.vector_store (
                    id uuid primary key,
                    content text,
                    metadata json,
                    embedding vector(3)
                )
                """);
    }

    @AfterEach
    void removeCompatibilityOnlyShape() {
        jdbc.execute("drop schema compatibility cascade");
    }

    @Test
    void performsSyntheticRoundTripWithoutAutomaticSchemaMutation() {
        EmbeddingModel embeddings = embeddingModel(new float[] {1.0f, 0.0f, 0.0f});
        PgVectorStore store = store(embeddings);

        store.add(List.of(new Document("synthetic compatibility text")));

        List<Document> results = store.similaritySearch(SearchRequest.builder()
                .query("synthetic query")
                .topK(1)
                .build());

        assertThat(results).singleElement()
                .extracting(Document::getText)
                .isEqualTo("synthetic compatibility text");
    }

    @Test
    void rejectsWrongVectorDimensionVisibly() {
        PgVectorStore store = store(embeddingModel(new float[] {1.0f, 0.0f}));

        assertThatThrownBy(() -> store.add(List.of(new Document("wrong dimension"))))
                .hasMessageContaining("expected 3 dimensions, not 2");
    }

    private PgVectorStore store(EmbeddingModel embeddingModel) {
        PgVectorStore store = PgVectorStore.builder(jdbc, embeddingModel)
                .schemaName("compatibility")
                .vectorTableName("vector_store")
                .dimensions(3)
                .indexType(PgIndexType.NONE)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .build();
        store.afterPropertiesSet();
        return store;
    }

    @SuppressWarnings("unchecked")
    private EmbeddingModel embeddingModel(float[] vector) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.dimensions()).thenReturn(vector.length);
        when(model.embed(anyString())).thenReturn(vector);
        when(model.embed(anyList(), any(), any())).thenAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            return documents.stream().map(ignored -> vector).toList();
        });
        return model;
    }
}
