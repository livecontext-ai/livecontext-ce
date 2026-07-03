package com.apimarketplace.orchestrator.tools.utility;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.generateInputSchema;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.intParam;

/**
 * General-purpose {@code wait} tool - lets an agent pause deliberately instead
 * of busy-polling or hallucinating that time has passed.
 *
 * <p>One execution action ({@code sleep}) plus {@code help}, following the
 * unified facade pattern ({@code image_generation} / {@code web_search}).
 * The sleep BLOCKS the tool-call thread server-side and returns when the
 * duration elapses, so the whole pause costs the agent a single tool call
 * (one LLM turn) instead of a poll loop.
 *
 * <p><b>Duration bound.</b> {@code wait.max-seconds} (default 240) caps a
 * single sleep. The cap must stay under every hop's tolerance for a
 * long-held tool call: the per-tool loop timeout (this tool declares its own
 * {@code timeoutMs} = cap + margin), the gateway response-timeout on the CLI
 * route, the 12-min inter-service read timeout on the direct-API path, and -
 * the tightest hop - the SILENCE watchdogs: the bridge kills a CLI session
 * after 5 min without stdout output (a CLI emits nothing while an MCP tool
 * call is in flight), and the agent loop's inactivity watchdog defaults to
 * the same 5 min. 240s keeps a full sleep + round trip under that window
 * with margin; longer waits re-call sleep (each tool result re-arms the
 * watchdogs). Raising the property past ~290s will get bridge sessions
 * killed with INACTIVITY_TIMEOUT mid-sleep.
 *
 * <p><b>Cancellation.</b> The agent loop only reads its stop signal BETWEEN
 * tool calls, so the sleep is sliced and each slice polls
 * {@link AgentCancellationProbe} - a user STOP releases the thread within
 * ~{@link #sliceMs} instead of the full duration (same design as the
 * workflow engine's inline WaitNode).
 *
 * <p><b>Capacity note.</b> A sleeping agent holds its execution thread (queue
 * workers are a fixed pool) - that is the accepted trade-off of the blocking
 * design; the cap keeps the hold bounded.
 */
@Slf4j
@Component
public class WaitToolsProvider implements ToolsProvider {

    private static final List<String> VALID_ACTIONS = List.of("sleep", "help");

    /** Cancellation poll granularity. Package-visible for fast unit tests. */
    long sliceMs = 250L;

    private final AgentCancellationProbe cancellationProbe;
    private final int maxSeconds;

