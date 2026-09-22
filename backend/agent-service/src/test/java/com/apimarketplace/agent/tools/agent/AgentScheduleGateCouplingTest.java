package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.tools.authz.ToolAuthorizationGuard;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate and the module must keep reading the SAME argument, and this is the invariant
 * that says so.
 *
 * <p><b>Why a source scan rather than a behaviour test.</b> The coupling is between two
 * places that never call each other: {@code ToolAuthorizationPolicy} decides whether a card
 * is raised by looking for a cron, and {@code AgentCrudModule} arms the schedule by reading
 * one. When they disagree the failure is the expensive kind: the gate finds no cron, raises
 * nothing, and the module arms the schedule anyway. Nothing fails, nothing is logged, and the
 * user is never asked. That already happened once on this very rule: the gate read the top
 * level while the module read the merged view, so the call shape the tool's own help
 * recommends walked straight through.
 *
 * <p>A behaviour test cannot see a SECOND reader being added (a new alias, a new arming
 * path), because it can only assert the shapes it was written for. A scan can: it fails the
 * moment this module grows a cron read the gate does not know about.
 *
 * <p>Same shape as {@code ParamAliasCreatorParityTest} and
 * {@code JsonbWritesCallsiteInvariantTest}, which guard two other couplings the compiler
 * cannot see. Do not "fix" a failure here by relaxing the expectation: teach the gate the new
 * reader instead.
 */
@DisplayName("AgentCrudModule and the authorization gate read the same cron")
class AgentScheduleGateCouplingTest {

    /**
     * Resolved against the module directory, then against the reactor root, because surefire's
     * working directory is the module but a hand-run from {@code backend/} is not. A scan test
     * that cannot find its file would otherwise throw where it should assert.
     */
    private static Path moduleSource() {
        Path relative = Path.of("src", "main", "java", "com", "apimarketplace",
                "agent", "tools", "agent", "AgentCrudModule.java");
        return Files.exists(relative) ? relative : Path.of("agent-service").resolve(relative);
    }

    /**
     * A cron read written the right way: through the constant the gate also reads.
     * Character classes rather than shorthand so the pattern carries no fragile escapes.
     */
    private static final Pattern CONSTANT_CRON_READ = Pattern.compile(
            "[A-Za-z]*Param[(][ ]*[A-Za-z_]+[ ]*,[ ]*ToolAuthorizationPolicy[.]PARAM_SCHEDULE_CRON[ ]*[)]");

    /**
     * The one place the module is allowed to spell the cron: WRITING it as a response key,
     * which is the tool's OUTPUT contract and a different thing from reading a parameter.
     *
     * <p>Everything else that mentions the string is a read of some shape, and every read has
     * to go through the constant the gate also reads. Matching on "not a write" rather than on
     * a list of known read spellings is what makes this catch a read nobody anticipated:
     * {@code p.get("schedule_cron")} and {@code getStringParam(mergeParams(x), "schedule_cron")}
     * are both invisible to a pattern built from the two spellings that exist today.
     */
    private static final Pattern RESPONSE_KEY_WRITE = Pattern.compile(
            "put[(][ ]*\"schedule_cron\"");

    /** Every mention of the literal, wherever it is. */
    private static final Pattern ANY_LITERAL = Pattern.compile("\"schedule_cron\"");

    @Test
    @DisplayName("the module never spells the cron key in a READ - it uses the gate's constant")
    void moduleReadsTheCronThroughTheSharedConstant() throws IOException {
        String source = Files.readString(moduleSource(), StandardCharsets.UTF_8);

        // A literal in a read would compile, work, and let a later rename drift the two
        // apart. The constant makes that impossible; this asserts nobody reintroduces one -
        // in ANY spelling, which is why it subtracts the writes instead of listing the reads.
        long mentions = ANY_LITERAL.matcher(source).results().count();
        long writes = RESPONSE_KEY_WRITE.matcher(source).results().count();

        assertThat(mentions - writes)
                .as("AgentCrudModule mentions \"schedule_cron\" %d time(s) and writes it as a "
                        + "response key %d time(s). Every remaining mention is a READ, and a read "
                        + "must go through ToolAuthorizationPolicy.PARAM_SCHEDULE_CRON - a literal "
                        + "can be renamed on one side only, which silently disarms the card.",
                        mentions, writes)
                .isZero();
    }

    @Test
    @DisplayName("every cron read in the module is a shape the gate also matches")
    void everyCronReadIsGated() throws IOException {
        String source = Files.readString(moduleSource(), StandardCharsets.UTF_8);

        Matcher matcher = CONSTANT_CRON_READ.matcher(source);
        int reads = 0;
        while (matcher.find()) {
            reads++;
        }
        // create + update. A THIRD read means a new arming path: check it sits on an action
        // the gate conditions on (see conditionalRuleKey) before raising this number.
        assertThat(reads)
                .as("AgentCrudModule reads the cron in %d place(s). Each one arms a schedule, so "
                        + "each one must sit on an action ToolAuthorizationPolicy gates - otherwise "
                        + "that path arms a recurring agent with no card.", reads)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the two actions that read it are exactly the two the gate conditions on")
    void theGatedActionsAreTheArmingOnes() {
        // The other half of the coupling, asserted behaviourally: whatever the source says,
        // these are the calls that must raise a card.
        for (String action : List.of("create", "update")) {
            assertThat(ToolAuthorizationGuard.matchedRule("agent",
                    Map.of("action", action, "schedule_cron", "0 9 * * *")))
                    .as("agent:%s carrying a cron must raise the schedule card", action)
                    .isEqualTo(ToolAuthorizationPolicy.RULE_AGENT_SCHEDULE);
        }
    }
}
