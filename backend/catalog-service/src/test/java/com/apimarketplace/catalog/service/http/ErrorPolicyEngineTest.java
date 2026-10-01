package com.apimarketplace.catalog.service.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine decides one thing: what the reader is told when a provider refuses a call. It never
 * decides to re-send, so a refusal with no declared rule comes back as {@code null} and the call
 * site keeps the provider's own words. Every silent failure mode of the matching is pinned here.
 */
class ErrorPolicyEngineTest {

    private final ErrorPolicyEngine engine = new ErrorPolicyEngine();

    @Nested
    @DisplayName("No re-send, ever")
    class NoRetry {

        @Test
        @DisplayName("REGRESSION: a 429 with no declared rule yields no verdict, there is no built-in retry")
        void aBare429IsNotSpecial() {
            assertThat(engine.declaredMessage(429, "{\"error\":\"rate limited\"}", null)).isNull();
            assertThat(engine.declaredMessage(503, "{}", "[]")).isNull();
        }

        @Test
        @DisplayName("a stored legacy `retry` rule is skipped, and the rules after it still apply")
        void aLegacyRetryRuleIsSkipped() {
            // Rows imported before the retry was removed keep the rule until re-imported, and a
            // self-hosted install can receive one in a bundle from an older cloud. Its message
            // described a re-send that no longer happens, so it must not be shown either.
            String policy = """
                [{"match":{"bodyContains":"rate_limit_exceeded"},"action":"retry","waitMs":5000,
                  "message":"The platform already waited and re-sent the call once."},
                 {"match":{"status":429},"action":"user_error","message":"Rate limited."}]
                """;

            assertThat(engine.declaredMessage(429, "rate_limit_exceeded", policy)).isEqualTo("Rate limited.");
            assertThat(engine.declaredMessage(400, "rate_limit_exceeded", policy)).isNull();
        }

        @Test
        @DisplayName("the engine exposes nothing that could schedule a re-send")
        void noRetryKnobSurvives() {
            // A later change that brings back a retry budget or attempt counter here would bring
            // back the invisible re-send with it. Wording is the engine's whole job now.
            String[] publicMethods = Arrays.stream(ErrorPolicyEngine.class.getDeclaredMethods())
                    .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                    .map(Method::getName)
                    .toArray(String[]::new);

            assertThat(publicMethods).containsExactly("declaredMessage");
            assertThat(Arrays.stream(publicMethods).map(n -> n.toLowerCase(Locale.ROOT)))
                    .noneMatch(n -> n.contains("retry") || n.contains("wait"));
        }
    }

    @Nested
    @DisplayName("Declared rules")
    class DeclaredRules {

        @Test
        @DisplayName("turns a provider code into a message written for the reader")
        void mapsBodyToUserMessage() {
            String policy = """
                [{"match":{"bodyContains":"unaudited_client_can_only_post_to_private_accounts"},
                  "action":"user_error",
                  "message":"Your TikTok app is not audited yet: only SELF_ONLY posts are allowed."}]
                """;

            assertThat(engine.declaredMessage(400,
                    "{\"error\":{\"code\":\"unaudited_client_can_only_post_to_private_accounts\"}}", policy))
                    .contains("not audited");
        }

        @Test
        @DisplayName("matches the body case-insensitively")
        void matchesCaseInsensitively() {
            String policy = """
                [{"match":{"bodyContains":"spam_risk"},"action":"user_error","message":"Slow down."}]
                """;

            assertThat(engine.declaredMessage(400, "{\"code\":\"SPAM_RISK_TOO_MANY_POSTS\"}", policy))
                    .isEqualTo("Slow down.");
        }

        @Test
        @DisplayName("a user_error on a 5xx is legal: describing a failure re-sends nothing")
        void describesAServerError() {
            String policy = """
                [{"match":{"status":503},"action":"user_error","message":"Provider down."}]
                """;

            assertThat(engine.declaredMessage(503, "{}", policy)).isEqualTo("Provider down.");
        }

        @Test
        @DisplayName("an unknown action disables itself, not the rules after it")
        void oneBadRuleDoesNotDiscardTheRest() {
            // A bundle from a newer cloud can carry an action this build has no code for.
            String policy = """
                [{"match":{"status":400},"action":"disconnect_credential"},
                 {"match":{"status":400},"action":"user_error","message":"Readable."}]
                """;

            assertThat(engine.declaredMessage(400, "{}", policy)).isEqualTo("Readable.");
        }

