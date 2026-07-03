package com.apimarketplace.auth.variables.repository;

import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.ValueType;
import com.apimarketplace.auth.variables.domain.WorkflowVariableModels.WorkflowVariable;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WorkflowVariableRepository} against a real PostgreSQL (Testcontainers),
 * mirroring {@code PlatformCredentialRepositoryPostgresIT}. Pins:
 * <ul>
 *   <li>encryption at the SQL boundary: plaintext in, {@code ENC:} ciphertext
 *       at rest, plaintext back on every read path;</li>
 *   <li>STRICT scope semantics: workspace rows keyed by {@code organization_id}
 *       ALONE (shared across the org's members), personal rows keyed by
 *       {@code tenant_id AND organization_id IS NULL} - no fallback in either
 *       direction;</li>
 *   <li>update/delete confined to the caller's scope (return false on a
 *       cross-scope id, never touch the row);</li>
 *   <li>the V383 partial-unique indexes: one name per workspace, one name per
 *       personal scope, but the SAME name may coexist across scopes.</li>
 * </ul>
 *
 * <p>Schema mirrors {@code V383__workflow_variables.sql} (table + both partial
 * unique indexes), created directly - not the full Flyway set - so the test
 * stays fast and self-contained.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("WorkflowVariableRepository - Postgres integration")
class WorkflowVariableRepositoryPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_test")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbc;
    private WorkflowVariableRepository repository;

    @BeforeAll
    void setUpSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        this.jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        // Mirrors V383__workflow_variables.sql
        jdbc.execute("""
                CREATE TABLE auth.workflow_variables (
                    id              BIGSERIAL PRIMARY KEY,
                    tenant_id       VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255),
                    name            VARCHAR(64)  NOT NULL,
                    value           TEXT         NOT NULL,
                    value_type      VARCHAR(16)  NOT NULL DEFAULT 'STRING',
                    is_secret       BOOLEAN      NOT NULL DEFAULT FALSE,
                    description     TEXT,
                    created_by      VARCHAR(255),
                    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE UNIQUE INDEX ux_workflow_variables_org_name
                    ON auth.workflow_variables (organization_id, name) WHERE organization_id IS NOT NULL
                """);
        jdbc.execute("""
                CREATE UNIQUE INDEX ux_workflow_variables_personal_name
                    ON auth.workflow_variables (tenant_id, name) WHERE organization_id IS NULL
                """);

        CredentialEncryptionService encryption = new CredentialEncryptionService(
                "integration-test-password", "0123456789abcdef");
        this.repository = new WorkflowVariableRepository(jdbc, encryption);
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM auth.workflow_variables");
    }

    private static WorkflowVariable personal(String name, String tenantId, String value) {
        return new WorkflowVariable(null, tenantId, null, name, value,
                ValueType.STRING, false, "desc for " + name, tenantId, null, null);
    }

    private static WorkflowVariable workspace(String name, String tenantId, String organizationId, String value) {
        return new WorkflowVariable(null, tenantId, organizationId, name, value,
                ValueType.STRING, false, null, tenantId, null, null);
    }

    // ============================================================================
    //  Encryption at the SQL boundary
    // ============================================================================

    @Test
    @DisplayName("insert stores the value encrypted at rest (ENC: prefix) and returns the generated id")
    void insertEncryptsValueAtRest() {
        WorkflowVariable inserted = repository.insert(personal("api_key_like", "tenant-A", "super-secret-value"));

        assertThat(inserted.id()).isNotNull();
        assertThat(inserted.value()).as("in-memory value stays plaintext").isEqualTo("super-secret-value");
        String raw = jdbc.queryForObject(
                "SELECT value FROM auth.workflow_variables WHERE id = ?", String.class, inserted.id());
        assertThat(raw)
                .as("value column must never store the plaintext")
                .isNotEqualTo("super-secret-value")
                .startsWith("ENC:");
    }

    @Test
    @DisplayName("every read path decrypts back to plaintext (findAll / findByName / findByIdForScope)")
    void readPathsDecrypt() {
        WorkflowVariable inserted = repository.insert(personal("api_url", "tenant-A", "https://api.example.com"));

        assertThat(repository.findAllForScope("tenant-A", null))
                .singleElement()
                .extracting(WorkflowVariable::value).isEqualTo("https://api.example.com");
        assertThat(repository.findByName("api_url", "tenant-A", null))
                .map(WorkflowVariable::value).contains("https://api.example.com");
        assertThat(repository.findByIdForScope(inserted.id(), "tenant-A", null))
                .map(WorkflowVariable::value).contains("https://api.example.com");
    }

    @Test
    @DisplayName("update re-encrypts the new value at rest")
    void updateReencrypts() {
        WorkflowVariable inserted = repository.insert(personal("api_url", "tenant-A", "old-value"));

        boolean updated = repository.update(inserted.id(), "tenant-A", null,
                "api_url", "new-value", ValueType.STRING, false, null);

        assertThat(updated).isTrue();
        String raw = jdbc.queryForObject(
                "SELECT value FROM auth.workflow_variables WHERE id = ?", String.class, inserted.id());
        assertThat(raw).isNotEqualTo("new-value").startsWith("ENC:");
        assertThat(repository.findByIdForScope(inserted.id(), "tenant-A", null))
                .map(WorkflowVariable::value).contains("new-value");
    }

    // ============================================================================
    //  Strict scope filtering
    // ============================================================================

    @Test
    @DisplayName("personal scope lists ONLY the tenant's org-null rows - other tenants and workspace rows excluded")
    void personalScopeIsStrict() {
        repository.insert(personal("mine", "tenant-A", "v"));
        repository.insert(personal("theirs", "tenant-B", "v"));
        repository.insert(workspace("shared", "tenant-A", "org-1", "v"));

        List<WorkflowVariable> visible = repository.findAllForScope("tenant-A", null);

        assertThat(visible).extracting(WorkflowVariable::name)
                .as("tenant-A's own workspace row must NOT leak into the personal scope")
                .containsExactly("mine");
    }

    @Test
    @DisplayName("workspace scope is keyed by organization_id ALONE - rows created by different members are all visible")
    void workspaceScopeSharedAcrossMembers() {
        repository.insert(workspace("from_alice", "tenant-alice", "org-1", "v"));
        repository.insert(workspace("from_bob", "tenant-bob", "org-1", "v"));
        repository.insert(workspace("other_org", "tenant-alice", "org-2", "v"));
        repository.insert(personal("alice_personal", "tenant-alice", "v"));

        // Bob resolves org-1: he must see Alice's workspace row too (shared),
        // but never org-2 rows or anyone's personal rows.
        List<WorkflowVariable> visible = repository.findAllForScope("tenant-bob", "org-1");

        assertThat(visible).extracting(WorkflowVariable::name)
                .containsExactly("from_alice", "from_bob");
    }

    @Test
    @DisplayName("findAllForScope orders by name ascending")
    void findAllOrdersByName() {
        repository.insert(personal("zulu", "tenant-A", "v"));
        repository.insert(personal("alpha", "tenant-A", "v"));
        repository.insert(personal("mike", "tenant-A", "v"));

        assertThat(repository.findAllForScope("tenant-A", null))
                .extracting(WorkflowVariable::name)
                .containsExactly("alpha", "mike", "zulu");
    }

    @Test
    @DisplayName("findByName never crosses scopes: a personal name is invisible from the workspace scope and vice versa")
    void findByNameScopeStrict() {
        repository.insert(personal("api_url", "tenant-A", "personal-value"));
        repository.insert(workspace("api_url", "tenant-A", "org-1", "workspace-value"));

        Optional<WorkflowVariable> personalHit = repository.findByName("api_url", "tenant-A", null);
        Optional<WorkflowVariable> workspaceHit = repository.findByName("api_url", "tenant-A", "org-1");
        Optional<WorkflowVariable> otherOrg = repository.findByName("api_url", "tenant-A", "org-2");

        assertThat(personalHit).map(WorkflowVariable::value).contains("personal-value");
        assertThat(workspaceHit).map(WorkflowVariable::value).contains("workspace-value");
        assertThat(otherOrg).as("no fallback to another org or to personal").isEmpty();
    }

    @Test
    @DisplayName("findByIdForScope refuses a cross-scope id - a workspace row is unreachable via the personal scope")
    void findByIdForScopeStrict() {
        WorkflowVariable orgRow = repository.insert(workspace("shared", "tenant-A", "org-1", "v"));

        assertThat(repository.findByIdForScope(orgRow.id(), "tenant-A", "org-1")).isPresent();
        assertThat(repository.findByIdForScope(orgRow.id(), "tenant-A", null))
                .as("same tenant, personal scope -> no match").isEmpty();
        assertThat(repository.findByIdForScope(orgRow.id(), "tenant-A", "org-2"))
                .as("different workspace -> no match").isEmpty();
    }

    @Test
    @DisplayName("countForScope counts each scope in isolation")
    void countForScopeIsolated() {
        repository.insert(personal("p1", "tenant-A", "v"));
        repository.insert(personal("p2", "tenant-A", "v"));
        repository.insert(workspace("w1", "tenant-A", "org-1", "v"));
        repository.insert(personal("q1", "tenant-B", "v"));

        assertThat(repository.countForScope("tenant-A", null)).isEqualTo(2);
        assertThat(repository.countForScope("tenant-A", "org-1")).isEqualTo(1);
        assertThat(repository.countForScope("tenant-B", null)).isEqualTo(1);
        assertThat(repository.countForScope("tenant-C", null)).isZero();
    }

    // ============================================================================
    //  Scope-confined mutations
    // ============================================================================

    @Test
    @DisplayName("update returns false and leaves the row untouched when the scope does not own the id")
    void updateRefusesCrossScope() {
        WorkflowVariable orgRow = repository.insert(workspace("shared", "tenant-A", "org-1", "original"));

        boolean fromPersonal = repository.update(orgRow.id(), "tenant-A", null,
                "shared", "hijacked", ValueType.STRING, false, null);
        boolean fromOtherOrg = repository.update(orgRow.id(), "tenant-A", "org-2",
                "shared", "hijacked", ValueType.STRING, false, null);

        assertThat(fromPersonal).isFalse();
        assertThat(fromOtherOrg).isFalse();
        assertThat(repository.findByIdForScope(orgRow.id(), "tenant-A", "org-1"))
                .map(WorkflowVariable::value).contains("original");
    }

    @Test
    @DisplayName("update rewrites name, value, type and description of a scope-owned row")
    void updateRewritesFields() {
        WorkflowVariable inserted = repository.insert(personal("old_name", "tenant-A", "1"));

        boolean updated = repository.update(inserted.id(), "tenant-A", null,
                "new_name", "42", ValueType.NUMBER, false, "now a number");

        assertThat(updated).isTrue();
        WorkflowVariable after = repository.findByIdForScope(inserted.id(), "tenant-A", null).orElseThrow();
        assertThat(after.name()).isEqualTo("new_name");
        assertThat(after.value()).isEqualTo("42");
        assertThat(after.valueType()).isEqualTo(ValueType.NUMBER);
        assertThat(after.description()).isEqualTo("now a number");
    }

    @Test
    @DisplayName("deleteByIdForScope returns false on a cross-scope id and true when the scope owns it")
    void deleteScopeConfined() {
        WorkflowVariable orgRow = repository.insert(workspace("shared", "tenant-A", "org-1", "v"));

        assertThat(repository.deleteByIdForScope(orgRow.id(), "tenant-A", null))
                .as("personal scope cannot delete a workspace row").isFalse();
        assertThat(repository.deleteByIdForScope(orgRow.id(), "tenant-B", "org-1"))
                .as("any org member may delete the shared row (org-keyed scope)").isTrue();
        assertThat(repository.findByIdForScope(orgRow.id(), "tenant-A", "org-1")).isEmpty();
    }

    // ============================================================================
    //  V383 uniqueness semantics + row-mapper resilience
    // ============================================================================

    @Test
    @DisplayName("the same name coexists across scopes (two personal tenants + a workspace) but duplicates within a scope are rejected")
    void uniquenessIsPerScope() {
        repository.insert(personal("api_url", "tenant-A", "v"));
        repository.insert(personal("api_url", "tenant-B", "v"));
        repository.insert(workspace("api_url", "tenant-A", "org-1", "v"));

        assertThatThrownBy(() -> repository.insert(personal("api_url", "tenant-A", "dup")))
                .as("V383 partial unique index: one name per personal scope")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> repository.insert(workspace("api_url", "tenant-B", "org-1", "dup")))
                .as("V383 partial unique index: one name per workspace, whoever the member is")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("row mapper defaults an unknown stored value_type to STRING instead of failing the whole listing")
    void rowMapperDefaultsUnknownTypeToString() {
        CredentialEncryptionService encryption = new CredentialEncryptionService(
                "integration-test-password", "0123456789abcdef");
        jdbc.update("""
                INSERT INTO auth.workflow_variables (tenant_id, organization_id, name, value, value_type)
                VALUES (?, NULL, ?, ?, ?)
                """, "tenant-A", "legacy_row", encryption.encrypt("v"), "LEGACY_TYPE");

        List<WorkflowVariable> rows = repository.findAllForScope("tenant-A", null);

        assertThat(rows).singleElement()
                .satisfies(row -> {
                    assertThat(row.valueType()).isEqualTo(ValueType.STRING);
                    assertThat(row.value()).isEqualTo("v");
                });
    }

    @Test
    @DisplayName("is_secret round-trips on insert: TRUE lands in the column and comes back on read")
    void secretFlagRoundTripsOnInsert() {
        WorkflowVariable inserted = repository.insert(new WorkflowVariable(
                null, "tenant-A", null, "api_key", "sk-123", ValueType.STRING,
                true, null, "tenant-A", null, null));

        Boolean raw = jdbc.queryForObject(
                "SELECT is_secret FROM auth.workflow_variables WHERE id = ?", Boolean.class, inserted.id());
        assertThat(raw).as("is_secret column stores TRUE at rest").isTrue();
        assertThat(repository.findByIdForScope(inserted.id(), "tenant-A", null))
                .map(WorkflowVariable::secret).contains(true);
    }

    @Test
    @DisplayName("is_secret round-trips on update: a plain row can be marked secret and demoted back")
    void secretFlagRoundTripsOnUpdate() {
        WorkflowVariable inserted = repository.insert(personal("api_key", "tenant-A", "sk-123"));
        assertThat(inserted.secret()).isFalse();

        boolean marked = repository.update(inserted.id(), "tenant-A", null,
                "api_key", "sk-123", ValueType.STRING, true, null);

        assertThat(marked).isTrue();
        Boolean rawAfterMark = jdbc.queryForObject(
                "SELECT is_secret FROM auth.workflow_variables WHERE id = ?", Boolean.class, inserted.id());
        assertThat(rawAfterMark).as("UPDATE must SET is_secret = TRUE").isTrue();
        assertThat(repository.findByIdForScope(inserted.id(), "tenant-A", null))
                .map(WorkflowVariable::secret).contains(true);

        boolean demoted = repository.update(inserted.id(), "tenant-A", null,
                "api_key", "sk-123", ValueType.STRING, false, null);

        assertThat(demoted).isTrue();
        assertThat(repository.findByIdForScope(inserted.id(), "tenant-A", null))
                .map(WorkflowVariable::secret).contains(false);
    }

    @Test
    @DisplayName("insert round-trips every column: scope, type, description, created_by, timestamps populated by the DB")
    void insertRoundTripsAllColumns() {
        WorkflowVariable inserted = repository.insert(new WorkflowVariable(
                null, "tenant-A", "org-1", "cfg", "{\"a\":1}", ValueType.JSON,
                false, "workspace config", "creator-user", null, null));

        WorkflowVariable fetched = repository.findByIdForScope(inserted.id(), "tenant-A", "org-1").orElseThrow();
        assertThat(fetched.tenantId()).isEqualTo("tenant-A");
        assertThat(fetched.organizationId()).isEqualTo("org-1");
        assertThat(fetched.name()).isEqualTo("cfg");
        assertThat(fetched.value()).isEqualTo("{\"a\":1}");
        assertThat(fetched.valueType()).isEqualTo(ValueType.JSON);
        assertThat(fetched.secret()).isFalse();
        assertThat(fetched.description()).isEqualTo("workspace config");
        assertThat(fetched.createdBy()).isEqualTo("creator-user");
        assertThat(fetched.createdAt()).as("DB default now()").isNotNull();
        assertThat(fetched.updatedAt()).as("DB default now()").isNotNull();
    }
}
