package com.apimarketplace.orchestrator.services.state.patch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan v4 §2b - JsonbPatch DELTA semantics + opKind-aware SQL composition.
 */
@DisplayName("Plan v4 §2b - JsonbPatch DELTA + SQL composer")
class JsonbPatchDeltaTest {

    @Nested
    @DisplayName("JsonbPatch factories + op-kind")
    class Factories {

        @Test
        @DisplayName("assignment() factory produces ASSIGN op")
        void assignmentFactory() {
            var p = JsonbPatch.assignment(new String[]{"seq"}, "42");
            assertThat(p.opKind()).isEqualTo(JsonbPatch.OpKind.ASSIGN);
            assertThat(p.jsonValue()).isEqualTo("42");
        }

        @Test
        @DisplayName("commutativeDelta() factory produces DELTA op with integer-encoded value")
        void deltaFactory() {
            var p = JsonbPatch.commutativeDelta(new String[]{"nodes", "X", "completed"}, 5);
            assertThat(p.opKind()).isEqualTo(JsonbPatch.OpKind.COMMUTATIVE_DELTA);
            assertThat(p.jsonValue()).isEqualTo("5");
        }

        @Test
        @DisplayName("commutativeDelta accepts negative deltas")
        void deltaNegative() {
            var p = JsonbPatch.commutativeDelta(new String[]{"x"}, -3);
            assertThat(p.jsonValue()).isEqualTo("-3");
        }

        @Test
        @DisplayName("Back-compat constructor defaults to ASSIGN")
        void backCompatConstructor() {
            var p = new JsonbPatch(new String[]{"seq"}, "42");
            assertThat(p.opKind()).isEqualTo(JsonbPatch.OpKind.ASSIGN);
        }

        @Test
        @DisplayName("COMMUTATIVE_DELTA with non-integer jsonValue → IllegalArgumentException")
        void deltaRejectsNonInteger() {
            assertThatThrownBy(() ->
                    new JsonbPatch(new String[]{"x"}, "not-a-number", JsonbPatch.OpKind.COMMUTATIVE_DELTA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a decimal integer");
        }

        @Test
        @DisplayName("equals/hashCode differentiate op-kinds at the same path+value")
        void opKindDifferentiates() {
            var assign = JsonbPatch.assignment(new String[]{"x"}, "1");
            var delta = JsonbPatch.commutativeDelta(new String[]{"x"}, 1);
            assertThat(assign).isNotEqualTo(delta);
            assertThat(assign.hashCode()).isNotEqualTo(delta.hashCode());
        }
    }

    @Nested
    @DisplayName("opKind-aware SQL composer")
    class SqlComposer {

        @Test
        @DisplayName("ASSIGN-only patch list → uses CAST(:vN AS jsonb) literal write")
        void assignOnlySql() {
            var patches = List.of(JsonbPatch.assignment(new String[]{"seq"}, "42"));
            String sql = JsonbPatchExecutor.composeUpdateSql(patches, true, false);
            assertThat(sql)
                    .contains("CAST(:v0 AS jsonb)")
                    .doesNotContain("to_jsonb(COALESCE")
                    .contains("state_snapshot_seq = :newSeq");
        }

        @Test
        @DisplayName("Single DELTA patch → uses to_jsonb(COALESCE((...)::bigint, 0) + CAST(:v AS bigint))")
        void deltaSqlShape() {
            var patches = List.of(JsonbPatch.commutativeDelta(new String[]{"nodes", "X", "completed"}, 5));
            String sql = JsonbPatchExecutor.composeUpdateSql(patches, true, false);
            assertThat(sql)
                    .contains("to_jsonb(COALESCE((CAST(state_snapshot AS jsonb)#>>CAST(:p0 AS text[]))::bigint, 0)"
                            + " + CAST(:v0 AS bigint))")
                    .contains("state_snapshot_seq = :newSeq");
        }

        @Test
        @DisplayName("Mixed DELTA + ASSIGN → each patch gets its own value-expr per op-kind")
        void mixedSql() {
            var patches = List.of(
                    JsonbPatch.commutativeDelta(new String[]{"nodes", "X", "completed"}, 1),
                    JsonbPatch.assignment(new String[]{"nodes", "X", "last_completed_at"}, "\"2026-05-11T10:00:00Z\"")
            );
            String sql = JsonbPatchExecutor.composeUpdateSql(patches, false, false);
            assertThat(sql)
                    .contains("to_jsonb(COALESCE((CAST(state_snapshot AS jsonb)#>>CAST(:p0 AS text[]))::bigint, 0)"
                            + " + CAST(:v0 AS bigint))")
                    .contains("CAST(:v1 AS jsonb)")
                    .doesNotContain("state_snapshot_seq = :newSeq");  // includeSeq=false
        }

        @Test
        @DisplayName("withCasPredicate=true adds AND state_snapshot_seq = :expectedSeq")
        void casPredicate() {
            var patches = List.of(JsonbPatch.commutativeDelta(new String[]{"x"}, 1));
            String sql = JsonbPatchExecutor.composeUpdateSql(patches, true, true);
            assertThat(sql).endsWith("AND state_snapshot_seq = :expectedSeq");
        }

        @Test
        @DisplayName("Empty list → IllegalArgumentException")
        void emptyListRejected() {
            assertThatThrownBy(() -> JsonbPatchExecutor.composeUpdateSql(List.of(), true, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-empty");
        }
    }
}