        @Test
        @DisplayName("a malformed statusIn narrows the rule to nothing, it does not widen it")
        void malformedStatusInFailsClosed() {
            String policy = """
                [{"match":{"statusIn":"429","bodyContains":"limit"},"action":"user_error",
                  "message":"m"}]
                """;

            assertThat(engine.declaredMessage(429, "limit reached", policy)).isNull();
        }

        @Test
        @DisplayName("every criterion in a match must hold")
        void allCriteriaMustMatch() {
            String policy = """
                [{"match":{"status":403,"bodyContains":"quota"},"action":"user_error","message":"m"}]
                """;

            assertThat(engine.declaredMessage(403, "quota exceeded", policy)).isEqualTo("m");
            assertThat(engine.declaredMessage(400, "quota exceeded", policy)).isNull();
            assertThat(engine.declaredMessage(403, "something else", policy)).isNull();
        }

        @Test
        @DisplayName("statusIn matches any listed status, and a string status never matches")
        void statusInMatchesAnyListed() {
            String policy = """
                [{"match":{"statusIn":[400, 403, "429"]},"action":"user_error","message":"m"}]
                """;

            assertThat(engine.declaredMessage(400, "{}", policy)).isEqualTo("m");
            assertThat(engine.declaredMessage(403, "{}", policy)).isEqualTo("m");
            assertThat(engine.declaredMessage(429, "{}", policy)).isNull();
        }

        @Test
        @DisplayName("the first matching rule wins")
        void firstMatchWins() {
            String policy = """
                [{"match":{"status":400},"action":"user_error","message":"first"},
                 {"match":{"status":400},"action":"user_error","message":"second"}]
                """;

            assertThat(engine.declaredMessage(400, "{}", policy)).isEqualTo("first");
        }

        @Test
        @DisplayName("an empty match matches nothing")
        void emptyMatchMatchesNothing() {
            String policy = """
                [{"match":{},"action":"user_error","message":"m"}]
                """;

            assertThat(engine.declaredMessage(400, "{}", policy)).isNull();
        }

        @Test
        @DisplayName("an explicit null criterion narrows the rule to nothing, it does not widen it")
        void explicitNullCriterionFailsClosed() {
            String policy = """
                [{"match":{"status":null,"bodyContains":"limit"},"action":"user_error","message":"m"}]
                """;

            assertThat(engine.declaredMessage(429, "limit", policy)).isNull();
        }

        @Test
        @DisplayName("a blank message is no message: the rule is skipped and scanning goes on")
        void blankMessageIsSkipped() {
            String policy = """
                [{"match":{"status":400},"action":"user_error","message":"   "},
                 {"match":{"status":400},"action":"user_error"},
                 {"match":{"status":400},"action":"user_error","message":"Readable."}]
                """;

            assertThat(engine.declaredMessage(400, "{}", policy)).isEqualTo("Readable.");
        }

        @Test
        @DisplayName("a needle too short to identify an error code matches nothing")
        void shortNeedleMatchesNothing() {
            String policy = """
                [{"match":{"bodyContains":"no"},"action":"user_error","message":"m"}]
                """;

            assertThat(engine.declaredMessage(400, "no such thing", policy)).isNull();
        }

        @Test
        @DisplayName("a match that is missing or not an object matches nothing")
        void missingMatchMatchesNothing() {
            String policy = """
                [{"action":"user_error","message":"m"},
                 {"match":"400","action":"user_error","message":"m"}]
                """;

            assertThat(engine.declaredMessage(400, "{}", policy)).isNull();
        }

        @Test
        @DisplayName("a malformed or non-array policy is ignored")
        void malformedPolicyIsIgnored() {
            assertThat(engine.declaredMessage(400, "{}", "not json")).isNull();
            assertThat(engine.declaredMessage(400, "{}", "{\"match\":{\"status\":400}}")).isNull();
            assertThat(engine.declaredMessage(400, "{}", "  ")).isNull();
        }

        @Test
        @DisplayName("a null body never throws")
        void nullBodyIsSafe() {
            String policy = """
                [{"match":{"bodyContains":"limit"},"action":"user_error","message":"m"},
                 {"match":{"status":400},"action":"user_error","message":"by status"}]
                """;

            assertThat(engine.declaredMessage(400, null, policy)).isEqualTo("by status");
        }
    }
}
