package com.apimarketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Datasource pgvector migration invariants")
class PgvectorDatasourceMigrationTest {

    private static final Path V74 = Path.of(
            "src/main/resources/db/migration/V74__enable_pgvector_datasource.sql");
    private static final Path V74_1 = Path.of(
            "src/main/resources/db/migration/V74_1__move_pgvector_to_datasource_schema.sql");
    private static final Path V75 = Path.of(
            "src/main/resources/db/migration/V75__create_data_source_vectors.sql");
    private static final Path MIGRATION_SERVICE_APPLICATION = Path.of(
            "src/main/resources/application.yml");
    private static final Path MONOLITH_CE_APPLICATION = Path.of(
            "../monolith-service/src/main/resources/application-ce.yml");

    @Test
    @DisplayName("movesExistingPgvectorExtensionBeforeDatasourceVectorTableCreation")
    void movesExistingPgvectorExtensionBeforeDatasourceVectorTableCreation() throws Exception {
        String v74 = Files.readString(V74);
        String v741 = Files.readString(V74_1);
        String v75 = Files.readString(V75);

        assertThat(v74).contains("CREATE EXTENSION IF NOT EXISTS vector SCHEMA datasource");
        assertThat(v741).contains("ALTER EXTENSION vector SET SCHEMA datasource");
        assertThat(v75).contains("SET search_path TO datasource");
        assertThat(v75).contains("embedding       vector NOT NULL");
    }

    @Test
    @DisplayName("enablesOutOfOrderForLatePgvectorBackfill")
    void enablesOutOfOrderForLatePgvectorBackfill() throws Exception {
        String migrationServiceApplication = Files.readString(MIGRATION_SERVICE_APPLICATION);
        String monolithCeApplication = Files.readString(MONOLITH_CE_APPLICATION);

        assertThat(migrationServiceApplication)
                .contains("V74.1 is a historical backfill")
                .contains("out-of-order: true");
        assertThat(monolithCeApplication)
                .contains("V74.1 is a historical backfill")
                .contains("out-of-order: true");
    }
}