    public WaitToolsProvider(AgentCancellationProbe cancellationProbe,
                             @Value("${wait.max-seconds:240}") int maxSeconds) {
        this.cancellationProbe = cancellationProbe;
        this.maxSeconds = maxSeconds;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.UTILITY;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters,
                                       ToolExecutionContext context) {
        if (!"wait".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }
        String action = parameters == null ? null : (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }
        return switch (action) {
            case "help" -> ToolExecutionResult.success(buildHelpPayload());
            case "sleep" -> executeSleep(parameters, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
        };
    }

    // ==================== sleep ====================

    private ToolExecutionResult executeSleep(Map<String, Object> parameters, ToolExecutionContext context) {
        Object rawSeconds = parameters.get("seconds");
        if (rawSeconds == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "seconds is required for action='sleep' (integer, 1-" + maxSeconds + ").");
        }
        Integer seconds = parseSeconds(rawSeconds);
        if (seconds == null) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "seconds must be a whole number between 1 and " + maxSeconds + " (got '" + rawSeconds + "').");
        }
        if (seconds < 1 || seconds > maxSeconds) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "seconds must be between 1 and " + maxSeconds + " (got " + rawSeconds + "). "
                    + "For a longer pause, call sleep again when this one returns. "
                    + "To wait for a workflow run to finish, use workflow(action='wait_run', run_id=...) instead.");
        }

        long startMs = System.currentTimeMillis();
        long deadlineMs = startMs + seconds * 1000L;
        try {
            while (true) {
                long remaining = deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) break;
                Thread.sleep(Math.min(sliceMs, remaining));
                if (cancellationProbe.isCallerCancelled(context)) {
                    long sleptSeconds = (System.currentTimeMillis() - startMs) / 1000L;
                    log.info("wait.sleep cancelled by caller stop signal after {}s (requested {}s)",
                        sleptSeconds, seconds);
                    return ToolExecutionResult.success(sleepResult(seconds, sleptSeconds, true));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Sleep interrupted before completion.");
        }
        return ToolExecutionResult.success(sleepResult(seconds, seconds, false));
    }

    private static Map<String, Object> sleepResult(int requestedSeconds, long sleptSeconds, boolean cancelled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", cancelled ? "cancelled" : "completed");
        out.put("requested_seconds", requestedSeconds);
        out.put("slept_seconds", sleptSeconds);
        if (cancelled) {
            out.put("note", "The user stopped this agent while it was sleeping. Wrap up now; do not start new work.");
        }
        return out;
    }

    /** Accepts Integer/Long/Double (whole, in int range) or a numeric string; null otherwise. */
    private static Integer parseSeconds(Object raw) {
        if (raw instanceof Integer i) return i;
        if (raw instanceof Long l) return l > Integer.MAX_VALUE || l < Integer.MIN_VALUE ? null : l.intValue();
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d) && !Double.isInfinite(d)
                && d <= Integer.MAX_VALUE && d >= Integer.MIN_VALUE ? (int) d : null;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ==================== Tool definition & help ====================

    private AgentToolDefinition buildUnifiedTool() {
        var params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("sleep | help")
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),
            intParam("seconds", "How long to pause, in seconds (sleep). Integer, 1-" + maxSeconds + ".",
                false, null)
        );

        String description = "Pause for a fixed duration.\n"
            + "- sleep: blocks for N seconds (max " + maxSeconds + "), then returns. The pause happens "
            + "server-side, so it costs a single tool call - use it between status checks on something "
            + "in progress (a delegated task, an external process) or to respect a rate limit.\n"
            + "- To wait for a workflow RUN, prefer workflow(action='wait_run', run_id=...) - it returns "
            + "as soon as the run finishes instead of a fixed delay.";

        return AgentToolDefinition.builder()
            .name("wait")
            .description(description)
            .category(ToolCategory.UTILITY)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("Call wait(action='help') for constraints and examples.")
            .requiresAuth(false)
            .tags(List.of("wait", "sleep", "pause", "delay", "poll"))
            // Must exceed the largest allowed sleep or the loop's per-tool
            // timeout kills the sleep mid-way with a spurious failure.
            .timeoutMs(maxSeconds * 1000L + 30_000L)
            .build();
    }

    private Map<String, Object> buildHelpPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
            "WAIT TOOL - pause deliberately instead of busy-polling. sleep blocks server-side for the "
                + "requested duration and returns; the whole pause costs ONE tool call.");

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("sleep", Map.of(
            "summary", "Block for N seconds, then return.",
            "params", Map.of("seconds", "required - integer, 1-" + maxSeconds),
            "returns", "{ status: 'completed'|'cancelled', requested_seconds, slept_seconds }. "
                + "status='cancelled' means the user stopped you mid-sleep - wrap up, do not start new work."));
        actions.put("help", Map.of("summary", "This payload. No params."));
        out.put("actions", actions);

        out.put("when_to_use", List.of(
            "Between status checks on something in progress: check, sleep, check again - instead of re-checking immediately.",
            "Rate limits / cooldowns: an external API asked you to retry later.",
            "NOT for workflow runs: workflow(action='wait_run', run_id=...) returns the moment the run finishes and gives you its full report - strictly better than sleep + get_run."));

        out.put("constraints", List.of(
            "Max " + maxSeconds + " seconds per call. Need longer? Call sleep again after it returns.",
            "Sleeping consumes your execution time budget like any other tool call."));

        out.put("examples", List.of(
            Map.of("action", "sleep", "seconds", 30,
                "comment", "Pause 30s before re-checking a delegated task's outbox."),
            Map.of("action", "sleep", "seconds", 5,
                "comment", "Back off after a rate-limit response, then retry.")));

        return out;
    }
}
