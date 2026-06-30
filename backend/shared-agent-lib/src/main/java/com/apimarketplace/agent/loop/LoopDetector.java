package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.ToolCall;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects repetitive tool calls to prevent infinite loops and suggest workflow automation.
 *
 * TWO detection mechanisms:
 *
 * 1. IDENTICAL CALLS (same tool + same args):
 *    - After 5 calls: warning (use result you have)
 *    - After 15 calls: hard stop
 *
 * 2. CONSECUTIVE CALLS (total tool calls regardless of signature):
 *    - After 15 calls: reminder (suggest workflow)
 *    - After 25 calls: strong recommendation
 *    - After 35 calls: final warning (1 iteration left)
 *    - After 40 calls: hard stop
 *
 * Key insight: Repetitive patterns should be automated via workflows.
 * Note: Limits are generous to allow batch operations (e.g., fetching 20+ emails).
 */
@Slf4j
public class LoopDetector {

    // Default thresholds for IDENTICAL calls (same tool + same args).
    // Kept public-package so AgentDefaultsConfig/tests can reference them
    // without diverging from the hard-coded fallback path.
    public static final int DEFAULT_WARN_THRESHOLD = 5;
    public static final int DEFAULT_STOP_THRESHOLD = 15;

    // Default thresholds for CONSECUTIVE calls (total tool calls).
    // Allows batch operations (e.g., 21 catalog_execute calls).
    public static final int DEFAULT_CONSECUTIVE_REMINDER = 15;
    public static final int DEFAULT_CONSECUTIVE_STRONG = 25;
    public static final int DEFAULT_CONSECUTIVE_FINAL = 35;
    public static final int DEFAULT_CONSECUTIVE_STOP = 40;

    // Instance-level thresholds - defaults above, overridable via constructor.
    // Intermediate thresholds (WARN/REMINDER/STRONG/FINAL) scale proportionally
    // when the caller passes custom stop thresholds, so the graduated severity
    // ladder stays monotonic:
    //   identical:   warn at 1/3 of stop (min 2)
    //   consecutive: reminder / strong / final at ~3/8, 5/8, 7/8 of stop (min 4)
    private final int warnThreshold;
    private final int stopThreshold;
    private final int consecutiveReminder;
    private final int consecutiveStrong;
    private final int consecutiveFinal;
    private final int consecutiveStop;

    // Map of tool call signature -> count (for identical detection)
    private final Map<String, Integer> callCounts = new HashMap<>();

    // Map of tool call signature -> last result (to detect same result)
    private final Map<String, String> lastResults = new HashMap<>();

    // Counter for total consecutive tool calls (for sequence detection)
    private int totalConsecutiveCalls = 0;

    /**
     * Default constructor - uses the hard-coded thresholds that match the historical
     * behavior (stop at 15 identical, stop at 40 consecutive).
     */
    public LoopDetector() {
        this(DEFAULT_STOP_THRESHOLD, DEFAULT_CONSECUTIVE_STOP);
    }

    /**
     * Configurable constructor. Intermediate warning thresholds are derived from the
     * two stop thresholds so the detector stays monotonic no matter what the caller
     * supplies. Use this when per-agent overrides (see {@code AgentEntity.loopIdenticalStop}
     * and {@code loopConsecutiveStop}) are being applied.
     *
     * @param identicalStop   hard-stop threshold for identical tool calls (min 2)
     * @param consecutiveStop hard-stop threshold for consecutive tool calls (min 4)
     * @throws IllegalArgumentException if either threshold is below its minimum
     */
    public LoopDetector(int identicalStop, int consecutiveStop) {
        if (identicalStop < 2) {
            throw new IllegalArgumentException("identicalStop must be >= 2, got " + identicalStop);
        }
        if (consecutiveStop < 4) {
            throw new IllegalArgumentException("consecutiveStop must be >= 4, got " + consecutiveStop);
        }
        this.stopThreshold = identicalStop;
        // WARN sits at one third of stop (rounded up, min 2). For the default 15 this
        // gives WARN=5, matching the historical constant.
        this.warnThreshold = Math.max(2, (int) Math.ceil(identicalStop / 3.0));

        this.consecutiveStop = consecutiveStop;
        this.consecutiveFinal = Math.max(3, (int) Math.ceil(consecutiveStop * 7.0 / 8.0));
        this.consecutiveStrong = Math.max(2, (int) Math.ceil(consecutiveStop * 5.0 / 8.0));
        this.consecutiveReminder = Math.max(1, (int) Math.ceil(consecutiveStop * 3.0 / 8.0));
    }

    /**
     * Records a tool call and returns the detection result.
     *
     * @param toolCall The tool call to record
     * @return Detection result indicating if warning/stop is needed
     */
    public DetectionResult recordToolCall(ToolCall toolCall) {
        String signature = computeSignature(toolCall);
        int count = callCounts.merge(signature, 1, Integer::sum);

        log.debug("Tool call signature: {} (count: {})", signature, count);

        if (count >= stopThreshold) {
            log.warn("🛑 [LOOP DETECTED] Tool '{}' called {} times with same args - STOPPING",
                toolCall.toolName(), count);
            return DetectionResult.STOP;
        } else if (count >= warnThreshold) {
            log.warn("⚠️ [LOOP WARNING] Tool '{}' called {} times with same args",
                toolCall.toolName(), count);
            return DetectionResult.WARN;
        }

        return DetectionResult.OK;
    }

