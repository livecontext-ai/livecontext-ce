package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.dto.CustomApiRefDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the custom-API publish gate's hand-built SQL against a REAL PostgreSQL.
 *
 * <p><b>Why this exists next to {@link WorkflowInspectorServiceCustomApiRefsTest}.</b> That
 * suite asserts the SQL as a STRING through a mocked {@code JdbcTemplate}, which is the right
 * place to pin WHICH predicate is emitted and in which bind order, but it cannot see what the
 * query RETURNS. The gate's semantics live entirely in that answer, and a string assertion
 * already let one defect through: an earlier exemption keyed on the candidate row's slug read
 * correctly, emitted exactly the expected text, and silently ungated a publisher's own custom
 * API whose slug happened to match a shipped integration ("Slack") - the feature's main path.
 *
 * <p>So the cases below are stated as data in and rows out, and each one is a rule the gate
 * must keep: the publisher's own custom API is caught (even when its slug collides, even when
 * its tool rows were renamed), a reference a SHIPPED tool answers is never attributed to a
 * custom API, and another tenant's custom API can only be reached by an exact row.
 *
 * <p>Skipped (not failed) when no Docker daemon is available, like its siblings
 * {@code PublicIntegrationSqlIntegrationTest} / {@code ApiCatalogBundleSqlIntegrationTest}.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Custom-API reference lookup SQL - real-Postgres integration (Testcontainers)")
class CustomApiRefsSqlIntegrationTest {

    private static final String PUBLISHER = "tenant-publisher";
    private static final String STRANGER = "tenant-stranger";
    private static final String ORG = "org-acme";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("custom_api_refs_it")
            .withUsername("postgres")
            .withPassword("postgres");

    private JdbcTemplate jdbc;
    private WorkflowInspectorService service;

