package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.tool.ToolExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The loop tells the tool when its own patience runs out. That one credential is what stops
 * an approval park from outliving the budget after which this loop discards the call as
 * timed out - a park that outlives it hands back a result nobody is left to collect, and
 * the user's approval appears to do nothing.
 */
@DisplayName("AgentLoopExecutor - the deadline handed to the tool")
class AgentLoopExecutorToolDeadlineTest {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final long GATE_BUDGET_MS = 300_000;

    private AtomicReference<Map<String, Object>> seenCredentials;
    private AgentLoopExecutor executor;

    @BeforeEach
    void setUp() {
        seenCredentials = new AtomicReference<>();
        ToolExecutionService toolExecutionService = new ToolExecutionService() {
            @Override
            public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                          String tenantId, Map<String, Object> credentials) {
                seenCredentials.set(new HashMap<>(credentials));
                return ToolResult.builder().toolCall(toolCall).success(true).content("{}").build();
            }

            @Override
            public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
                return true;
            }
        };
        executor = new AgentLoopExecutor(
                toolExecutionService, mock(AgentLogger.class),
                Executors.newCachedThreadPool(), DEFAULT_TIMEOUT_MS, false);
    }

    private static AgentLoopContext chatContext() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("conversationId", "conv-1");
        credentials.put("__streamId__", "stream-1");
        return AgentLoopContext.builder()
                .provider("openai").model("gpt-4").tenantId("tenant-1")
                .credentials(credentials)
                .build();
    }

    private static ToolCall call(String toolName, String action) {
        return new ToolCall("call-1", toolName, Map.of("action", action), null);
    }

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder().name(name).build();
    }

    private long deadlineHandedToTheTool() {
        Object deadline = seenCredentials.get().get("__toolDeadlineEpochMs__");
        assertThat(deadline).as("the tool must be told when the loop stops waiting").isInstanceOf(Long.class);
        return (Long) deadline;
    }

    @Test
    @DisplayName("A gateable call is told a deadline that covers its park budget")
    void gateableCallIsGivenTheParkBudget() {
        long before = System.currentTimeMillis();

        executor.executeSingleToolCall(call("workflow", "execute"), List.of(tool("workflow")), chatContext());

        long deadline = deadlineHandedToTheTool();
        // Far enough out that the park can run its course; without this the call would be
        // discarded after 30 s with its card still on screen.
        assertThat(deadline).isBetween(before + GATE_BUDGET_MS, before + GATE_BUDGET_MS + DEFAULT_TIMEOUT_MS + 5_000);
    }

    @Test
    @DisplayName("An ordinary call is told the ordinary deadline, not the park budget")
    void ordinaryCallKeepsTheOrdinaryDeadline() {
        long before = System.currentTimeMillis();

        executor.executeSingleToolCall(call("files", "list"), List.of(tool("files")), chatContext());

        long deadline = deadlineHandedToTheTool();
        // Well short of the park budget: a hung tool is still caught in seconds.
        assertThat(deadline).isLessThan(before + GATE_BUDGET_MS);
        assertThat(deadline).isBetween(before, before + DEFAULT_TIMEOUT_MS + 5_000);
    }

    @Test
    @DisplayName("The PARALLEL path gives a gated call the same budget, so a park is not cut short")
    void parallelPathHonoursTheParkBudget() throws Exception {
        // This path wraps each call in its own timeout, computed separately from the one the
        // call itself uses. Left on the plain default, a gated call running in parallel is
        // discarded seconds after its card appears, and the user's approval then releases a
        // call whose result nobody collects - green everywhere else, broken in production.
        AgentLoopExecutor slowExecutor = new AgentLoopExecutor(
                new ToolExecutionService() {
                    @Override
                    public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                                  String tenantId, Map<String, Object> credentials) {
                        try {
                            Thread.sleep(400);   // longer than the base timeout below
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return ToolResult.builder().toolCall(toolCall).success(true).content("{}").build();
                    }

                    @Override
                    public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
                        return true;
                    }
                },
                mock(AgentLogger.class), Executors.newCachedThreadPool(), 150, false);

        // TWO calls, because one falls back to the sequential path and would never reach the
        // branch under test. Both must be parallelisable tools (catalog is; workflow is not).
        List<ToolResult> results = slowExecutor.executeToolCallsParallel(
                List.of(call("catalog", "execute"), call("catalog", "execute")),
                List.of(tool("catalog")), chatContext(),
                new LoopExecutionState("run-1", 10, 60_000),
                mock(com.apimarketplace.agent.streaming.StreamingCallback.class));

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result -> assertThat(result.success())
                .as("a gated call must not be timed out at the base budget on the parallel path")
                .isTrue());
    }

    @Test
    @DisplayName("The tool is also told how long it needs to RUN, so a park cannot spend that too")
    void toolIsToldItsExecutionReserve() {
        executor.executeSingleToolCall(call("workflow", "execute"), List.of(tool("workflow")), chatContext());

        // Without this, the second park of a call falls back to a token 5 s margin: the
        // approved tool then starts with seconds left and its result is discarded as a
        // timeout, after the call was made and charged.
        assertThat(seenCredentials.get().get("__toolExecutionReserveMs__"))
                .as("the tool's own budget must be reserved from the park's deadline")
                .isEqualTo(DEFAULT_TIMEOUT_MS);
    }

    @Test
    @DisplayName("An already-authorized call gets no park budget - no card means no wait")
    void alreadyAuthorizedCallGetsNoParkBudget() {
        // After "always allow", every sensitive call in the conversation is in this state.
        // Leaving the budget on would turn a hung backend into a 5.5 min stall for all of them.
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("conversationId", "conv-1");
        credentials.put("__streamId__", "stream-1");
        credentials.put("__approvedToolActions__", List.of("*"));
        AgentLoopContext granted = AgentLoopContext.builder()
                .provider("openai").model("gpt-4").tenantId("tenant-1").credentials(credentials).build();

        long before = System.currentTimeMillis();
        executor.executeSingleToolCall(call("workflow", "execute"), List.of(tool("workflow")), granted);

        assertThat(deadlineHandedToTheTool()).isLessThan(before + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("...but a call that can ask to CONNECT A SERVICE keeps it, grant or no grant")
    void alreadyAuthorizedConnectCapableCallKeepsTheParkBudget() {
        // The grant answers "may I run this". It says nothing about a service the user never
        // connected, and that card comes from the tool result, so it can still be raised on
        // this very call. With no budget there is nothing holding the call while they connect,
        // and the connect card drops back to the two-turn flow - silently, and only for the
        // people who never see the prompt again, which is why it went unnoticed.
        //
        // BOTH ways a grant arrives, because they are different branches of the check: the
        // chat-wide toggle writes "*", while approving one card writes the rule itself - and
        // a rule lands there for anyone whose park merely expired, not only for people who
        // opted out. Pinning one spelling leaves the other free to regress.
        long before = System.currentTimeMillis();
        for (List<String> grant : List.of(List.of("*"), List.of("catalog:execute"))) {
            Map<String, Object> credentials = new HashMap<>();
            credentials.put("conversationId", "conv-1");
            credentials.put("__streamId__", "stream-1");
            credentials.put("__approvedToolActions__", grant);
            AgentLoopContext granted = AgentLoopContext.builder()
                    .provider("openai").model("gpt-4").tenantId("tenant-1").credentials(credentials).build();

            executor.executeSingleToolCall(call("catalog", "execute"), List.of(tool("catalog")), granted);

            assertThat(deadlineHandedToTheTool())
                    .as("the connect card still needs a budget to be held on, granted via " + grant)
                    .isBetween(before + GATE_BUDGET_MS,
                            before + GATE_BUDGET_MS + DEFAULT_TIMEOUT_MS + 5_000);
        }
    }

    @Test
    @DisplayName("A grant scoped to one ask is read here too, exactly as the remote gate reads it")
    void askScopedGrantIsRecognisedByTheLoopAsWell() {
        // This class and RemoteToolExecutionService are two readers of the same grant list,
        // for two execution paths: a direct-API agent never goes through the remote one. A
        // grant written by a chat button has to mean the same thing on both, or a permission
        // applies or does not depending on which provider the agent happens to run on.
        Map<String, Object> args = Map.of("action", "execute");
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("conversationId", "conv-1");
        credentials.put("__streamId__", "stream-1");
        credentials.put("__approvedToolActions__", List.of(
                com.apimarketplace.agent.tools.authz.AuthorizationAsk.scopedGrant("workflow:execute",
                        com.apimarketplace.agent.tools.authz.AuthorizationAsk.fingerprintOfCall(
                                "workflow:execute", "workflow", args))));
        AgentLoopContext granted = AgentLoopContext.builder()
                .provider("openai").model("gpt-4").tenantId("tenant-1").credentials(credentials).build();

        long before = System.currentTimeMillis();
        executor.executeSingleToolCall(call("workflow", "execute"), List.of(tool("workflow")), granted);

        // Authorized, so no card is coming and there is nothing to wait for: same outcome as
        // the bare rule and the wildcard above.
        assertThat(deadlineHandedToTheTool()).isLessThan(before + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("...and a scoped grant for a DIFFERENT ask still leaves the park budget on")
    void askScopedGrantForAnotherCallKeepsTheParkBudget() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("conversationId", "conv-1");
        credentials.put("__streamId__", "stream-1");
        credentials.put("__approvedToolActions__", List.of(
                com.apimarketplace.agent.tools.authz.AuthorizationAsk.scopedGrant("workflow:execute",
                        com.apimarketplace.agent.tools.authz.AuthorizationAsk.fingerprintOfCall(
                                "workflow:execute", "workflow", Map.of("action", "execute", "id", "wf-9")))));
        AgentLoopContext granted = AgentLoopContext.builder()
                .provider("openai").model("gpt-4").tenantId("tenant-1").credentials(credentials).build();

        long before = System.currentTimeMillis();
        executor.executeSingleToolCall(call("workflow", "execute"), List.of(tool("workflow")), granted);

        // Not covered, so a card IS coming and the call must be held long enough for somebody
        // to answer it. Reading the grant too loosely here would not authorize anything by
        // itself, but it would withdraw the budget and drop the card back to a two-turn flow.
        assertThat(deadlineHandedToTheTool()).isGreaterThanOrEqualTo(before + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("application:acquire gets no park budget - it raises a card but never waits on one")
    void userPerformedRuleGetsNoParkBudget() {
        long before = System.currentTimeMillis();

        executor.executeSingleToolCall(call("application", "acquire"), List.of(tool("application")), chatContext());

        // Approving it means "the USER installs it", so the call is never held. Granting the
        // budget anyway would turn a hung install backend into a 5.5-minute stall in chat.
        assertThat(deadlineHandedToTheTool()).isLessThan(before + GATE_BUDGET_MS);
    }

    @Test
    @DisplayName("The deadline follows the same rule as the timeout, so the two can never disagree")
    void deadlineMatchesTheEffectiveTimeout() {
        long before = System.currentTimeMillis();
        ToolCall toolCall = call("catalog", "execute");
        AgentLoopContext context = chatContext();

        executor.executeSingleToolCall(toolCall, List.of(tool("catalog")), context);

        long expected = executor.effectiveTimeoutMs(toolCall, tool("catalog"), context.credentials());
        // A deadline computed from a different rule than the timeout would either cut the
        // park short or let it outlive the call that is waiting on it.
        assertThat(deadlineHandedToTheTool()).isBetween(before + expected, before + expected + 5_000);
    }
}
