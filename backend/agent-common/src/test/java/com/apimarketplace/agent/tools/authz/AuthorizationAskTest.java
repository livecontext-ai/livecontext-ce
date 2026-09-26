package com.apimarketplace.agent.tools.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity of one request for permission, which three services have to agree on.
 *
 * <p>orchestrator writes it on the request row, conversation records the grant that carries it,
 * and agent-service recomputes it from the call to decide whether that grant covers it. The
 * failure mode of a disagreement is not an error anywhere: it is a grant that never matches, so
 * the agent asks again and the person who pressed Approve is asked the same question tomorrow,
 * with nothing in any log saying why.
 */
@DisplayName("authorization ask - what makes two requests the same request")
class AuthorizationAskTest {

    private static final String RULE = "publish_post";

    @Nested
    @DisplayName("material")
    class Material {

        @Test
        @DisplayName("is the same for the same arguments written in a different order")
        void argumentOrderDoesNotMatter() {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("post_id", "september");
            one.put("channel", "linkedin");
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("channel", "linkedin");
            other.put("post_id", "september");

            // An LLM does not emit its arguments in a stable order. If that decided identity,
            // the duplicate rule would let the same question through twice at random.
            assertThat(AuthorizationAsk.material("social", one))
                    .isEqualTo(AuthorizationAsk.material("social", other));
        }

        @Test
        @DisplayName("separates two calls that differ only in one argument")
        void differentArgumentsAreDifferentAsks() {
            assertThat(AuthorizationAsk.material("social", Map.of("post_id", "september")))
                    .isNotEqualTo(AuthorizationAsk.material("social", Map.of("post_id", "october")));
        }

        @Test
        @DisplayName("separates two tools called with the same arguments")
        void theToolIsPartOfTheAsk() {
            assertThat(AuthorizationAsk.material("social", Map.of("id", "1")))
                    .isNotEqualTo(AuthorizationAsk.material("email", Map.of("id", "1")));
        }

