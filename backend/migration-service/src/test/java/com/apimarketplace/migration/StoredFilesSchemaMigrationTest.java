package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

@DisplayName("Stored files schema migration invariants")
class StoredFilesSchemaMigrationTest {

    @Test
    @DisplayName("V294 moves legacy public stored_files into storage schema for Hibernate validation")
    void v294MovesLegacyPublicStoredFilesIntoStorageSchema(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeLegacyStorageFixture(tempDir);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "stored_files_schema");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "stored_files_schema", tempDir))
                    .doesNotThrowAnyException();

            assertThat(tableExists(postgres, "stored_files_schema", "storage", "stored_files")).isTrue();
            assertThat(tableExists(postgres, "stored_files_schema", "public", "stored_files")).isFalse();
            assertThat(columnDataType(postgres, "stored_files_schema", "storage", "stored_files", "organization_id"))
                    .isEqualTo("character varying");
            assertThat(columnIsNullable(postgres, "stored_files_schema", "storage", "stored_files", "organization_id"))
                    .isEqualTo("NO");
            assertThat(fileStatisticsCount(postgres, "stored_files_schema")).isEqualTo(1);
        }
    }

    private static void writeLegacyStorageFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__legacy_public_stored_files.sql"), """
                CREATE SCHEMA storage;
                CREATE SCHEMA auth;

                CREATE TABLE auth.organization_member (
                    user_id BIGINT NOT NULL,
                    organization_id UUID NOT NULL,
                    is_default BOOLEAN NOT NULL
                );

                CREATE TABLE public.stored_files (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    organization_id UUID NOT NULL,
                    file_name VARCHAR(255) NOT NULL,
                    original_name VARCHAR(255) NOT NULL,
                    content_type VARCHAR(100) NOT NULL,
                    file_size BIGINT NOT NULL,
                    file_path VARCHAR(500) NOT NULL,
                    storage_provider VARCHAR(100),
                    storage_key VARCHAR(500),
                    is_public BOOLEAN DEFAULT FALSE,
                    description TEXT,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_accessed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                INSERT INTO auth.organization_member (user_id, organization_id, is_default)
                VALUES (1, '00000000-0000-0000-0000-000000000001', true);

                INSERT INTO public.stored_files (
                    user_id,
                    organization_id,
                    file_name,
                    original_name,
                    content_type,
                    file_size,
                    file_path
                ) VALUES (
                    1,
                    '00000000-0000-0000-0000-000000000001',
                    'example.txt',
                    'example.txt',
                    'text/plain',
                    42,
                    '/uploads/example.txt'
                );

                CREATE OR REPLACE VIEW public.file_statistics AS
                SELECT user_id,
                       COUNT(*) AS file_count,
                       SUM(file_size) AS total_size,
                       AVG(file_size) AS average_file_size,
                       MIN(created_at) AS first_file_date,
                       MAX(created_at) AS last_file_date
                  FROM public.stored_files
                 GROUP BY user_id;
                """);

        String productionV294 = Files.readString(Path.of(
                "src/main/resources/db/migration/V294__move_legacy_stored_files_to_storage_schema.sql"));
        Files.writeString(directory.resolve("V2__move_legacy_stored_files_to_storage_schema.sql"), productionV294);
    }

    private static boolean tableExists(
            PostgreSQLContainer<?> postgres,
            String databaseName,
            String schemaName,
            String tableName) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1
                           FROM information_schema.tables
                          WHERE table_schema = ?
                            AND table_name = ?
                     )
                     """)) {
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static String columnDataType(
            PostgreSQLContainer<?> postgres,
            String databaseName,
            String schemaName,
            String tableName,
            String columnName) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT data_type
                       FROM information_schema.columns
                      WHERE table_schema = ?
                        AND table_name = ?
                        AND column_name = ?
                     """)) {
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static String columnIsNullable(
            PostgreSQLContainer<?> postgres,
            String databaseName,
            String schemaName,
            String tableName,
            String columnName) throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT is_nullable
                       FROM information_schema.columns
                      WHERE table_schema = ?
                        AND table_name = ?
                        AND column_name = ?
                     """)) {
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            statement.setString(3, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static long fileStatisticsCount(PostgreSQLContainer<?> postgres, String databaseName)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT file_count FROM public.file_statistics WHERE user_id = 1")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
