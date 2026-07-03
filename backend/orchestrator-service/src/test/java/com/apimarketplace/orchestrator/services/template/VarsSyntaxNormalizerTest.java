package com.apimarketplace.orchestrator.services.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VarsSyntaxNormalizer.
 *
 * The normalizer rewrites the two author-facing workflow-variable forms
 * ($vars.name canonical, vars:name alias) to the single internal form
 * vars.name, skipping SpEL protected regions (string literals), and returns
 * the SAME instance when the input contains neither form.
 */
@DisplayName("VarsSyntaxNormalizer")
class VarsSyntaxNormalizerTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // Canonical form: $vars.name
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Canonical form $vars.name")
    class CanonicalFormTests {

        @Test
        @DisplayName("Should rewrite $vars.name to vars.name")
        void shouldRewriteDollarVarsToVarsDot() {
            // Act
            String result = VarsSyntaxNormalizer.normalize("$vars.api_url");

            // Assert
            assertEquals("vars.api_url", result);
        }

        @Test
        @DisplayName("Should rewrite $vars.name embedded in a larger expression")
        void shouldRewriteDollarVarsInsideExpression() {
            String result = VarsSyntaxNormalizer.normalize("$vars.count > 3 && $vars.enabled");

            assertEquals("vars.count > 3 && vars.enabled", result);
        }

        @Test
        @DisplayName("Should rewrite deep paths after $vars.")
        void shouldRewriteDeepPaths() {
            String result = VarsSyntaxNormalizer.normalize("$vars.config.api.url");

            assertEquals("vars.config.api.url", result);
        }

        @Test
        @DisplayName("Should leave bare $vars (no dot) untouched and return same instance")
        void shouldLeaveBareDollarVarsUntouched() {
            String input = "$vars";

            String result = VarsSyntaxNormalizer.normalize(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("Should not rewrite $vars. when not followed by an identifier start")
        void shouldNotRewriteDollarVarsDotWithoutIdentifier() {
            // "$vars.1" - digit after the dot is not an identifier start
            String result = VarsSyntaxNormalizer.normalize("$vars.1 + $vars.name");

            assertEquals("$vars.1 + vars.name", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Alias form: vars:name
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Alias form vars:name")
    class AliasFormTests {

        @Test
        @DisplayName("Should rewrite vars:name to vars.name")
        void shouldRewriteVarsColonToVarsDot() {
            String result = VarsSyntaxNormalizer.normalize("vars:api_url");

            assertEquals("vars.api_url", result);
        }

        @Test
        @DisplayName("Should rewrite vars:name embedded in a larger expression")
        void shouldRewriteVarsColonInsideExpression() {
            String result = VarsSyntaxNormalizer.normalize("vars:n == 5");

            assertEquals("vars.n == 5", result);
        }

        @Test
        @DisplayName("Should not rewrite envvars: (word char before vars:)")
        void shouldNotRewriteEnvvarsPrefix() {
            String result = VarsSyntaxNormalizer.normalize("envvars:x");

            assertEquals("envvars:x", result);
        }

        @Test
        @DisplayName("Should not rewrite vars: when preceded by a colon")
        void shouldNotRewriteVarsColonAfterColon() {
            String result = VarsSyntaxNormalizer.normalize("ns:vars:x");

            assertEquals("ns:vars:x", result);
        }

        @Test
        @DisplayName("Should not rewrite vars: when not followed by an identifier start")
        void shouldNotRewriteVarsColonWithoutIdentifier() {
            String result = VarsSyntaxNormalizer.normalize("vars:1 and vars:name");

            assertEquals("vars:1 and vars.name", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Untouched inputs and same-instance guarantee
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Untouched inputs")
    class UntouchedInputTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(VarsSyntaxNormalizer.normalize(null));
        }

        @Test
        @DisplayName("Should return same instance when input contains neither form")
        void shouldReturnSameInstanceWhenNoVarsForm() {
            String input = "mcp:api_call.output.data > 3";

            String result = VarsSyntaxNormalizer.normalize(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("Should leave core:vars.x untouched (node named vars) and return same instance")
        void shouldLeaveCoreVarsUntouched() {
            String input = "core:vars.output.result";

            String result = VarsSyntaxNormalizer.normalize(input);

            assertSame(input, result);
        }

        @Test
        @DisplayName("Should return same instance for empty string")
        void shouldReturnSameInstanceForEmptyString() {
            String input = "";

            String result = VarsSyntaxNormalizer.normalize(input);

            assertSame(input, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Protected regions (SpEL string literals)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Protected regions")
    class ProtectedRegionTests {

        @Test
        @DisplayName("Should leave $vars.x untouched inside a single-quoted string literal")
        void shouldNotRewriteInsideSingleQuotedLiteral() {
            String result = VarsSyntaxNormalizer.normalize("'$vars.x'");

            assertEquals("'$vars.x'", result);
        }

        @Test
        @DisplayName("Should leave vars:x untouched inside a single-quoted string literal")
        void shouldNotRewriteAliasInsideSingleQuotedLiteral() {
            String result = VarsSyntaxNormalizer.normalize("'vars:x'");

            assertEquals("'vars:x'", result);
        }

        @Test
        @DisplayName("Should rewrite occurrences outside literals while protecting those inside")
        void shouldRewriteOutsideButProtectInsideLiterals() {
            String result = VarsSyntaxNormalizer.normalize("concat('$vars.a', $vars.b)");

            assertEquals("concat('$vars.a', vars.b)", result);
        }

        @Test
        @DisplayName("Should rewrite an occurrence that follows a closed string literal")
        void shouldRewriteAfterClosedLiteral() {
            String result = VarsSyntaxNormalizer.normalize("'prefix-' + $vars.name");

            assertEquals("'prefix-' + vars.name", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Mixed occurrences
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Mixed occurrences")
    class MixedOccurrenceTests {

        @Test
        @DisplayName("Should rewrite both forms in the same expression")
        void shouldRewriteBothFormsTogether() {
            String result = VarsSyntaxNormalizer.normalize("$vars.a + vars:b");

            assertEquals("vars.a + vars.b", result);
        }

        @Test
        @DisplayName("Should rewrite repeated occurrences of the same form")
        void shouldRewriteRepeatedOccurrences() {
            String result = VarsSyntaxNormalizer.normalize("$vars.a + $vars.a");

            assertEquals("vars.a + vars.a", result);
        }
    }
}