    @BeforeAll
    void initSchema() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("schema-public-integrations-postgres.sql"));
            // The service's SQL names `apis` / `api_tools` UNQUALIFIED and relies on the
            // search_path, exactly as it does in production (where the datasource sets it).
            // Set it on the database so every later connection inherits it.
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER DATABASE " + POSTGRES.getDatabaseName()
                        + " SET search_path TO catalog, public");
            }
        }
        jdbc = new JdbcTemplate(ds);
        service = new WorkflowInspectorService(null, null, null, null, jdbc, null);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE catalog.api_tools, catalog.apis, catalog.tool_names, "
                + "catalog.api_usage_stats CASCADE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fixtures
    // ─────────────────────────────────────────────────────────────────────────

    private UUID api(String slug, String name, String source, String createdBy, String organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO catalog.apis
                (id, created_by, organization_id, api_name, api_slug, description, base_url,
                 visibility, source)
            VALUES (?, ?, ?, ?, ?, 'desc', 'https://example.test', 'private', ?)
            """, id, createdBy, organizationId, name, slug, source);
        return id;
    }

    /** A custom API owned personally by the publisher. */
    private UUID ownCustomApi(String slug, String name) {
        return api(slug, name, "custom", PUBLISHER, null);
    }

    /** A custom API owned by somebody else entirely. */
    private UUID strangerCustomApi(String slug, String name) {
        return api(slug, name, "custom", STRANGER, null);
    }

    /** A shipped catalog integration (what the seed corpus produces). */
    private UUID shippedApi(String slug, String name) {
        return api(slug, name, "import", "SYSTEM", null);
    }

    private void tool(UUID apiId, String toolSlug) {
        jdbc.update("""
            INSERT INTO catalog.api_tools (api_id, tool_slug, description, method, endpoint)
            VALUES (?, ?, 'desc', 'POST', '/v1/thing')
            """, apiId, toolSlug);
    }

    private List<String> gateNames(List<String> identifiers, String tenantId, String organizationId) {
        return service.findCustomApiRefs(identifiers, tenantId, organizationId)
                .stream().map(CustomApiRefDTO::apiName).toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // the publisher's own custom API is caught
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an mcp node on the publisher's own custom API is caught")
    void ownCustomApiIsCaught() {
        UUID mine = ownCustomApi("my-api", "My API");
        tool(mine, "my-api-do-thing");

        assertThat(gateNames(List.of("my-api/my-api-do-thing"), PUBLISHER, null))
                .containsExactly("My API");
    }

    @Test
    @DisplayName("an agent grant's colon form is caught the same way")
    void colonFormIsCaught() {
        UUID mine = ownCustomApi("my-api", "My API");
        tool(mine, "my-api-do-thing");

        assertThat(gateNames(List.of("my-api:my-api-do-thing"), PUBLISHER, null))
                .containsExactly("My API");
    }

    /**
     * Regression for the defect a string assertion could not see: the exemption is keyed on the
     * REFERENCE, so a custom API that merely shares a slug with a shipped integration keeps
     * being gated on ITS OWN tools. Keyed on the candidate row instead, this returned nothing.
     */
    @Test
    @DisplayName("the publisher's own custom API is STILL caught when its slug collides with a shipped one")
    void ownCustomApiWithCollidingSlugIsStillCaught() {
        UUID shipped = shippedApi("slack", "Slack");
        tool(shipped, "slack-send-message");
        UUID mine = ownCustomApi("slack", "My Private Slack Proxy");
        tool(mine, "slack-my-thing");

        assertThat(gateNames(List.of("slack/slack-my-thing"), PUBLISHER, null))
                .containsExactly("My Private Slack Proxy");
    }

    @Test
    @DisplayName("the publisher's own custom API is caught by slug even after its tool row was renamed")
    void ownCustomApiIsCaughtAfterToolRename() {
        UUID mine = ownCustomApi("my-api", "My API");
        tool(mine, "renamed-since");

        assertThat(gateNames(List.of("my-api/what-the-node-still-says"), PUBLISHER, null))
                .containsExactly("My API");
    }

    @Test
    @DisplayName("an org-owned custom API is caught when publishing from that workspace")
    void orgOwnedCustomApiIsCaught() {
        UUID ours = api("team-api", "Team API", "custom", "someone-else", ORG);
        tool(ours, "team-api-do-thing");

        assertThat(gateNames(List.of("team-api/renamed"), PUBLISHER, ORG)).containsExactly("Team API");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // a shipped reference is never attributed to a custom API
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a reference a SHIPPED tool answers is not refused, even with a same-slug custom API around")
    void shippedReferenceIsNeverRefused() {
        UUID shipped = shippedApi("slack", "Slack");
        tool(shipped, "slack-send-message");
        UUID squatter = strangerCustomApi("slack", "Stranger Confidential API");
        tool(squatter, "slack-send-message");

        assertThat(gateNames(List.of("slack/slack-send-message"), PUBLISHER, null)).isEmpty();
    }

    @Test
    @DisplayName("a plan built only on shipped integrations is never refused")
    void shippedOnlyPlanIsNeverRefused() {
        UUID shipped = shippedApi("github", "GitHub");
        tool(shipped, "github-get-user");

        assertThat(gateNames(List.of("github/github-get-user", "github-get-user"), PUBLISHER, null))
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // another tenant's custom API: exact row only
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a stranger's custom API is NOT reachable by slug prefix (no cross-tenant refusal or name leak)")
    void strangerCustomApiIsNotReachableByPrefix() {
        UUID theirs = strangerCustomApi("their-api", "Stranger Confidential API");
        tool(theirs, "their-api-do-thing");

        assertThat(gateNames(List.of("their-api/something-else"), PUBLISHER, null)).isEmpty();
    }

    @Test
    @DisplayName("a stranger's custom API IS caught on an exact row - an acquired plan runs on its publisher's API")
    void strangerCustomApiIsCaughtOnAnExactRow() {
        UUID theirs = strangerCustomApi("their-api", "Publisher API");
        tool(theirs, "their-api-do-thing");

        assertThat(gateNames(List.of("their-api/their-api-do-thing"), PUBLISHER, null))
                .containsExactly("Publisher API");
    }

    @Test
    @DisplayName("an api_tools.id reference is caught whoever owns the API, with no slug involved")
    void toolIdReferenceIsCaught() {
        UUID theirs = strangerCustomApi("their-api", "Publisher API");
        tool(theirs, "their-api-do-thing");
        String toolId = jdbc.queryForObject(
                "SELECT id::text FROM catalog.api_tools WHERE api_id = ?", String.class, theirs);

        assertThat(gateNames(List.of(toolId), PUBLISHER, null)).containsExactly("Publisher API");
    }

    /**
     * `api_slug` is unique only per creator, so one reference can return two custom APIs: the
     * publisher's own (prefix-matched) and a stranger's (exact-row-matched). They must stay two
     * named entries. Grouped by slug they collapsed into one, and the name that survived was
     * whichever row the planner emitted first.
     */
    @Test
    @DisplayName("two custom APIs sharing a slug are reported separately, each with its own name")
    void apisSharingASlugAreReportedSeparately() {
        UUID mine = ownCustomApi("dup", "My API");
        tool(mine, "dup-mine");
        UUID theirs = strangerCustomApi("dup", "Stranger Confidential API");
        tool(theirs, "dup-theirs");

        assertThat(service.findCustomApiRefs(
                List.of("dup/dup-mine", "dup/dup-theirs"), PUBLISHER, null))
                .extracting(CustomApiRefDTO::apiName)
                .containsExactlyInAnyOrder("My API", "Stranger Confidential API");
    }

    @Test
    @DisplayName("a deactivated custom API still blocks the share (no is_active filter)")
    void deactivatedCustomApiStillBlocks() {
        UUID mine = ownCustomApi("my-api", "My API");
        tool(mine, "my-api-do-thing");
        jdbc.update("UPDATE catalog.apis SET is_active = false WHERE id = ?", mine);
        jdbc.update("UPDATE catalog.api_tools SET is_active = false WHERE api_id = ?", mine);

        assertThat(gateNames(List.of("my-api/my-api-do-thing"), PUBLISHER, null))
                .containsExactly("My API");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // the query's own invariants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a SHIPPED api is never returned, whatever the reference shape")
    void shippedApisAreNeverReturned() {
        UUID shipped = shippedApi("github", "GitHub");
        tool(shipped, "github-get-user");
        String toolId = jdbc.queryForObject(
                "SELECT id::text FROM catalog.api_tools WHERE api_id = ?", String.class, shipped);

        // The id branch sits outside the exemption group, so this is the case that would
        // surface a lost `AND (` around the top-level OR.
        assertThat(gateNames(List.of("github/github-get-user", "github-get-user", toolId),
                PUBLISHER, null)).isEmpty();
    }

    @Test
    @DisplayName("every identifier shape in one call executes and reports each API once")
    void mixedShapesExecuteAndGroup() {
        UUID mine = ownCustomApi("my-api", "My API");
        tool(mine, "my-api-do-thing");
        UUID other = ownCustomApi("other-api", "Other API");
        tool(other, "other-api-do-thing");
        String toolId = jdbc.queryForObject(
                "SELECT id::text FROM catalog.api_tools WHERE api_id = ?", String.class, other);

        List<CustomApiRefDTO> refs = service.findCustomApiRefs(
                List.of("my-api/my-api-do-thing", "my-api:my-api-do-thing",
                        "other-api-do-thing", toolId),
                PUBLISHER, null);

        assertThat(refs).hasSize(2);
        assertThat(refs).extracting(CustomApiRefDTO::apiName)
                .containsExactlyInAnyOrder("My API", "Other API");
        assertThat(refs).filteredOn(ref -> "my-api".equals(ref.apiSlug()))
                .singleElement()
                .satisfies(ref -> assertThat(ref.toolIdentifiers())
                        .containsExactlyInAnyOrder("my-api/my-api-do-thing", "my-api:my-api-do-thing"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // the caller-facing (owner-restricted) variant
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the scoped variant answers about the caller's own API and never about a stranger's")
    void scopedVariantIsOwnerRestricted() {
        UUID mine = ownCustomApi("my-api", "My API");
        tool(mine, "my-api-do-thing");
        UUID theirs = strangerCustomApi("their-api", "Stranger Confidential API");
        tool(theirs, "their-api-do-thing");

        assertThat(service.findCustomApiRefsInScope(
                List.of("my-api/my-api-do-thing", "their-api/their-api-do-thing"), PUBLISHER, null))
                .extracting(CustomApiRefDTO::apiName)
                .containsExactly("My API");
    }

    @Test
    @DisplayName("the scoped variant in a workspace answers about the workspace's APIs, not the user's personal ones")
    void scopedVariantInOrgSeesOrgApisOnly() {
        UUID personal = ownCustomApi("personal-api", "Personal API");
        tool(personal, "personal-api-do-thing");
        UUID team = api("team-api", "Team API", "custom", "someone-else", ORG);
        tool(team, "team-api-do-thing");

        assertThat(service.findCustomApiRefsInScope(
                List.of("personal-api/personal-api-do-thing", "team-api/team-api-do-thing"),
                PUBLISHER, ORG))
                .extracting(CustomApiRefDTO::apiName)
                .containsExactly("Team API");
    }
}
