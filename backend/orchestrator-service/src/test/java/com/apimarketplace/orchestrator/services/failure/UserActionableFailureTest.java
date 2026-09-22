package com.apimarketplace.orchestrator.services.failure;

import com.apimarketplace.common.credit.ChatCreditRefusal;
import com.apimarketplace.orchestrator.services.credit.CreditExhaustion;
import com.apimarketplace.orchestrator.services.plan.NodePlanGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classifier that keeps a customer's own condition out of the error dashboard.
 *
 * <p>Regression context: on 2026-09-16 one workspace sitting at 0.55 credits since the 13th
 * produced eight orchestrator ERROR lines in three minutes, because its webhook caller retried a
 * run the credit gate kept refusing. Nothing was broken; the level was wrong.
 */
@DisplayName("UserActionableFailure")
class UserActionableFailureTest {

    @Nested
    @DisplayName("what the tenant can fix is recognised")
    class Recognised {

        @Test
        @DisplayName("out of credits, by message and by error code, even when a caller prefixes it")
        void creditExhaustion() {
            assertThat(UserActionableFailure.isUserActionable(CreditExhaustion.MESSAGE)).isTrue();
            assertThat(UserActionableFailure.isUserActionable(CreditExhaustion.ERROR_CODE)).isTrue();
            // ReusableTriggerService's own catch wraps the node error in this prefix, and that
            // wrapped form is what reached the log as ERROR in production.
            assertThat(UserActionableFailure.isUserActionable(
                "V2 execution failed: " + CreditExhaustion.MESSAGE)).isTrue();
        }

        @Test
        @DisplayName("a plan upgrade is the customer's call too, so it is not an error either")
        void planUpgradeRequired() {
            assertThat(UserActionableFailure.isUserActionable(NodePlanGate.ERROR_CODE)).isTrue();
            assertThat(UserActionableFailure.isUserActionable(
                "Node failed: " + NodePlanGate.ERROR_CODE + " (needs PRO)")).isTrue();
        }

        /**
         * The five node refusals, quoted verbatim from the nodes that produce them. Pinned here
         * rather than referenced, because they are inline user-facing literals: re-wording one
         * would send that node's refusals silently back to ERROR, and this is what fails first.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "Database credential not found. Configure a Database credential and set it on this node before running.",
            "No IMAP credential configured. Configure an IMAP credential and set it on this node before running.",
            "No SMTP credential configured. Configure an SMTP credential and set it on this node before running.",
            "SFTP credential not found. Configure an SFTP credential and set it on this node before running.",
            "SSH credential not found. Configure an SSH credential and set it on this node before running.",
        })
        @DisplayName("a credential the caller has not configured")
        void missingCredential(String message) {
            assertThat(UserActionableFailure.isUserActionable(message)).isTrue();
        }

        @Test
        @DisplayName("the CHAT wording - the vocabulary no workflow node produces")
        void chatCreditRefusal() {
            // An agent schedule does not fail through a node: it calls the internal sync-chat
            // endpoint, which answers 402 with this sentence. The case lives HERE, in the
            // CI-armed test of the class that was actually widened, and not only in the
            // ScheduleExecutorService test that happens to exercise it - otherwise refactoring
            // that one caller would leave the widening untested with nothing failing.
            assertThat(UserActionableFailure.isUserActionable(ChatCreditRefusal.MESSAGE)).isTrue();
        }

        @Test
        @DisplayName("the CHAT wording wrapped in the transport sentence a caller relays")
        void chatCreditRefusalWrapped() {
            String relayed = "402  on POST request for \"http://livecontext-livecontext-conversation:8087"
                + "/api/internal/chat/sync\": \"{\"error\":\"Insufficient credits\",\"success\":false}\"";

            assertThat(UserActionableFailure.isUserActionable(relayed)).isTrue();
        }
    }

    @Nested
    @DisplayName("what only the platform can fix keeps ERROR")
    class NotRecognised {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "Connection refused: connect",
            "NullPointerException",
            "Read timed out",
            "CredentialClient is not available",
            "500 Internal Server Error from POST http://livecontext-livecontext-agent:8090/x",
        })
        @DisplayName("an infrastructure or code failure is not the customer's to act on")
        void platformFailures(String message) {
            assertThat(UserActionableFailure.isUserActionable(message)).isFalse();
        }

        @Test
        @DisplayName("an unknown shape defaults to ERROR, never to silence")
        void unknownDefaultsToError() {
            // The safe default in both directions is asserted, not assumed: hiding a platform
            // fault costs far more than leaving one customer condition at ERROR.
            assertThat(UserActionableFailure.isUserActionable("something nobody has seen before")).isFalse();
            assertThat(UserActionableFailure.isUserActionable(null)).isFalse();
            assertThat(UserActionableFailure.isUserActionable("")).isFalse();
            assertThat(UserActionableFailure.isUserActionable("   ")).isFalse();
        }
    }

    @Test
    @DisplayName("the five nodes still end their refusal with the sentence this classifier matches")
    void theFiveNodesStillCarryTheHint() throws IOException {
        // The literals above are a copy; this reads the NODES. Without it, re-wording a node and
        // its copy here together would keep the suite green while production went back to ERROR.
        //
        // It matches inside a STRING LITERAL that also names the credential, not anywhere in the
        // file: asserting "the file contains the sentence" passes when the sentence survives only
        // in a javadoc or a comment, which is exactly the re-word this is meant to catch.
        Path nodes = Path.of("src/main/java/com/apimarketplace/orchestrator/execution/v2/nodes");
        Pattern refusal = Pattern.compile(
            "\"[^\"]*credential[^\"]*" + Pattern.quote(UserActionableFailure.CREDENTIAL_NOT_CONFIGURED_HINT) + "\"",
            Pattern.CASE_INSENSITIVE);
        for (String node : List.of("DatabaseNode", "EmailInboxNode", "SendEmailNode", "SftpNode", "SshNode")) {
            String source = Files.readString(nodes.resolve(node + ".java"));
            assertThat(refusal.matcher(source).find())
                .as("%s must keep the shared closing sentence inside its refusal MESSAGE, or its "
                    + "refusals become ERROR again", node)
                .isTrue();
        }
    }
}
