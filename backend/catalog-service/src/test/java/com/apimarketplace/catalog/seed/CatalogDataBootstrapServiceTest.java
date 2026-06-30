package com.apimarketplace.catalog.seed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogDataBootstrapService")
class CatalogDataBootstrapServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ResourceLoader resourceLoader;

    private CatalogDataBootstrapService service;

    @BeforeEach
    void setUp() {
        CatalogSeedConfig config = new CatalogSeedConfig();
        config.setPath("unused");
        service = new CatalogDataBootstrapService(config, jdbcTemplate, resourceLoader);
    }

    @Test
    @DisplayName("Deletes imported tool responses by tool_id because catalog.tool_responses has no api_tool_id")
    void deletesImportedToolResponsesByToolId() {
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*)::int FROM catalog.apis"), eq(Integer.class)))
                .thenReturn(0);

        ReflectionTestUtils.invokeMethod(
                service,
                "executeSqlDump",
                new ByteArrayInputStream(gzipSql("-- empty dump\n"))
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sqlCaptor.capture());

        List<String> statements = sqlCaptor.getAllValues();
        assertThat(statements)
                .anySatisfy(sql -> assertThat(sql)
                        .contains("DELETE FROM catalog.tool_responses WHERE tool_id IN")
                        .doesNotContain("api_tool_id"));
    }

    @Test
    @DisplayName("Deletes imported credential templates by platform credential name because catalog.credentials has no api_id")
    void deletesImportedCredentialsByPlatformCredentialName() {
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*)::int FROM catalog.apis"), eq(Integer.class)))
                .thenReturn(0);

        ReflectionTestUtils.invokeMethod(
                service,
                "executeSqlDump",
                new ByteArrayInputStream(gzipSql("-- empty dump\n"))
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sqlCaptor.capture());

        List<String> statements = sqlCaptor.getAllValues();
        assertThat(statements)
                .anySatisfy(sql -> assertThat(sql)
                        .contains("DELETE FROM catalog.credentials c WHERE")
                        .contains("metadata ->> 'source' = 'api-migration'")
                        .contains("a.platform_credential_name = c.credential_name")
                        .doesNotContain("credentials WHERE api_id"));
    }

    @Test
    @DisplayName("Does not delete api_subcategories by api_id because subcategories are shared reference data")
    void doesNotDeleteApiSubcategoriesByApiId() {
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*)::int FROM catalog.apis"), eq(Integer.class)))
                .thenReturn(0);

        ReflectionTestUtils.invokeMethod(
                service,
                "executeSqlDump",
                new ByteArrayInputStream(gzipSql("-- empty dump\n"))
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sqlCaptor.capture());

        assertThat(sqlCaptor.getAllValues())
                .noneSatisfy(sql -> assertThat(sql).contains("catalog.api_subcategories WHERE api_id"));
    }

    @Test
    @DisplayName("Rebuilds the lexical search index for imported tools after the dump loads "
            + "(catalog-data.sql.gz omits lexical_search_index, so without this the agent tool-search finds nothing)")
    void rebuildsLexicalSearchIndexForImportedTools() {
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*)::int FROM catalog.apis"), eq(Integer.class)))
                .thenReturn(0);

        ReflectionTestUtils.invokeMethod(
                service,
                "executeSqlDump",
                new ByteArrayInputStream(gzipSql("-- empty dump\n"))
        );

        // The reindex is the only jdbcTemplate.update(String) call in the bootstrap path
        // (dump statements + repair use execute()); assert it inserts derived lexical rows
        // from the loaded catalog tables for import-sourced tools only, idempotently.
        ArgumentCaptor<String> updateCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).update(updateCaptor.capture());

        assertThat(updateCaptor.getAllValues())
                .anySatisfy(sql -> assertThat(sql)
                        .contains("INSERT INTO catalog.lexical_search_index")
                        .contains("FROM catalog.api_tools")
                        .contains("WHERE a.source = 'import'")
                        .contains("NOT EXISTS"));
    }

    @Test
    @DisplayName("Skips pg_dump psql meta commands so \\restrict is not concatenated into SQL")
    void skipsPgDumpPsqlMetaCommands() {
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*)::int FROM catalog.apis"), eq(Integer.class)))
                .thenReturn(0);

        ReflectionTestUtils.invokeMethod(
                service,
                "executeSqlDump",
                new ByteArrayInputStream(gzipSql("""
                        \\restrict token
                        SET statement_timeout = 0;
                        \\unrestrict token
                        """))
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sqlCaptor.capture());

        assertThat(sqlCaptor.getAllValues())
                .noneSatisfy(sql -> assertThat(sql).contains("\\restrict"))
                .noneSatisfy(sql -> assertThat(sql).contains("\\unrestrict"));
    }

    @Test
    @DisplayName("Drops legacy credential_mode from catalog.apis COPY blocks because V154 removed that column")
    void adaptsLegacyApisCredentialModeCopyBlock() {
        String copyStatement = "COPY catalog.apis (id, api_name, credential_mode, platform_credential_name, version) FROM stdin";
        String copyData = """
                11111111-1111-1111-1111-111111111111\tAlpha\tuser_key\talpha\t1.0.0
                22222222-2222-2222-2222-222222222222\tBeta\tboth\t\\N\t1.0.0""";

        CatalogDataBootstrapService.CopyBlock copyBlock = service.adaptLegacyCopyBlock(copyStatement, copyData);

        assertThat(copyBlock.statement())
                .isEqualTo("COPY catalog.apis (id, api_name, platform_credential_name, version) FROM stdin");
        assertThat(copyBlock.data()).isEqualTo("""
                11111111-1111-1111-1111-111111111111\tAlpha\talpha\t1.0.0
                22222222-2222-2222-2222-222222222222\tBeta\t\\N\t1.0.0""");
    }

    @Test
    @DisplayName("Does not guess missing platform_credential_name from icon_slug because legacy icon slugs are shared")
    void doesNotGuessMissingPlatformCredentialNameFromIconSlug() {
        String copyStatement = "COPY catalog.apis (id, api_name, icon_slug, credential_mode, platform_credential_name, version) FROM stdin";
        String copyData = """
                11111111-1111-1111-1111-111111111111\tTwitter / X\tx\tuser_key\t\\N\t1.0.0
                22222222-2222-2222-2222-222222222222\txAI\tx\tboth\t\\N\t1.0.0""";

        CatalogDataBootstrapService.CopyBlock copyBlock = service.adaptLegacyCopyBlock(copyStatement, copyData);

        assertThat(copyBlock.statement())
                .isEqualTo("COPY catalog.apis (id, api_name, icon_slug, platform_credential_name, version) FROM stdin");
        assertThat(copyBlock.data()).isEqualTo("""
                11111111-1111-1111-1111-111111111111\tTwitter / X\tx\t\\N\t1.0.0
                22222222-2222-2222-2222-222222222222\txAI\tx\t\\N\t1.0.0""");
    }

    @Test
    @DisplayName("Repairs imported platform credential names from per-API tool credentials after loading the dump")
    void repairsImportedPlatformCredentialNamesFromPerApiToolCredentials() {
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*)::int FROM catalog.apis"), eq(Integer.class)))
                .thenReturn(0);

        ReflectionTestUtils.invokeMethod(
                service,
                "executeSqlDump",
                new ByteArrayInputStream(gzipSql("-- empty dump\n"))
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sqlCaptor.capture());

        assertThat(sqlCaptor.getAllValues())
                .anySatisfy(sql -> assertThat(sql)
                        .contains("JOIN catalog.tool_credentials tc ON tc.api_tool_id = at.id")
                        .contains("FROM catalog.credentials c")
                        .contains("HAVING COUNT(DISTINCT tc.credential_name) = 1")
                        .contains("HAVING COUNT(DISTINCT c.credential_name) = 1")
                        .contains("a.platform_credential_name IS DISTINCT FROM rc.credential_name"));
    }

    @Test
    @DisplayName("Filters legacy custom credential rows because migrations own built-in custom credential templates")
    void filtersLegacyCustomCredentialRows() {
        String copyStatement = "COPY catalog.credentials (id, credential_name, display_name, metadata) FROM stdin";
        String copyData = """
                11111111-1111-1111-1111-111111111111\tactivecampaign\tActiveCampaign\t{"source": "api-migration"}
                a1b2c3d4-e5f6-7890-abcd-000000000001\tsmtp\tSMTP Email\t{"category": "email", "provider": "smtp"}""";

        CatalogDataBootstrapService.CopyBlock copyBlock = service.adaptLegacyCopyBlock(copyStatement, copyData);

        assertThat(copyBlock.data()).isEqualTo(
                "11111111-1111-1111-1111-111111111111\tactivecampaign\tActiveCampaign\t{\"source\": \"api-migration\"}");
    }

    @Test
    @DisplayName("Deduplicates legacy tool credential rows that predate the variant uniqueness contract")
    void deduplicatesLegacyToolCredentialRowsWithoutVariant() {
        String copyStatement = "COPY catalog.tool_credentials (id, api_tool_id, credential_id, credential_name, metadata) FROM stdin";
        String copyData = """
                11111111-1111-1111-1111-111111111111\ttool-1\tcred-1\tagora\t{"field": "username"}
                22222222-2222-2222-2222-222222222222\ttool-1\tcred-1\tagora\t{"field": "password"}
                33333333-3333-3333-3333-333333333333\ttool-2\tcred-1\tagora\t{"field": "username"}""";

        CatalogDataBootstrapService.CopyBlock copyBlock = service.adaptLegacyCopyBlock(copyStatement, copyData);

        assertThat(copyBlock.data()).isEqualTo("""
                11111111-1111-1111-1111-111111111111\ttool-1\tcred-1\tagora\t{"field": "username"}
                33333333-3333-3333-3333-333333333333\ttool-2\tcred-1\tagora\t{"field": "username"}""");
    }

    private byte[] gzipSql(String sql) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(sql.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to gzip test SQL", e);
        }
    }
}
