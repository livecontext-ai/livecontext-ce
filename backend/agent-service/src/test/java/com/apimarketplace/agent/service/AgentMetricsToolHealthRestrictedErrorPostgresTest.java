package com.apimarketplace.agent.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the cross-tenant tool-health view leaking Gmail / Drive text to staff.
 *
 * <p>{@code GET /api/agents/tool-health} is admin-only and deliberately crosses tenants. Its
 * {@code sampleError} was the newest error message of a tool across every tenant, whatever the
 * call had read, so a provider error quoting a Gmail search or a message the agent had just read
 * reached a platform administrator: human access to restricted-scope data, which Limited Use
 * forbids. Runs the real SQL of {@link AgentMetricsQueryService#globalToolHealthSql} on Postgres,
 * because the leak lives in an aggregate FILTER and a JOIN that only a database evaluates.
 */
// Named *Test, not *IT: this module runs surefire with default includes and has no failsafe.
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Tool health (admin, cross-tenant) never returns error text from restricted data")
class AgentMetricsToolHealthRestrictedErrorPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void schema() {
        jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        // Only the columns the query reads, with the production defaults for data_sensitivity.
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_executions (
                    id UUID PRIMARY KEY,
                    data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL')""");
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_execution_tool_calls (
                    id UUID PRIMARY KEY,
                    execution_id UUID,
                    tenant_id VARCHAR(255),
                    tool_name VARCHAR(255) NOT NULL,
                    arguments JSONB,
                    success BOOLEAN NOT NULL,
                    error_message TEXT,
                    data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                    created_at TIMESTAMPTZ NOT NULL)""");
    }

    @BeforeEach
    void clean() {
        jdbc.getJdbcTemplate().execute("TRUNCATE agent_execution_tool_calls, agent_executions");
    }

    @Test
    @DisplayName("a failure tagged RESTRICTED never becomes the sample, even when it is the newest")
    void restrictedCallErrorIsWithheld() {
        UUID normalExec = execution("NORMAL");
        UUID restrictedExec = execution("RESTRICTED");
        call(normalExec, "t1", "gmail", false, "401 invalid_grant", "NORMAL", 10);
        call(restrictedExec, "t2", "gmail", false, "Message 'Q3 layoffs plan' not found", "RESTRICTED", 1);

        Map<String, Object> row = single(run(false));

        assertThat(row.get("sample_error")).isEqualTo("401 invalid_grant");
        // The withheld call still counts: the spread across tenants is what the view is for.
        assertThat(((Number) row.get("failure_count")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("tenants_affected")).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("a call tagged NORMAL inside a RESTRICTED execution is withheld too (it can quote what the agent read)")
    void normalCallOfRestrictedExecutionIsWithheld() {
        UUID restrictedExec = execution("RESTRICTED");
        call(restrictedExec, "t1", "slack", false, "channel_not_found: quoted 'Q3 layoffs plan'", "NORMAL", 1);

        Map<String, Object> row = single(run(false));

        assertThat(row.get("sample_error")).isNull();
        assertThat(((Number) row.get("failure_count")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("REDACTED rows and executions are withheld, and an unknown tag fails closed")
    void redactedAndUnknownTagsAreWithheld() {
        call(execution("REDACTED"), "t1", "gmail", false, "left over text", "NORMAL", 3);
        call(execution("NORMAL"), "t2", "gmail", false, "left over text 2", "REDACTED", 2);
        call(execution("NORMAL"), "t3", "gmail", false, "left over text 3", "SOMETHING_NEW", 1);

        assertThat(single(run(false)).get("sample_error")).isNull();
    }

    @Test
    @DisplayName("an ordinary failure keeps its sample, with or without an execution row, in the windowed query too")
    void normalFailuresStillCarryTheirSample() {
        call(null, "t1", "github", false, "404 Not Found", "NORMAL", 1);
        call(execution("NORMAL"), "t2", "github", true, null, "NORMAL", 2);

        Map<String, Object> all = single(run(false));
        Map<String, Object> windowed = single(run(true));

        assertThat(all.get("sample_error")).isEqualTo("404 Not Found");
        assertThat(windowed.get("sample_error")).isEqualTo("404 Not Found");
        assertThat(((Number) all.get("total_calls")).longValue()).isEqualTo(2);
    }

    private static UUID execution(String sensitivity) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_executions (id, data_sensitivity) VALUES (:id, :s)",
                new MapSqlParameterSource().addValue("id", id).addValue("s", sensitivity));
        return id;
    }

    private static void call(UUID executionId, String tenant, String api, boolean success, String error,
                             String sensitivity, int minutesAgo) {
        jdbc.update("""
                INSERT INTO agent_execution_tool_calls
                    (id, execution_id, tenant_id, tool_name, arguments, success, error_message, data_sensitivity, created_at)
                VALUES (:id, :exec, :tenant, 'catalog', CAST(:args AS JSONB), :success, :error, :s,
                        NOW() - CAST(:ago || ' minutes' AS INTERVAL))""",
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("exec", executionId)
                        .addValue("tenant", tenant)
                        .addValue("args", "{\"api\":\"" + api + "\"}")
                        .addValue("success", success)
                        .addValue("error", error)
                        .addValue("s", sensitivity)
                        .addValue("ago", String.valueOf(minutesAgo)));
    }

    private static List<Map<String, Object>> run(boolean windowed) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("minCalls", 1L);
        if (windowed) {
            params.addValue("sinceDays", "30");
        }
        return jdbc.queryForList(AgentMetricsQueryService.globalToolHealthSql(windowed), params);
    }

    private static Map<String, Object> single(List<Map<String, Object>> rows) {
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
