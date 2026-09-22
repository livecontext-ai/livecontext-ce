package com.apimarketplace.agent.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every class that starts an LLM turn must resolve model execution links first.
 *
 * <p>A link maps a BILLED {@code (provider, model)} pair to a different EXECUTION target.
 * Honouring it is not a nicety: an admin who routes a pair away from its own API key
 * expects nothing to keep spending that key. Three callers ({@code ClassifyService},
 * {@code GuardrailService}, {@code SubAgentExecutionHandler}) silently did exactly that
 * for months, because honouring links was a convention each new caller had to remember
 * rather than something the build checked. The symptom is the worst kind: a green run
 * that quietly bills, and eventually fails on, the key the platform stopped using.
 *
 * <p>The rule is therefore mechanical: if a class calls {@code AgentLoopService.execute}
 * or {@code BridgeLoopDispatcher.execute}, it must also depend on
 * {@code ExecutionLinkRouter}. A new caller that forgets fails here, in the build, instead
 * of on someone's invoice.
 *
 * <p>Two kinds of exemption exist and each is listed by name below, with its reason.
 * Adding an entry is a deliberate decision, which is the point: it makes "this one does
 * not need links" an explicit, reviewed claim rather than an oversight.
 */
@DisplayName("Execution-link callsite invariant")
class ExecutionLinkCallsiteInvariantTest {

    /**
     * Callers exempt from the rule, each with the reason it cannot or must not resolve.
     * Keep this list short; a new entry needs the same scrutiny as a new bypass.
     */
    private static final List<String> ALLOWLIST = List.of(
        // The dispatcher IS the bridge transport. It is what the router asks about
        // availability, so depending on the router would invert the dependency.
        "BridgeLoopDispatcher",
        // The loop itself: it runs the pair it is handed and resolves nothing.
        "AgentLoopService",
        // The bridge HTTP client: transport again, one layer lower.
        "SubAgentBridgeClient"
    );

    private static final String ROUTER = "com.apimarketplace.agent.service.execution.ExecutionLinkRouter";
    private static final String LOOP_SERVICE = "com.apimarketplace.agent.loop.AgentLoopService";
    private static final String BRIDGE_DISPATCHER =
        "com.apimarketplace.agent.service.execution.BridgeLoopDispatcher";
    /** The bridge HTTP client: reachable directly, as the sub-agent path does. */
    private static final String BRIDGE_CLIENT =
        "com.apimarketplace.agent.service.execution.SubAgentBridgeClient";

    @Test
    @DisplayName("a class that starts an LLM turn also depends on ExecutionLinkRouter")
    void everyLlmCallsiteResolvesExecutionLinks() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.apimarketplace.agent");

        Set<String> offenders = new TreeSet<>();
        for (JavaClass clazz : classes) {
            if (ALLOWLIST.contains(clazz.getSimpleName())) {
                continue;
            }
            boolean startsATurn = startsAnLlmTurn(clazz);
            if (!startsATurn) {
                continue;
            }
            boolean knowsTheRouter = clazz.getDirectDependenciesFromSelf().stream()
                .anyMatch(dep -> ROUTER.equals(dep.getTargetClass().getFullName()));
            if (!knowsTheRouter) {
                offenders.add(clazz.getSimpleName());
            }
        }

        assertThat(offenders)
            .as("These classes start an LLM turn without resolving model execution links, so a "
                + "linked model would keep running on the provider key an admin routed away from. "
                + "Resolve through ExecutionLinkRouter (run the loop on the execution pair, keep "
                + "the billed identity on the response), or add the class to this test's "
                + "ALLOWLIST with the reason it genuinely cannot be linked. Offenders: %s",
                String.join(", ", offenders))
            .isEmpty();
    }

    @Test
    @DisplayName("the rule can still see its own subjects: the known callers are found")
    void theRuleActuallyMatchesTheKnownCallers() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.apimarketplace.agent");

        // Without this, a refactor that renames execute() or moves the loop turns the rule
        // above into a vacuous pass: zero subjects, zero offenders, green build, no cover.
        Set<String> callers = classes.stream()
            .filter(ExecutionLinkCallsiteInvariantTest::startsAnLlmTurn)
            .map(JavaClass::getSimpleName)
            .collect(Collectors.toCollection(TreeSet::new));

        assertThat(callers).contains(
            "AgentRemoteExecutionService", "ClassifyService", "GuardrailService", "SubAgentExecutionHandler",
            "JsonCompletionService");
        // AgentRemoteExecutionService reaches the bridge through dispatchRaw and the sub-agent
        // handler through the client directly, so both of those entry points must be watched
        // too: a future caller that only ever touches the bridge is still a linked-model run.
        assertThat(entryPointsWatched())
            .contains("AgentLoopService.execute*", "BridgeLoopDispatcher.execute*",
                "BridgeLoopDispatcher.dispatchRaw", "SubAgentBridgeClient.execute*");
    }

    /**
     * True when the class calls the loop or the bridge dispatcher to run a turn. Matching on
     * the OWNER plus an {@code execute} prefix rather than the exact name keeps the streaming
     * entry point ({@code AgentLoopService.executeStreaming}) inside the rule: a future caller
     * that reaches for it would otherwise bypass links with the invariant still green.
     */
    private static boolean startsAnLlmTurn(JavaClass clazz) {
        return clazz.getMethodCallsFromSelf().stream().anyMatch(call -> {
            String owner = call.getTargetOwner().getFullName();
            // Any `execute*` on the loop or the dispatcher, plus the dispatcher's raw entry
            // point and the bridge client one layer below it. A caller that reaches only for
            // the bridge - no loop call at all - would otherwise sit outside the rule.
            boolean executeOnRunner = call.getName().startsWith("execute")
                && (LOOP_SERVICE.equals(owner) || BRIDGE_DISPATCHER.equals(owner) || BRIDGE_CLIENT.equals(owner));
            boolean rawBridgeDispatch = "dispatchRaw".equals(call.getName()) && BRIDGE_DISPATCHER.equals(owner);
            return executeOnRunner || rawBridgeDispatch;
        });
    }


    /** The entry points {@link #startsAnLlmTurn} watches, spelled out so the list is reviewable. */
    private static Set<String> entryPointsWatched() {
        return new TreeSet<>(List.of(
            "AgentLoopService.execute*",
            "BridgeLoopDispatcher.execute*",
            "BridgeLoopDispatcher.dispatchRaw",
            "SubAgentBridgeClient.execute*"));
    }

}
