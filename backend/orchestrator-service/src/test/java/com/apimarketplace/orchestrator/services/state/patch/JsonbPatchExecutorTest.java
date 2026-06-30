package com.apimarketplace.orchestrator.services.state.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JsonbPatchExecutor}. Verifies the SQL composition shape
 * (a bug-prone area), parameter binding (no SQL injection), and the
 * {@link jakarta.persistence.Query} interaction. Mocks {@link EntityManager}
 * to keep this test framework-light (no Spring context, no DB needed).
 */
class JsonbPatchExecutorTest {

    private EntityManager entityManager;
    private Query query;
    private JsonbPatchExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        executor = new JsonbPatchExecutor(new ObjectMapper());
        // EntityManager is @PersistenceContext-injected - assign via reflection for unit test
        Field emField = JsonbPatchExecutor.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(executor, entityManager);
    }

    private List<JsonbPatch> singlePatch() {
        return List.of(new JsonbPatch(new String[] { "seq" }, "42"));
    }

    private List<JsonbPatch> threePatches() {
        return List.of(
                new JsonbPatch(new String[] { "seq" }, "100"),
                new JsonbPatch(new String[] { "dags", "trigger:webhook", "epochs", "5", "completedNodeIds" },
                        "[\"node:a\"]"),
                new JsonbPatch(new String[] { "nodes", "node:a" },
                        "{\"completed\":1}")
        );
    }

    @Nested
    @DisplayName("applyPatches SQL composition")
    class SqlComposition {

        @Test
        @DisplayName("Single patch produces one jsonb_set wrapping state_snapshot")
        void singlePatchSql() {
            executor.applyPatches("run-1", singlePatch());

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(entityManager).createNativeQuery(sqlCaptor.capture());
            String sql = sqlCaptor.getValue();

            assertThat(sql).contains("UPDATE orchestrator.workflow_runs");
            assertThat(sql).contains("WHERE run_id_public = :runIdPublic");
            // Single jsonb_set wrapping CAST(state_snapshot AS jsonb) - column
            // is text in DB; we cast in/out around the jsonb_set call.
            assertThat(sql).contains("jsonb_set(CAST(state_snapshot AS jsonb), CAST(:p0 AS text[]), CAST(:v0 AS jsonb), true)");
            assertThat(sql).contains("CAST(jsonb_set(");
            assertThat(sql).contains("AS text)");
            // Verify it's NOT nested twice (single patch = single jsonb_set)
            int count = sql.length() - sql.replace("jsonb_set(", "").length();
            assertThat(count / "jsonb_set(".length()).isEqualTo(1);
        }

        @Test
        @DisplayName("Three patches produce nested jsonb_set with innermost = first patch")
        void threePatchesSqlOrder() {
            executor.applyPatches("run-1", threePatches());

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(entityManager).createNativeQuery(sqlCaptor.capture());
            String sql = sqlCaptor.getValue();

            // Three nested jsonb_set calls
            int count = (sql.length() - sql.replace("jsonb_set(", "").length()) / "jsonb_set(".length();
            assertThat(count).isEqualTo(3);

            // Innermost = first patch (p0/v0) wrapping state_snapshot directly.
            // Find positions of p0, p1, p2 in the SQL - innermost = leftmost in nested syntax.
            int posP0 = sql.indexOf(":p0");
            int posP1 = sql.indexOf(":p1");
            int posP2 = sql.indexOf(":p2");
            assertThat(posP0).isLessThan(posP1);
            assertThat(posP1).isLessThan(posP2);
            // p0 sits next to CAST(state_snapshot AS jsonb) (the innermost expression)
            assertThat(sql).contains("jsonb_set(CAST(state_snapshot AS jsonb), CAST(:p0 AS text[])");
        }
    }

    @Nested
    @DisplayName("Parameter binding")
    class ParameterBinding {

        @Test
        @DisplayName("Binds runIdPublic + path/value pairs by indexed name")
        void bindsRunIdAndIndexedParams() {
            executor.applyPatches("run-42", threePatches());

            verify(query).setParameter(eq("runIdPublic"), eq("run-42"));
            // Each patch contributes p_i (path text[] literal) and v_i (json string)
            verify(query).setParameter(eq("p0"), eq(threePatches().get(0).toPostgresArrayLiteral()));
            verify(query).setParameter(eq("v0"), eq("100"));
            verify(query).setParameter(eq("p1"), eq(threePatches().get(1).toPostgresArrayLiteral()));
            verify(query).setParameter(eq("v1"), eq("[\"node:a\"]"));
            verify(query).setParameter(eq("p2"), eq(threePatches().get(2).toPostgresArrayLiteral()));
            verify(query).setParameter(eq("v2"), eq("{\"completed\":1}"));
            verify(query).executeUpdate();
            verifyNoMoreInteractions(query);
        }

        @Test
        @DisplayName("Path with colon in triggerId binds as quoted text[] literal")
        void colonInTriggerIdBindsAsQuotedLiteral() {
            executor.applyPatches("run-42", threePatches());

            // Verify the path literal for the completedNodeIds patch contains "trigger:webhook"
            ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
            verify(query).setParameter(eq("p1"), valueCaptor.capture());
            assertThat(valueCaptor.getValue())
                    .isEqualTo("{\"dags\",\"trigger:webhook\",\"epochs\",\"5\",\"completedNodeIds\"}");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Rejects null patch list")
        void rejectsNull() {
            assertThatThrownBy(() -> executor.applyPatches("r", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or empty");
        }

        @Test
        @DisplayName("Rejects empty patch list")
        void rejectsEmpty() {
            assertThatThrownBy(() -> executor.applyPatches("r", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or empty");
        }

        @Test
        @DisplayName("Returns 0 when no rows updated (deleted run)")
        void returnsZeroWhenRunDeleted() {
            when(query.executeUpdate()).thenReturn(0);

            int updated = executor.applyPatches("run-deleted", singlePatch());

            assertThat(updated).isZero();
        }

        @Test
        @DisplayName("Returns 1 when row updated (happy path)")
        void returnsOneOnSuccess() {
            int updated = executor.applyPatches("run-1", singlePatch());

            assertThat(updated).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("estimatePayloadBytes")
    class PayloadBytes {

        @Test
        @DisplayName("Sums path-literal length + JSON value length per patch")
        void sumsPathPlusValueBytes() {
            JsonbPatch p = new JsonbPatch(new String[] { "seq" }, "12345");
            // path literal = {"seq"} = 7 chars; jsonValue = "12345" = 5 chars → 12 total
            long bytes = executor.estimatePayloadBytes(List.of(p));
            assertThat(bytes).isEqualTo(7 + 5);
        }

        @Test
        @DisplayName("Sums across multiple patches")
        void sumsAcrossPatches() {
            long bytes = executor.estimatePayloadBytes(threePatches());
            long expected = 0L;
            for (JsonbPatch p : threePatches()) {
                expected += p.toPostgresArrayLiteral().length();
                expected += p.jsonValue().length();
            }
            assertThat(bytes).isEqualTo(expected);
        }
    }
}