        @Test
        @DisplayName("survives a call with no arguments at all")
        void noArguments() {
            assertThat(AuthorizationAsk.material("social", null)).isEqualTo("social");
            assertThat(AuthorizationAsk.material(null, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("fingerprint")
    class Fingerprint {

        @Test
        @DisplayName("is bounded whatever the arguments looked like")
        void isBounded() {
            String huge = "x".repeat(50_000);

            // It is stored in a column and repeated in a grant list, and an argument map has no
            // length anyone controls.
            assertThat(AuthorizationAsk.fingerprint(RULE, huge)).hasSize(32);
        }

        @Test
        @DisplayName("is URL-safe, so it survives everywhere it is carried")
        void isUrlSafe() {
            String fingerprint = AuthorizationAsk.fingerprint(RULE, "post_id=september");

            // It travels in JSON, in a log line and in a grant entry read back by string match.
            assertThat(fingerprint).matches("[A-Za-z0-9_-]{32}");
        }

        @Test
        @DisplayName("separates the same call made under two different rules")
        void theRuleIsPartOfIt() {
            String material = AuthorizationAsk.material("social", Map.of("id", "1"));

            assertThat(AuthorizationAsk.fingerprint("publish_post", material))
                    .isNotEqualTo(AuthorizationAsk.fingerprint("delete_post", material));
        }

        @Test
        @DisplayName("is the same value whether built in one step or two")
        void oneStepMatchesTwo() {
            Map<String, Object> args = Map.of("post_id", "september");

            // The two sides of this feature use different entry points: orchestrator has the
            // material as a string, agent-service has the call. They must land on one value.
            assertThat(AuthorizationAsk.fingerprintOfCall(RULE, "social", args))
                    .isEqualTo(AuthorizationAsk.fingerprint(RULE, AuthorizationAsk.material("social", args)));
        }
    }

    @Nested
    @DisplayName("authorizes")
    class Authorizes {

        private final String fingerprint = AuthorizationAsk.fingerprintOfCall(RULE, "social",
                Map.of("post_id", "september"));
        private final String otherFingerprint = AuthorizationAsk.fingerprintOfCall(RULE, "social",
                Map.of("post_id", "october"));

        @Test
        @DisplayName("lets through the exact ask a scoped grant was written for")
        void scopedGrantCoversItsOwnAsk() {
            List<String> grants = List.of(AuthorizationAsk.scopedGrant(RULE, fingerprint));

            assertThat(AuthorizationAsk.authorizes(grants, RULE, fingerprint)).isTrue();
        }

        @Test
        @DisplayName("refuses a different call of the same rule")
        void scopedGrantDoesNotCoverAnotherAsk() {
            List<String> grants = List.of(AuthorizationAsk.scopedGrant(RULE, fingerprint));

            // The reason this class exists. Somebody approved "publish the September report"
            // from a chat; the run that spends the grant is a scheduled one nobody is watching,
            // and it must not be able to publish October under that answer.
            assertThat(AuthorizationAsk.authorizes(grants, RULE, otherFingerprint)).isFalse();
        }

        @Test
        @DisplayName("refuses a call whose ask cannot be computed")
        void scopedGrantNeedsAnAsk() {
            List<String> grants = List.of(AuthorizationAsk.scopedGrant(RULE, fingerprint));

            // Without a fingerprint there is nothing to match, and "cannot tell" is not a
            // reason to run a sensitive action.
            assertThat(AuthorizationAsk.authorizes(grants, RULE, null)).isFalse();
        }

        @Test
        @DisplayName("still honours a bare rule grant")
        void bareRuleStillWorks() {
            // Every grant written before this existed, and every in-app approval since.
            assertThat(AuthorizationAsk.authorizes(List.of(RULE), RULE, fingerprint)).isTrue();
            assertThat(AuthorizationAsk.authorizes(List.of(RULE), RULE, null)).isTrue();
        }

        @Test
        @DisplayName("still honours the conversation-wide wildcard")
        void wildcardStillWorks() {
            // The person turned off being asked, for this whole conversation, on purpose.
            assertThat(AuthorizationAsk.authorizes(List.of("*"), RULE, otherFingerprint)).isTrue();
        }

        @Test
        @DisplayName("refuses when nothing was granted")
        void nothingGranted() {
            assertThat(AuthorizationAsk.authorizes(null, RULE, fingerprint)).isFalse();
            assertThat(AuthorizationAsk.authorizes(List.of(), RULE, fingerprint)).isFalse();
        }

        @Test
        @DisplayName("refuses a call that matches no rule")
        void noRuleToMatch() {
            List<String> grants = List.of(AuthorizationAsk.scopedGrant(RULE, fingerprint));

            // A call the guard did not classify is not a sensitive call, but if it reaches
            // here it must not be covered by somebody else's answer.
            assertThat(AuthorizationAsk.authorizes(grants, null, fingerprint)).isFalse();
        }
    }

    @Nested
    @DisplayName("scopedGrant")
    class ScopedGrant {

        @Test
        @DisplayName("falls back to the bare rule when there is no ask to scope to")
        void noFingerprintKeepsTheRule() {
            // The in-app case: the person is present and the call they approved resumes in
            // place, so narrowing it would change a behaviour nobody complained about.
            assertThat(AuthorizationAsk.scopedGrant(RULE, null)).isEqualTo(RULE);
            assertThat(AuthorizationAsk.scopedGrant(RULE, "  ")).isEqualTo(RULE);
        }

        @Test
        @DisplayName("is nothing at all without a rule")
        void noRuleNoGrant() {
            assertThat(AuthorizationAsk.scopedGrant(null, "abc")).isNull();
            assertThat(AuthorizationAsk.scopedGrant(" ", "abc")).isNull();
        }

        @Test
        @DisplayName("keeps the rule readable at the front of the entry")
        void staysReadable() {
            String grant = AuthorizationAsk.scopedGrant(RULE, "abc");

            // These end up in a stored JSON list somebody will read while debugging a
            // permission that did not apply. The rule has to be the first thing they see.
            assertThat(grant).startsWith(RULE + "#").endsWith("abc");
        }
    }
}
