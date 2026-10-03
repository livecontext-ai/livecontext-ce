package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.seed.CatalogSeedCredentialService;
import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The credential-key ownership decisions (LC-002 / LC-057, CASA readiness) executed against a real
 * Postgres, with the SHIPPED query text read off the {@code @Query} annotations and the shipped
 * upsert/delete statements of {@link CatalogSeedCredentialService}. Nothing that decides the
 * outcome is stubbed.
 *
 * <p>The schema is a minimal stand-in holding only the columns these statements read. The
 * decisive case is the native template: {@code imap} is seeded by a migration with no
 * {@code catalog.apis} row, so the previous predicate ("exempt when one of the owner's custom
 * APIs carries the key") called it the squatter's own, let {@code delete_api} remove it for the
 * whole installation and kept the platform fallback at execution.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Credential key ownership - the shipped SQL, against a real Postgres")
class CredentialKeyOwnershipPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CATALOG_TEST_PG",
            "it is the only executable proof of which credential templates a custom API may claim or delete");

    private static final String SCRATCH_DB = "credential_key_test_" + ProcessHandle.current().pid();
    private static final String OWNER = "user-A";
    private static final String OTHER = "user-B";

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;

    @BeforeAll
    void createDatabase() {
        DB.require();
        JdbcTemplate admin = new JdbcTemplate(dataSource(DB.url()));
        Integer existing = admin.queryForObject(
                "SELECT count(*) FROM pg_database WHERE datname = ?", Integer.class, SCRATCH_DB);
        if (existing == null || existing == 0) {
            admin.execute("CREATE DATABASE " + SCRATCH_DB);
        }
        DriverManagerDataSource ds = dataSource(withDatabase(DB.url(), SCRATCH_DB));
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);
    }

    @BeforeEach
    void schema() {
        jdbc.execute("DROP SCHEMA IF EXISTS catalog CASCADE");
        jdbc.execute("CREATE SCHEMA catalog");
        jdbc.execute("""
                CREATE TABLE catalog.apis (
                    id UUID PRIMARY KEY, created_by VARCHAR(255) NOT NULL, source VARCHAR(50) DEFAULT 'import',
                    platform_credential_name VARCHAR(200), icon_slug VARCHAR(100), base_url TEXT)""");
        jdbc.execute("CREATE TABLE catalog.api_tools (id UUID PRIMARY KEY, api_id UUID NOT NULL)");
        jdbc.execute("""
                CREATE TABLE catalog.credentials (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), credential_name VARCHAR(200) NOT NULL,
                    variant VARCHAR(50) NOT NULL DEFAULT 'primary', display_name VARCHAR(255),
                    credential_type VARCHAR(100), auth_type VARCHAR(50), properties JSONB DEFAULT '{}'::jsonb,
                    icon_slug VARCHAR(100), icon_url VARCHAR(500), metadata JSONB DEFAULT '{}'::jsonb,
                    created_at BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0,
                    UNIQUE (credential_name, variant))""");
        jdbc.execute("""
                CREATE TABLE catalog.tool_credentials (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), api_tool_id UUID NOT NULL,
                    credential_id UUID, credential_name VARCHAR(200) NOT NULL,
                    variant VARCHAR(50) NOT NULL DEFAULT 'primary', is_required BOOLEAN DEFAULT TRUE,
                    usage VARCHAR(50), metadata JSONB, created_at BIGINT DEFAULT 0, updated_at BIGINT DEFAULT 0,
                    UNIQUE (api_tool_id, credential_name, variant))""");
    }

    // ---------------------------------------------------------------- fixtures

    private UUID api(String createdBy, String source, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.apis (id, created_by, source, platform_credential_name, icon_slug) "
                + "VALUES (?, ?, ?, ?, ?)", id, createdBy, source, key, key);
        return id;
    }

    private UUID tool(UUID apiId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.api_tools (id, api_id) VALUES (?, ?)", id, apiId);
        return id;
    }

    /** The native template exactly as V322 seeds it: credential_type set, provider/category markers. */
    private void nativeImapTemplate() {
        jdbc.update("INSERT INTO catalog.credentials (credential_name, variant, credential_type, auth_type, metadata) "
                + "VALUES ('imap', 'primary', 'imap', 'custom', '{\"provider\": \"imap\", \"category\": \"email\"}'::jsonb)");
    }

    private void customTemplate(String name, String metadataJson) {
        jdbc.update("INSERT INTO catalog.credentials (credential_name, variant, auth_type, metadata) "
                + "VALUES (?, 'primary', 'bearer_token', ?::jsonb)", name, metadataJson);
    }

    private void link(UUID toolId, String name) {
        jdbc.update("INSERT INTO catalog.tool_credentials (api_tool_id, credential_name) VALUES (?, ?)", toolId, name);
    }

    private boolean shared(String key, String owner) throws Exception {
        String sql = ApiRepository.class.getMethod("existsSharedIntegrationWithCredentialKey", String.class, String.class)
                .getAnnotation(Query.class).value();
        return Boolean.TRUE.equals(named.queryForObject(sql,
                new MapSqlParameterSource().addValue("key", key).addValue("ownerId", owner), Boolean.class));
    }

    private boolean identityFree(String key) throws Exception {
        String sql = ApiRepository.class.getMethod("existsShippedIntegrationWithCredentialKey", String.class)
                .getAnnotation(Query.class).value();
        return Boolean.TRUE.equals(named.queryForObject(sql,
                new MapSqlParameterSource().addValue("key", key), Boolean.class));
    }

    private CatalogSeedCredentialService seedService(UUID apiId, UUID toolId) {
        ApiToolRepository tools = mock(ApiToolRepository.class);
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(tools.findByApiId(apiId)).thenReturn(List.of(tool));
        return new CatalogSeedCredentialService(jdbc, tools);
    }

    private String imapMetadata() {
        return jdbc.queryForObject("SELECT metadata::text FROM catalog.credentials WHERE credential_name = 'imap'",
                String.class);
    }

    // ---------------------------------------------------------------- ownership predicate

    @Test
    @DisplayName("regression: a custom API named 'imap' does NOT make the native imap template its own")
    void nativeTemplateIsForeignEvenToTheSquatter() throws Exception {
        nativeImapTemplate();
        api(OWNER, "custom", "imap"); // authType none: carries the key, links nothing

        assertThat(shared("imap", OWNER))
                .as("pre-fix the owner's own custom API carrying the key exempted the native template")
                .isTrue();
        assertThat(identityFree("imap")).isTrue();
    }

    @Test
    @DisplayName("regression: the native template stays foreign even when the squatter linked its tools to it")
    void nativeTemplateLinkedBySquatterIsStillForeign() throws Exception {
        nativeImapTemplate();
        UUID squat = api(OWNER, "custom", "imap");
        link(tool(squat), "imap");

        assertThat(shared("imap", OWNER)).isTrue();
    }

    @Test
    @DisplayName("a shipped integration's key is foreign, by created_by and whatever the row's source")
    void shippedKeyIsForeign() throws Exception {
        api("system", "import", "gmail");
        assertThat(shared("gmail", OWNER)).isTrue();

        api("SYSTEM", "custom", "slack"); // a bundle row claiming source custom still is not the user's
        assertThat(shared("slack", OWNER)).isTrue();
    }

    @Test
    @DisplayName("the owner's own custom template (legacy, no stamp) linked only from their API is theirs")
    void legacyOwnTemplateIsMine() throws Exception {
        UUID mine = api(OWNER, "custom", "mycrm");
        customTemplate("mycrm", "{}");
        link(tool(mine), "mycrm");

        assertThat(shared("mycrm", OWNER)).isFalse();
        assertThat(shared("mycrm", OTHER)).as("and foreign to everyone else").isTrue();
    }

    @Test
    @DisplayName("a template stamped by another owner is foreign even with no link")
    void stampedByAnotherOwnerIsForeign() throws Exception {
        customTemplate("acme", "{\"customApiOwner\": \"" + OTHER + "\"}");
        assertThat(shared("acme", OWNER)).isTrue();
        assertThat(shared("acme", OTHER)).isFalse();
    }

    @Test
    @DisplayName("an unstamped custom template linked from another owner's API is foreign")
    void linkedFromAnotherOwnersApiIsForeign() throws Exception {
        UUID theirs = api(OTHER, "custom", "acme");
        customTemplate("acme", "{}");
        link(tool(theirs), "acme");

        assertThat(shared("acme", OWNER)).isTrue();
    }

    @Test
    @DisplayName("ownership is by created_by: the owner's own API submitted with source 'import' is not foreign to them")
    void ownApiWithImportSourceIsMine() throws Exception {
        api(OWNER, "import", "mytool");
        assertThat(shared("mytool", OWNER)).isFalse();
        assertThat(shared("mytool", OTHER)).isTrue();
    }

    @Test
    @DisplayName("a key nobody holds is free")
    void unknownKeyIsFree() throws Exception {
        assertThat(shared("nothing", OWNER)).isFalse();
        assertThat(identityFree("nothing")).isFalse();
    }

    // ---------------------------------------------------------------- the shipped upsert and delete

    @Test
    @DisplayName("regression LC-057: registering over the native template is refused and leaves it byte-identical")
    void customUpsertCannotOverwriteNativeTemplate() {
        nativeImapTemplate();
        String before = imapMetadata();
        UUID squat = api(OWNER, "custom", "imap");
        UUID toolId = tool(squat);

        assertThatThrownBy(() -> seedService(squat, toolId).linkCredentials(
                squat, "imap", "bearer_token", "imap", null, null, null, OWNER, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in use");
        assertThat(imapMetadata()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.tool_credentials", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the owner's own template is stamped on insert and updated on re-register")
    void customUpsertOwnsItsTemplate() {
        UUID mine = api(OWNER, "custom", "mycrm");
        UUID toolId = tool(mine);
        CatalogSeedCredentialService service = seedService(mine, toolId);

        service.linkCredentials(mine, "mycrm", "bearer_token", "mycrm", null, null, null, OWNER, false);
        service.linkCredentials(mine, "mycrm", "api_key", "mycrm", null, null, null, OWNER, false);

        assertThat(jdbc.queryForObject("SELECT metadata ->> 'customApiOwner' FROM catalog.credentials "
                + "WHERE credential_name = 'mycrm'", String.class)).isEqualTo(OWNER);
        assertThat(jdbc.queryForObject("SELECT auth_type FROM catalog.credentials WHERE credential_name = 'mycrm'",
                String.class)).isEqualTo("api_key");
    }

    @Test
    @DisplayName("another owner cannot upsert over a stamped template")
    void otherOwnerCannotUpsertOverStampedTemplate() {
        UUID mine = api(OWNER, "custom", "mycrm");
        seedService(mine, tool(mine)).linkCredentials(mine, "mycrm", "bearer_token", "mycrm", null, null, null, OWNER, false);
        UUID theirs = api(OTHER, "custom", "mycrm2");
        UUID theirTool = tool(theirs);

        assertThatThrownBy(() -> seedService(theirs, theirTool).linkCredentials(
                theirs, "mycrm", "bearer_token", "mycrm", null, null, null, OTHER, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an update re-linking a key it already held links to the foreign row untouched")
    void reuseHeldTemplateLinksWithoutMutation() {
        nativeImapTemplate();
        String before = imapMetadata();
        UUID legacy = api(OWNER, "custom", "imap");
        UUID toolId = tool(legacy);

        seedService(legacy, toolId).linkCredentials(legacy, "imap", "bearer_token", "imap", null, null, null, OWNER, true);

        assertThat(imapMetadata()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.tool_credentials WHERE api_tool_id = ?",
                Integer.class, toolId)).isEqualTo(1);
    }

    @Test
    @DisplayName("regression LC-057: deleting the squatter's links never deletes the native template")
    void deleteNeverRemovesNativeTemplate() {
        nativeImapTemplate();
        UUID squat = api(OWNER, "custom", "imap");
        link(tool(squat), "imap");

        seedService(squat, UUID.randomUUID()).deleteCredentialsForApi(squat, "imap");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.tool_credentials", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.credentials WHERE credential_name = 'imap'",
                Integer.class)).as("pre-fix: NOT EXISTS links was true, so the template went").isEqualTo(1);
    }

    @Test
    @DisplayName("deleting an API's links leaves another API's links and the shared template alone")
    void deleteIsScopedToTheApi() {
        UUID a = api(OWNER, "custom", "shared");
        UUID b = api(OWNER, "custom", "shared2");
        customTemplate("shared", "{}");
        link(tool(a), "shared");
        UUID bTool = tool(b);
        link(bTool, "shared");

        seedService(a, UUID.randomUUID()).deleteCredentialsForApi(a, "shared");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.tool_credentials WHERE api_tool_id = ?",
                Integer.class, bTool)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.credentials WHERE credential_name = 'shared'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the owner's own unreferenced template IS deleted with their last link")
    void ownUnreferencedTemplateIsDeleted() {
        UUID mine = api(OWNER, "custom", "mycrm");
        customTemplate("mycrm", "{\"customApiOwner\": \"" + OWNER + "\"}");
        link(tool(mine), "mycrm");

        seedService(mine, UUID.randomUUID()).deleteCredentialsForApi(mine, "mycrm");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.credentials WHERE credential_name = 'mycrm'",
                Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- plumbing

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, DB.user(), DB.password());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static String withDatabase(String url, String database) {
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        String params = query < 0 ? "" : url.substring(query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + params;
    }
}
