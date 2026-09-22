package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.testsupport.ScratchPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Postgres integration test for {@link CredentialRepository#updateName(Long, String, String)} - the
 * write behind "rename a credential". Proves against a live engine what a mocked repository
 * cannot see:
 *
 * <ul>
 *   <li>{@code name} is replaced and {@code updated_at} IS moved (unlike
 *       {@code touchLastUsed}): {@code computeStateVersion} keys the agent response cache on
 *       {@code MAX(updated_at)}, so a rename that left it behind would serve the old name,
 *   <li>{@code credential_data} stays byte-for-byte intact - the targeted UPDATE never goes
 *       through {@code save()} and so never re-encrypts the secret,
 *   <li>{@code integration}, {@code is_default} and {@code status} are untouched: they are what
 *       execution resolves on, so a rename must not disturb them,
 *   <li>a rename works on a row whose {@code tenant_id} is NOT the caller (workspace-shared
 *       credential renamed by another org member) - the UPDATE is scoped by workspace,
 *   <li>a row outside the workspace named by the caller is NOT renamed, including one the
 *       caller OWNS in another workspace, and a blank workspace matches nothing,
 *   <li>{@code findOtherIntegrationsWithNameForTenant} catches a collision in another
 *       workspace of the same tenant (the scope {@code findAllByTenantIdAndName} actually
 *       reads), returns the integration that decides whether it is refused, and reaches no
 *       encrypted column while doing it,
 *   <li>{@code findAllByTenantIdAndName} returns every duplicate, deterministically ordered,
 *   <li>an unknown or null id is a no-op returning 0 (never throws).
 * </ul>
 *
 * <p>None of that is reachable from a mocked repository: {@code verify(repo).updateName(7L,
 * "org-1", "x")} passes whatever SQL the method body happens to contain, so a WHERE clause
 * that lost its {@code organization_id} or an {@code ORDER BY} flipped to {@code DESC} would
 * leave every mocked test green. This class runs the real statements against a real engine.
 *
 * <p><b>How it runs.</b> It talks to a plain Postgres over JDBC rather than starting one:
 * Testcontainers needs a Docker socket, which the {@code arc-build} CI runners do not expose.
 * CI provides a {@code pgvector/pgvector:pg16} service container and sets
 * {@code CREDENTIAL_TEST_PG_URL}, so the class runs there for real.
 *
 * <p>{@link ScratchPostgres} owns that decision and documents it: skipped on a laptop with no
 * scratch database, a hard failure on CI (a test that skips there is the same as no test), and a
 * refusal for any URL that does not visibly name a scratch one. This class TRUNCATEs
 * {@code auth.credentials}, which is why that last check matters. Locally:
 * {@code createdb lc_auth_test && CREDENTIAL_TEST_PG_URL=jdbc:postgresql://localhost:5432/lc_auth_test
 * mvn -pl auth-service test -Dtest=CredentialRepositoryNameSqlTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CredentialRepository name SQL - real Postgres")
class CredentialRepositoryNameSqlTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only thing that runs the credential name and rename SQL against a real "
                    + "engine");

    private JdbcTemplate jdbc;
    private CredentialEncryptionService encryption;
    private CredentialRepository repository;
    private ObjectMapper objectMapper;

    @BeforeAll
    void setUpSchema() {
        DB.require();

        DriverManagerDataSource ds = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        ds.setDriverClassName("org.postgresql.Driver");
        this.jdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(ds);
        this.objectMapper = new ObjectMapper();

        // Encryption is a pass-through stub: identity is enough for the columns these tests read.
        // Held as a field so a test can assert the rename guard NEVER reaches it - see
        // guardReadsIntegrationsWithoutDecryptingAnySecret.
        CredentialEncryptionService enc = mock(CredentialEncryptionService.class);
        encryption = enc;
        when(enc.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(enc.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.credentials CASCADE");
        jdbc.execute("""
                CREATE TABLE auth.credentials (
                    id BIGSERIAL PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255),
                    name VARCHAR(255) NOT NULL,
                    integration VARCHAR(255),
                    type VARCHAR(50) NOT NULL,
                    environment VARCHAR(50) NOT NULL DEFAULT 'Production',
                    status VARCHAR(50) NOT NULL DEFAULT 'active',
                    description TEXT,
                    credential_data JSONB NOT NULL DEFAULT '{}',
                    scopes TEXT[],
                    tags TEXT[],
                    owner VARCHAR(255),
                    icon_url VARCHAR(500),
                    is_default BOOLEAN NOT NULL DEFAULT FALSE,
                    last_used TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        this.repository = new CredentialRepository(jdbc, namedJdbc, objectMapper, enc);
    }

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE auth.credentials RESTART IDENTITY");
    }

    @Test
    @DisplayName("replaces the name, moves updated_at and leaves the encrypted secret intact")
    void renamesRowAndMovesUpdatedAtOnly() {
        long id = insertCredential("tenant-1", "gmail Credential",
                Map.of("access_token", "ya29-secret", "refresh_token", "1//refresh"));
        jdbc.update("UPDATE auth.credentials SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)), id);
        Timestamp updatedAtBefore = getTimestamp(id, "updated_at");
        String dataBefore = getCredentialDataText(id);

        int rows = repository.updateName(id, "org-1", "Gmail (work account)");

        assertThat(rows).isEqualTo(1);
        assertThat(getString(id, "name")).isEqualTo("Gmail (work account)");
        // updated_at MUST move: it is the agent response cache key (computeStateVersion).
        assertThat(getTimestamp(id, "updated_at")).isAfter(updatedAtBefore);
        // The secret is never re-encrypted by this path.
        assertThat(getCredentialDataText(id)).isEqualTo(dataBefore);
    }

    @Test
    @DisplayName("updateLlmMode rewrites ONE JSONB key of an llm_* row, moves updated_at, and leaves the encrypted api_key byte-for-byte")
    void updateLlmModeRewritesOnlyTheModeKey() {
        long id = insertCredential("tenant-1", "Anthropic", "llm_anthropic",
                Map.of("api_key", "enc:sk-ant-secret", "mode", "no_proxy"));
        jdbc.update("UPDATE auth.credentials SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)), id);
        Timestamp updatedAtBefore = getTimestamp(id, "updated_at");

        int rows = repository.updateLlmMode(id, "org-1", "proxy");

        assertThat(rows).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT credential_data->>'mode' FROM auth.credentials WHERE id = ?", String.class, id)).isEqualTo("proxy");
        assertThat(jdbc.queryForObject(
                "SELECT credential_data->>'api_key' FROM auth.credentials WHERE id = ?", String.class, id)).isEqualTo("enc:sk-ant-secret");
        assertThat(getTimestamp(id, "updated_at")).isAfter(updatedAtBefore);
        // A row without a mode yet gets one (jsonb_set with create_missing).
        long fresh = insertCredential("tenant-1", "OpenAI", "llm_openai", Map.of("api_key", "enc:sk-x"));
        assertThat(repository.updateLlmMode(fresh, "org-1", "proxy")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT credential_data->>'mode' FROM auth.credentials WHERE id = ?", String.class, fresh)).isEqualTo("proxy");
    }

    @Test
    @DisplayName("findUsableLlmIntegrations returns exactly the keys the resolver would serve, and nothing else")
    void findUsableLlmIntegrationsMirrorsTheResolver() {
        // Serves: default llm_ row, no mode (defaults to no_proxy), non-blank key.
        insertDefaultLlm("tenant-1", "llm_anthropic", Map.of("api_key", "enc:a"));
        // Serves: explicit no_proxy.
        insertDefaultLlm("tenant-1", "llm_openai", Map.of("api_key", "enc:b", "mode", "no_proxy"));
        // Does NOT serve: opted into the platform key.
        insertDefaultLlm("tenant-1", "llm_google", Map.of("api_key", "enc:c", "mode", "proxy"));
        // Does NOT serve: no key behind the row.
        insertDefaultLlm("tenant-1", "llm_mistral", Map.of("mode", "no_proxy"));
        insertDefaultLlm("tenant-1", "llm_deepseek", Map.of("api_key", "", "mode", "no_proxy"));
        // Does NOT serve: not the default, so the lookup never reaches it.
        long notDefault = insertCredential("tenant-1", "Second OpenAI", "llm_openai",
                Map.of("api_key", "enc:d"));
        jdbc.update("UPDATE auth.credentials SET is_default = FALSE WHERE id = ?", notDefault);
        // Does NOT serve: another tenant's key.
        insertDefaultLlm("tenant-OTHER", "llm_xai", Map.of("api_key", "enc:e"));
        // Does NOT serve: not an LLM key at all, and a lookalike the LIKE escape must reject.
        insertDefaultLlm("tenant-1", "gmail", Map.of("api_key", "enc:f"));
        insertDefaultLlm("tenant-1", "llmXanthropic", Map.of("api_key", "enc:g"));

        List<String> usable = repository.findUsableLlmIntegrations("tenant-1");

        assertThat(usable).containsExactlyInAnyOrder("llm_anthropic", "llm_openai");
    }

    @Test
    @DisplayName("findUsableLlmIntegrations reads mode and key the way the RESOLVER reads them: trimmed, and case-blind on the mode")
    void findUsableLlmIntegrationsMatchesTheResolverOnShapeNotSpelling() {
        // The resolver compares the mode with equalsIgnoreCase on a trimmed value, so each
        // of these three rows runs on the PLATFORM key. A literal <> 'proxy' in SQL would
        // have quoted all three as own-key, which is the expensive direction of wrong.
        insertDefaultLlm("tenant-1", "llm_anthropic", Map.of("api_key", "enc:a", "mode", "Proxy"));
        insertDefaultLlm("tenant-1", "llm_openai", Map.of("api_key", "enc:b", "mode", "PROXY"));
        insertDefaultLlm("tenant-1", "llm_google", Map.of("api_key", "enc:c", "mode", "  proxy "));
        // A tab is whitespace to String.trim() but not to BTRIM with no character set, which
        // is how the first version of this predicate still let a proxy row through.
        insertDefaultLlm("tenant-1", "llm_xai", Map.of("api_key", "enc:x", "mode", "\tproxy\n"));
        // The resolver rejects a blank key with isBlank(), and a whitespace-only value
        // reaches the column verbatim: the encryptor passes blank input through unchanged.
        insertDefaultLlm("tenant-1", "llm_mistral", Map.of("api_key", "   "));
        insertDefaultLlm("tenant-1", "llm_cohere", Map.of("api_key", "\t\n"));
        // Kept, so the assertion proves the query still returns something on this data.
        insertDefaultLlm("tenant-1", "llm_deepseek", Map.of("api_key", "enc:d", "mode", "NO_PROXY"));

        assertThat(repository.findUsableLlmIntegrations("tenant-1")).containsExactly("llm_deepseek");
    }

    @Test
    @DisplayName("findUsableLlmIntegrations lower-cases the integration it answers (a pin on behaviour this query already had)")
    void findUsableLlmIntegrationsAnswersLowerCase() {
        // The column has no case contract; the picker matches the catalogue provider in
        // lower case, so a row saved as LLM_Anthropic must not read as a second provider.
        insertDefaultLlm("tenant-1", "LLM_Anthropic", Map.of("api_key", "enc:a"));

        assertThat(repository.findUsableLlmIntegrations("tenant-1")).containsExactly("llm_anthropic");
    }

    @Test
    @DisplayName("findUsableLlmIntegrations answers empty for a blank tenant and for one with no key, never throws")
    void findUsableLlmIntegrationsEmptyCases() {
        insertDefaultLlm("tenant-1", "llm_anthropic", Map.of("api_key", "enc:a"));

        assertThat(repository.findUsableLlmIntegrations("tenant-none")).isEmpty();
        assertThat(repository.findUsableLlmIntegrations("")).isEmpty();
        assertThat(repository.findUsableLlmIntegrations(null)).isEmpty();
    }

    @Test
    @DisplayName("updateLlmMode refuses a non-LLM row, a row in another workspace, and an unknown id (0 rows, never throws)")
    void updateLlmModeScopeAndKindGuards() {
        long gmail = insertCredential("tenant-1", "Gmail", "gmail", Map.of("access_token", "t"));
        long other = insertCredential("tenant-1", "Anthropic", "llm_anthropic", Map.of("api_key", "k"));
        jdbc.update("UPDATE auth.credentials SET organization_id = 'org-OTHER' WHERE id = ?", other);
        // 'llm\_%' must match a literal underscore: an integration like 'llmX...' is not an LLM key.
        long lookalike = insertCredential("tenant-1", "Lookalike", "llmXanthropic", Map.of("api_key", "k"));

        assertThat(repository.updateLlmMode(gmail, "org-1", "proxy")).isZero();
        assertThat(repository.updateLlmMode(other, "org-1", "proxy")).isZero();
        assertThat(repository.updateLlmMode(lookalike, "org-1", "proxy")).isZero();
        assertThat(repository.updateLlmMode(999_999L, "org-1", "proxy")).isZero();
        assertThat(repository.updateLlmMode(null, "org-1", "proxy")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT credential_data->>'mode' FROM auth.credentials WHERE id = ?", String.class, gmail)).isNull();
    }

    @Test
    @DisplayName("leaves integration, is_default and status untouched")
    void leavesResolutionColumnsUntouched() {
        long id = insertCredential("tenant-1", "gmail Credential", Map.of("access_token", "t"));
        jdbc.update("UPDATE auth.credentials SET is_default = TRUE WHERE id = ?", id);

        repository.updateName(id, "org-1", "Renamed");

        // These three are what execution resolves on. A rename that moved any of them
        // would silently re-point every workflow that has no credential_id pinned.
        assertThat(getString(id, "integration")).isEqualTo("gmail");
        assertThat(getString(id, "status")).isEqualTo("active");
        assertThat(jdbc.queryForObject(
                "SELECT is_default FROM auth.credentials WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("renames a workspace-shared row owned by another member (keyed on id alone)")
    void renamesRowOwnedByAnotherMember() {
        long id = insertCredential("owner-member", "Team Gmail", Map.of("access_token", "t"));

        // save()'s UPDATE is "WHERE id = ? AND tenant_id = ?" and would write 0 rows here;
        // updateName matches the workspace too, so a member can rename what the org shares.
        int rows = repository.updateName(id, "org-1", "Shared Gmail");

        assertThat(rows).isEqualTo(1);
        assertThat(getString(id, "name")).isEqualTo("Shared Gmail");
        assertThat(getString(id, "tenant_id")).isEqualTo("owner-member");
    }

    @Test
    @DisplayName("renames only the targeted row")
    void renamesOnlyTargetRow() {
        long first = insertCredential("tenant-1", "Gmail A", Map.of("access_token", "a"));
        long second = insertCredential("tenant-1", "Gmail B", Map.of("access_token", "b"));

        repository.updateName(first, "org-1", "Gmail renamed");

        assertThat(getString(first, "name")).isEqualTo("Gmail renamed");
        assertThat(getString(second, "name")).isEqualTo("Gmail B");
    }

    @Test
    @DisplayName("refuses to rename a row in another workspace, even for its own owner")
    void refusesRowOutsideTheNamedWorkspace() {
        long id = insertCredential("tenant-1", "Personal Gmail", Map.of("access_token", "t"));

        // The caller OWNS this row, but is acting in another workspace. Strict
        // isolation is pure org equality (CredentialService.matchesScope), so the
        // second lock has to refuse here too: an "OR tenant_id = ?" clause would
        // let a member reach into a workspace they are not currently in.
        int rows = repository.updateName(id, "org-OTHER", "Hijacked");

        assertThat(rows).isZero();
        assertThat(getString(id, "name")).isEqualTo("Personal Gmail");
    }

    @Test
    @DisplayName("refuses to rename a row belonging to a stranger's workspace")
    void refusesRowOfAnotherWorkspace() {
        long id = insertCredential("owner-member", "Team Gmail", Map.of("access_token", "t"));

        assertThat(repository.updateName(id, "org-OTHER", "Hijacked")).isZero();
        assertThat(getString(id, "name")).isEqualTo("Team Gmail");
    }

    @Test
    @DisplayName("refuses to rename when no workspace is supplied")
    void refusesWithoutWorkspace() {
        long id = insertCredential("tenant-1", "Gmail", Map.of("access_token", "t"));

        // A blank org must never widen to "match any row"; it matches nothing.
        assertThat(repository.updateName(id, null, "Renamed")).isZero();
        assertThat(repository.updateName(id, "  ", "Renamed")).isZero();
        assertThat(getString(id, "name")).isEqualTo("Gmail");
    }

    @Test
    @DisplayName("findOtherIntegrationsWithNameForTenant catches a collision in ANOTHER workspace of the same tenant")
    void detectsDuplicateAcrossTheTenantsOtherWorkspace() {
        long personal = insertCredential("tenant-1", "gmail", "slack", Map.of("access_token", "a"));
        jdbc.update("UPDATE auth.credentials SET organization_id = 'org-PERSONAL' WHERE id = ?", personal);
        long shared = insertCredential("tenant-1", "Shared", Map.of("access_token", "b"));

        // findAllByTenantIdAndName is TENANT-scoped: renaming the org row to "gmail" would
        // make that lookup pick between two rows, even though they sit in different
        // workspaces. An org-only probe would miss this and allow the collision.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(shared, "tenant-1", "gmail"))
                .containsExactly("slack");
    }

    @Test
    @DisplayName("findOtherIntegrationsWithNameForTenant sees a sibling with the same name and ignores the row itself")
    void detectsDuplicateNamesInScope() {
        long first = insertCredential("tenant-1", "Gmail", Map.of("access_token", "a"));
        long second = insertCredential("tenant-1", "Other", Map.of("access_token", "b"));

        assertThat(repository.findOtherIntegrationsWithNameForTenant(second, "tenant-1", "Gmail"))
                .containsExactly("gmail");
        // The row being renamed never collides with itself.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(first, "tenant-1", "Gmail")).isEmpty();
        // Case-insensitive and trimmed, unlike findAllByTenantIdAndName's exact `name = ?`.
        // Deliberately wider: catalog's run-time selector compares the typed LABEL with
        // trim + equalsIgnoreCase, so "Gmail" and "  gmail " are one credential to it and two
        // rows differing only that way are exactly the ambiguity it refuses to resolve.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(second, "tenant-1", "gmail"))
                .containsExactly("gmail");
        assertThat(repository.findOtherIntegrationsWithNameForTenant(second, "tenant-1", "  GMAIL  "))
                .containsExactly("gmail");
        // Another tenant's name is not a collision.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(second, "another-tenant", "Gmail")).isEmpty();
    }

    @Test
    @DisplayName("findOtherIntegrationsWithNameForTenant returns every colliding integration, ordered, nulls kept")
    void collisionsCarryTheirIntegrationInOrder() {
        insertCredential("tenant-1", "grok", "slack", Map.of("access_token", "a"));
        long nameless = insertCredential("tenant-1", "grok", null, Map.of("access_token", "b"));
        long renamed = insertCredential("tenant-1", "My key", "xai", Map.of("access_token", "c"));
        // The default is the row inserted SECOND, so a plain scan would return it last: the
        // assertion below can only pass on the ORDER BY.
        jdbc.update("UPDATE auth.credentials SET is_default = TRUE WHERE id = ?", nameless);

        // The integration is the whole input to the refusal, and a NULL one is the shape whose
        // NAME is its identity: dropping nulls would silently stop refusing the one collision
        // that matters most. The order is the documented one (default first), so the log names
        // a stable row rather than whichever the scan happened to reach.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(renamed, "tenant-1", "grok"))
                .containsExactly(null, "slack");
    }

    @Test
    @DisplayName("the rename guard reads integrations without decrypting a single secret")
    void guardReadsIntegrationsWithoutDecryptingAnySecret() {
        insertCredential("tenant-1", "gmail", "gmail", Map.of("access_token", "a"));
        long renamed = insertCredential("tenant-1", "My key", "gmail", Map.of("access_token", "b"));
        clearInvocations(encryption);

        assertThat(repository.findOtherIntegrationsWithNameForTenant(renamed, "tenant-1", "gmail"))
                .containsExactly("gmail");

        // Mapping these rows through CredentialRowMapper would decrypt the api_key /
        // access_token / password of every colliding row, including rows in a workspace the
        // caller cannot open, purely to decide not to use them. It would also turn the rename
        // into a 500 on any row encrypted under a rotated key, since decrypt throws and the
        // guard has no catch. One column, no mapper, no decryption.
        verifyNoInteractions(encryption);
    }

    @Test
    @DisplayName("findAllByTenantIdAndName returns EVERY duplicate, default first then oldest")
    void nameLookupReturnsAllDuplicatesDefaultFirst() {
        long older = insertCredential("tenant-1", "Gmail", Map.of("access_token", "older"));
        long newer = insertCredential("tenant-1", "Gmail", Map.of("access_token", "newer"));
        jdbc.update("UPDATE auth.credentials SET is_default = TRUE WHERE id = ?", newer);

        // ALL of them: the resolver walks this list to find the row the name actually
        // identifies, so a query returning one row would hide the right answer behind the
        // wrong one. The DEFAULT leads even though the scan reaches the older row first,
        // so this cannot pass on insertion order alone.
        assertThat(repository.findAllByTenantIdAndName("tenant-1", "Gmail"))
                .extracting(c -> c.id()).containsExactly(newer, older);

        // With no default set, the OLDEST leads. Not a compatibility guarantee: an unordered
        // scan does not return insertion order on this table (touchLastUsed rewrites a tuple
        // on every use), so duplicates were already resolving arbitrarily. Ascending is the
        // safer of two arbitrary choices, since the oldest row has had the most time to be
        // pinned by id somewhere.
        jdbc.update("UPDATE auth.credentials SET is_default = FALSE WHERE id = ?", newer);
        assertThat(repository.findAllByTenantIdAndName("tenant-1", "Gmail"))
                .extracting(c -> c.id()).containsExactly(older, newer);
    }

    @Test
    @DisplayName("rename on an unknown id is a no-op returning 0 (never throws)")
    void unknownIdIsNoOp() {
        assertThat(repository.updateName(999_999L, "org-1", "Ghost")).isZero();
    }

    @Test
    @DisplayName("null id or null name is a no-op returning 0")
    void nullArgumentsAreNoOp() {
        long id = insertCredential("tenant-1", "Gmail", Map.of("access_token", "t"));

        assertThat(repository.updateName(null, "org-1", "Gmail")).isZero();
        assertThat(repository.updateName(id, "org-1", null)).isZero();
        assertThat(getString(id, "name")).isEqualTo("Gmail");
    }

    // ────────────────────────── helpers ──────────────────────────

    private long insertCredential(String tenantId, String name, Map<String, Object> data) {
        return insertCredential(tenantId, name, "gmail", data);
    }

    /** {@code integration} may be null: that is the shape whose NAME is its identity. */
    private long insertCredential(String tenantId, String name, String integration, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return jdbc.queryForObject("""
                    INSERT INTO auth.credentials (tenant_id, organization_id, name, integration, type, status, credential_data)
                    VALUES (?, 'org-1', ?, ?, 'OAuth2', 'active', ?::jsonb)
                    RETURNING id
                    """, Long.class, tenantId, name, integration, json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A DEFAULT credential of {@code integration}, which is the shape the resolver reads. */
    private long insertDefaultLlm(String tenantId, String integration, Map<String, Object> data) {
        long id = insertCredential(tenantId, integration + " key", integration, data);
        jdbc.update("UPDATE auth.credentials SET is_default = TRUE WHERE id = ?", id);
        return id;
    }

    private String getString(long id, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM auth.credentials WHERE id = ?", String.class, id);
    }

    private Timestamp getTimestamp(long id, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM auth.credentials WHERE id = ?", Timestamp.class, id);
    }

    private String getCredentialDataText(long id) {
        return jdbc.queryForObject(
                "SELECT credential_data::text FROM auth.credentials WHERE id = ?", String.class, id);
    }
}
