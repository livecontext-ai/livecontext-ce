package com.apimarketplace.orchestrator.services.purge;

import com.apimarketplace.auth.client.AuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The statements auth-service used to run cross-schema for this schema, now issued by their
 * owner. The old purger's anti-drift test asserted every declared table was hit with an
 * org-scoped, type-safe predicate; this test carries that contract over for the orchestrator
 * slice, plus the cursor's monotonic advance that makes concurrent passes safe.
 */
@DisplayName("OrchestratorPurgeFollower")
class OrchestratorPurgeFollowerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private JdbcTemplate jdbc;
    private OrchestratorPurgeFollower follower;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        follower = new OrchestratorPurgeFollower(jdbc, mock(AuthClient.class), false);
    }

    private List<String> deletesFor(Runnable call) {
        call.run();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), eq(ORG));
        return sql.getAllValues();
    }

    @Test
    @DisplayName("Deletes every declared table, children before parents, each org-scoped and cast")
    void deletesEveryDeclaredTableInOrder() {
        List<String> sql = deletesFor(() -> follower.purgeOrganization(ORG));

        for (String table : OrchestratorPurgeFollower.ORG_TABLES) {
            assertThat(sql).as("declared table %s", table).anyMatch(s -> s.startsWith("DELETE FROM " + table + " "));
        }
        assertThat(sql.get(0)).startsWith("DELETE FROM orchestrator.workflow_step_data")
                .contains("SELECT id::text FROM orchestrator.workflow_runs WHERE organization_id::text = ?");
        assertThat(sql.indexOf(sql.stream().filter(s -> s.contains("workflow_runs WHERE")).findFirst().orElseThrow()))
                .isLessThan(sql.indexOf(sql.stream().filter(s -> s.contains("orchestrator.workflows WHERE")).findFirst().orElseThrow()));
        for (String s : sql) {
            assertThat(s).as("own schema only: %s", s).doesNotContainPattern("\\b(auth|storage|agent|datasource|conversation|catalog|interface|trigger|publication)\\.");
            assertThat(s).as("org-scoped and cast: %s", s).contains("organization_id::text = ?");
        }
    }

    @Test
    @DisplayName("A USER purge deletes that person's personal rows (notifications, monthly recaps), and only by their id")
    void userPurgeDeletesOnlyNotificationRows() {
        follower.purgeUser("42");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), eq("42"));
        assertThat(sql.getAllValues()).hasSize(OrchestratorPurgeFollower.USER_TABLES.size());
        for (String table : OrchestratorPurgeFollower.USER_TABLES) {
            assertThat(sql.getAllValues()).as("declared user table %s", table)
                    .contains("DELETE FROM " + table + " WHERE tenant_id = ?");
        }
    }

    @Test
    @DisplayName("The cursor lives in this schema and only ever moves forward")
    void cursorIsMonotonicAndLocal() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(17L);

        assertThat(follower.read()).isEqualTo(17L);
        follower.advanceTo(20L);

        verify(jdbc).queryForObject(eq("SELECT last_seq FROM orchestrator.purge_cursor WHERE id = 1"), eq(Long.class));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(20L));
        assertThat(sql.getValue()).contains("orchestrator.purge_cursor").contains("GREATEST(last_seq, ?)");
    }

    @Test
    @DisplayName("A missing cursor row reads as the beginning of the log, not as a crash")
    void missingCursorReadsAsZero() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
        assertThat(follower.read()).isZero();
    }
}
