package com.apimarketplace.orchestrator.services.state.patch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonbPatchTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Rejects null path")
        void rejectsNullPath() {
            assertThatThrownBy(() -> new JsonbPatch(null, "\"x\""))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Rejects empty path")
        void rejectsEmptyPath() {
            assertThatThrownBy(() -> new JsonbPatch(new String[0], "\"x\""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("Rejects path element containing comma (Postgres array separator)")
        void rejectsPathElementWithComma() {
            assertThatThrownBy(() -> new JsonbPatch(new String[] { "a,b" }, "\"x\""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not contain ','");
        }

        @Test
        @DisplayName("Rejects null jsonValue")
        void rejectsNullJsonValue() {
            assertThatThrownBy(() -> new JsonbPatch(new String[] { "a" }, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("toPostgresArrayLiteral")
    class PostgresArrayLiteral {

        @Test
        @DisplayName("Single key wraps in quoted braces")
        void singleKey() {
            JsonbPatch patch = new JsonbPatch(new String[] { "seq" }, "42");
            assertThat(patch.toPostgresArrayLiteral()).isEqualTo("{\"seq\"}");
        }

        @Test
        @DisplayName("Multi-element path with colon in trigger key (the StateSnapshot canonical case)")
        void multiElementWithColon() {
            JsonbPatch patch = new JsonbPatch(
                    new String[] { "dags", "trigger:webhook", "epochs", "5", "completedNodeIds" },
                    "[\"a\"]");
            // Postgres text[] uses , as separator; : inside a key is opaque (no escape needed
            // since we wrap in double quotes), and double-quote wrapping is defensive.
            assertThat(patch.toPostgresArrayLiteral())
                    .isEqualTo("{\"dags\",\"trigger:webhook\",\"epochs\",\"5\",\"completedNodeIds\"}");
        }

        @Test
        @DisplayName("Escapes embedded backslash")
        void escapesBackslash() {
            JsonbPatch patch = new JsonbPatch(new String[] { "a\\b" }, "\"x\"");
            assertThat(patch.toPostgresArrayLiteral()).isEqualTo("{\"a\\\\b\"}");
        }

        @Test
        @DisplayName("Escapes embedded double quote")
        void escapesDoubleQuote() {
            JsonbPatch patch = new JsonbPatch(new String[] { "a\"b" }, "\"x\"");
            assertThat(patch.toPostgresArrayLiteral()).isEqualTo("{\"a\\\"b\"}");
        }
    }

    @Nested
    @DisplayName("equals/hashCode")
    class Equality {

        @Test
        @DisplayName("Same path and value are equal")
        void sameValuesEqual() {
            JsonbPatch a = new JsonbPatch(new String[] { "x", "y" }, "1");
            JsonbPatch b = new JsonbPatch(new String[] { "x", "y" }, "1");
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different paths are not equal")
        void differentPathsNotEqual() {
            JsonbPatch a = new JsonbPatch(new String[] { "x" }, "1");
            JsonbPatch b = new JsonbPatch(new String[] { "y" }, "1");
            assertThat(a).isNotEqualTo(b);
        }
    }
}
