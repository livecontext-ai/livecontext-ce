package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression test for the V264 follow-through fix on
 * {@code AgentTaskRepository.cascadingCancelInOrganization}.
 *
 * <p>The pre-fix native SQL contained
 * {@code organization_id = CAST(:organizationId AS uuid)}. After V264
 * aligned {@code agent.agent_tasks.organization_id} from UUID to
 * VARCHAR(255), Postgres rejected the predicate with
 * {@code ERROR: operator does not exist: character varying = uuid} -
 * unlike INSERT-value casts, equality has no implicit assignment cast.
 *
 * <p>This regression pins the EXACT post-fix SQL pattern (drop the
 * {@code CAST AS uuid}, compare varchar to varchar directly) against a
 * real V264'd schema slice. If the SQL drifts back to the broken form,
 * this IT fails with the production exception.
 *
 * <p>The SQL below mirrors the STRUCTURE of
 * {@link AgentTaskRepository#cascadingCancelInOrganization} to pin the V264
 * {@code organization_id = :organizationId} (varchar) contract. It keeps only
 * the literal active-status filter and intentionally does NOT replicate the F4
 * category-aware branch (the {@code OR status IN (SELECT key FROM
 * agent.task_statuses ...)} clause added so tasks in custom active columns also
 * cascade); that branch is additive and is covered against real Postgres by
 * {@code ce-task-board-kanban-proxy-contracts.spec.ts} (CE-TASK-KANBAN-015).
 *
 * <p>Skipped without Docker.
 */
@Testcontainers
@DisplayName("V264 regression - AgentTaskRepository.cascadingCancelInOrganization survives varchar organization_id")
class AgentTaskRepositoryV264PostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ORG_ID = "00000000-0000-0000-0000-000000000000";
    private static final String OTHER_ORG_ID = "34df5a10-1234-5678-9abc-def012345678";
    private static final String TENANT_ID = "1";

    // Structural mirror of AgentTaskRepository#cascadingCancelInOrganization @Query
    // body, pinning the V264 varchar organization_id contract. The F4 category-aware
    // OR-branch is omitted on purpose (additive; covered by the CE e2e spec) - see
    // the class javadoc.
    private static final String CASCADING_CANCEL_SQL = """
        WITH RECURSIVE descendants AS (
            SELECT id
              FROM agent.agent_tasks
             WHERE id = CAST(? AS uuid)
               AND tenant_id = ?
               AND organization_id = ?
               AND status IN ('pending','in_progress','in_review')
            UNION ALL
            SELECT t.id
              FROM agent.agent_tasks t
              JOIN descendants d ON t.parent_task_id = d.id
             WHERE t.tenant_id = ?
               AND t.organization_id = ?
               AND t.status IN ('pending','in_progress','in_review')
        )
        UPDATE agent.agent_tasks t
           SET status        = 'cancelled',
               error_message = CASE WHEN ? IS NULL THEN t.error_message ELSE ? END,
               completed_at  = now(),
               updated_at    = now()
          FROM descendants d
         WHERE t.id = d.id
    """;

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - AgentTaskRepositoryV264PostgresIT skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA agent");
        // V264'd schema: organization_id VARCHAR(255), not UUID.
        jdbc.execute("CREATE TABLE agent.agent_tasks ("
                + "  id UUID PRIMARY KEY,"
                + "  parent_task_id UUID,"
                + "  tenant_id VARCHAR(255) NOT NULL,"
                + "  organization_id VARCHAR(255) NOT NULL,"
                + "  status VARCHAR(32) NOT NULL,"
                + "  error_message TEXT,"
                + "  completed_at TIMESTAMPTZ,"
                + "  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()"
                + ")");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM agent.agent_tasks");
    }

    @Test
    @DisplayName("cancels root + descendants in scope and skips out-of-org siblings (regression: WHERE varchar = uuid parse error)")
    void cancelsTreeInScopeOnly() {
        UUID root = UUID.randomUUID();
        UUID childA = UUID.randomUUID();
        UUID childB = UUID.randomUUID();
        UUID otherOrgRoot = UUID.randomUUID();

        insertTask(root, null, TENANT_ID, ORG_ID, "pending");
        insertTask(childA, root, TENANT_ID, ORG_ID, "in_progress");
        insertTask(childB, root, TENANT_ID, ORG_ID, "in_review");
        // Sibling in a different org - must NOT be touched.
        insertTask(otherOrgRoot, null, TENANT_ID, OTHER_ORG_ID, "pending");

        int affected = jdbc.update(CASCADING_CANCEL_SQL,
                root.toString(),       // CAST(? AS uuid) - root id
                TENANT_ID,             // tenant_id (anchor)
                ORG_ID,                // organization_id (anchor)  ← was CAST AS uuid pre-fix
                TENANT_ID,             // tenant_id (recursive step)
                ORG_ID,                // organization_id (recursive step)  ← was CAST AS uuid pre-fix
                "user_request",        // reason IS NULL check
                "user_request");       // reason value

        assertThat(affected).isEqualTo(3);
        assertCancelled(root);
        assertCancelled(childA);
        assertCancelled(childB);

        // Out-of-org task untouched.
        String otherStatus = jdbc.queryForObject(
                "SELECT status FROM agent.agent_tasks WHERE id = ?",
                String.class, otherOrgRoot);
        assertThat(otherStatus).isEqualTo("pending");
    }

    private void insertTask(UUID id, UUID parentId, String tenantId, String orgId, String status) {
        jdbc.update(
                "INSERT INTO agent.agent_tasks (id, parent_task_id, tenant_id, organization_id, status) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id, parentId, tenantId, orgId, status);
    }

    private void assertCancelled(UUID id) {
        String status = jdbc.queryForObject(
                "SELECT status FROM agent.agent_tasks WHERE id = ?",
                String.class, id);
        assertThat(status).isEqualTo("cancelled");
    }
}