    /**
     * Records a tool result to track if the same result keeps coming back.
     * Also checks if the result should be truncated for context management.
     *
     * @param toolCall The tool call
     * @param result The result content
     * @return True if this is a duplicate result
     */
    public boolean recordResult(ToolCall toolCall, String result) {
        String signature = computeSignature(toolCall);
        String previousResult = lastResults.put(signature, result);

        if (previousResult != null && previousResult.equals(result)) {
            log.debug("Same result returned for tool '{}' - duplicate detected", toolCall.toolName());
            return true;
        }
        return false;
    }

    /**
     * Generates a warning message for identical calls.
     */
    public String generateWarningMessage(ToolCall toolCall, int count) {
        int remaining = stopThreshold - count;
        return String.format(
            "[WARNING] '%s' called %dx with same args. Use the result you have. " +
            "If repetitive → suggest workflow. Remaining: %d calls.",
            toolCall.toolName(), count, remaining
        );
    }

    /**
     * Generates the stop message for identical calls.
     */
    public String generateStopMessage(ToolCall toolCall, int count) {
        return String.format(
            "[STOP] '%s' called %dx identically. Respond NOW. " +
            "Tip: Repetitive API calls should be a workflow with loop.",
            toolCall.toolName(), count
        );
    }

    /**
     * Gets the current call count for a tool signature.
     */
    public int getCallCount(ToolCall toolCall) {
        String signature = computeSignature(toolCall);
        return callCounts.getOrDefault(signature, 0);
    }

    /**
     * Resets the detector for a new conversation.
     */
    public void reset() {
        callCounts.clear();
        lastResults.clear();
        totalConsecutiveCalls = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONSECUTIVE CALL DETECTION (sequence patterns like A→B→C→A→B→C)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Records a consecutive tool call and returns the detection result.
     * This tracks the TOTAL number of tool calls regardless of their signature.
     *
     * @return Detection result indicating the severity level
     */
    public ConsecutiveResult recordConsecutiveCall() {
        totalConsecutiveCalls++;

        if (totalConsecutiveCalls >= consecutiveStop) {
            log.error("🛑 [CONSECUTIVE STOP] {} tool calls executed - HARD STOP",
                totalConsecutiveCalls);
            return ConsecutiveResult.STOP;
        } else if (totalConsecutiveCalls >= consecutiveFinal) {
            log.warn("🚨 [CONSECUTIVE FINAL] {} tool calls - 1 iteration left before stop",
                totalConsecutiveCalls);
            return ConsecutiveResult.FINAL_WARNING;
        } else if (totalConsecutiveCalls >= consecutiveStrong) {
            log.warn("⚠️ [CONSECUTIVE STRONG] {} tool calls - consider different approach",
                totalConsecutiveCalls);
            return ConsecutiveResult.STRONG_RECOMMENDATION;
        } else if (totalConsecutiveCalls >= consecutiveReminder) {
            log.info("💡 [CONSECUTIVE REMINDER] {} tool calls executed",
                totalConsecutiveCalls);
            return ConsecutiveResult.REMINDER;
        }

        return ConsecutiveResult.OK;
    }

    /**
     * Gets the current total consecutive call count.
     */
    public int getTotalConsecutiveCalls() {
        return totalConsecutiveCalls;
    }

    /**
     * Generates a message based on the consecutive detection result.
     * Messages are JIT, concise, and workflow-oriented.
     */
    public String generateConsecutiveMessage(ConsecutiveResult result) {
        int remaining = consecutiveStop - totalConsecutiveCalls;

        return switch (result) {
            case REMINDER -> String.format(
                "[INFO] %d tool calls. Repetitive task? → Suggest workflow automation.",
                totalConsecutiveCalls
            );
            case STRONG_RECOMMENDATION -> String.format(
                "[RECOMMEND] %d calls. SHOULD: respond with results OR suggest workflow. " +
                "Remaining: %d.",
                totalConsecutiveCalls, remaining
            );
            case FINAL_WARNING -> String.format(
                "[LAST CHANCE] %d calls. 1 iteration left. STOP tools, RESPOND NOW.",
                totalConsecutiveCalls
            );
            case STOP -> String.format(
                "[TERMINATED] %d calls limit. Respond NOW with results.",
                totalConsecutiveCalls
            );
            default -> null;
        };
    }

    /**
     * Consecutive detection result enum with graduated severity levels.
     */
    public enum ConsecutiveResult {
        OK,                    // No issue
        REMINDER,              // 15 calls - light reminder
        STRONG_RECOMMENDATION, // 25 calls - aggressive recommendation
        FINAL_WARNING,         // 35 calls - 1 iteration left
        STOP                   // 40 calls - hard stop
    }

    /**
     * Computes a unique signature for a tool call based on name and arguments.
     */
    private String computeSignature(ToolCall toolCall) {
        StringBuilder sb = new StringBuilder();
        sb.append(toolCall.toolName());

        if (toolCall.arguments() != null && !toolCall.arguments().isEmpty()) {
            // Sort arguments for consistent hashing
            toolCall.arguments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("|").append(e.getKey()).append("=").append(e.getValue()));
        }

        // Create a short hash for logging
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 4; i++) { // First 4 bytes = 8 hex chars
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return toolCall.toolName() + ":" + hexString;
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple signature
            return sb.toString().hashCode() + "";
        }
    }

    /**
     * Detection result enum.
     */
    public enum DetectionResult {
        OK,      // No issue
        WARN,    // Warning threshold reached
        STOP     // Stop threshold reached
    }
}
