package com.apimarketplace.agent.archunit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run the user cancelled is never silently retried.
 *
 * <p>The execution-link fallback re-runs a failed bridge dispatch on the billed pair's
 * direct API when nothing reached the screen yet, which is invisible and therefore safe -
 * for a crash. It is not safe for a Stop: a user who cancels in the first seconds has
 * produced no content, no tool result and no thinking, so the response is byte-identical
 * to a crash, and the retry runs the WHOLE turn again on a paid API for a chat the user
 * just ended. Nothing in the UI could connect that charge to anything.
 *
 * <p>Output cannot distinguish the two cases; only the stop reason can. So the rule is
 * mechanical and checked here rather than remembered: any condition that reaches for
 * {@code hasNoVisibleOutput()} to decide on a retry must also consult
 * {@code wasCancelledByUser()}.
 *
 * <p><b>What this does and does not reach.</b> It scans agent-service's main sources for
 * the two calls inside ONE {@code if (...)} condition. That covers both fallback sites
 * that exist and the shape a third would plausibly take, and it is deliberately not a
 * proof: a call hoisted into a local ({@code boolean invisible = ...hasNoVisibleOutput();
 * if (invisible)}) or a fallback added in another module passes unseen. It buys the
 * common case cheaply, not completeness.
 *
 * <p>Read from the SOURCE, not the bytecode: the rule is about two calls appearing in one
 * boolean expression, which the compiler flattens into jumps that no bytecode rule can
 * reassemble into "the same condition".
 */
@DisplayName("A cancelled run is never silently retried")
class CancelledRunNeverRetriedInvariantTest {

    private static final String VISIBILITY_CHECK = "hasNoVisibleOutput()";
    private static final String CANCELLATION_CHECK = "wasCancelledByUser()";

    @Test
    @DisplayName("every retry decision that reads hasNoVisibleOutput() also reads wasCancelledByUser()")
    void everyVisibilityGateAlsoChecksCancellation() throws IOException {
        List<String> offenders = new ArrayList<>();
        int sitesChecked = 0;

        for (Path file : mainSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            int from = 0;
            while (true) {
                int at = source.indexOf(VISIBILITY_CHECK, from);
                if (at < 0) break;
                from = at + VISIBILITY_CHECK.length();

                String condition = enclosingCondition(source, at);
                if (condition == null) continue; // a declaration or a comment, not a decision
                sitesChecked++;
                if (!condition.contains(CANCELLATION_CHECK)) {
                    offenders.add(file.getFileName() + ": " + condition.replaceAll("\\s+", " ").trim());
                }
            }
        }

        assertThat(sitesChecked)
            .as("the scan must still find BOTH retry decisions. Fewer means one moved out of "
                + "reach of this scan - hoisted into a local, or rewritten - and its guard is "
                + "no longer checked by anything, whatever the rest of this test reports")
            .isGreaterThanOrEqualTo(2);
        assertThat(offenders)
            .as("a retry decision that ignores the stop reason will re-run, and bill, a turn "
                + "the user cancelled")
            .isEmpty();
    }

    /**
     * The text of the {@code if (...)} condition containing the given offset, or null when
     * the occurrence is not inside one (the method's own declaration, a javadoc mention).
     */
    private static String enclosingCondition(String source, int offset) {
        int open = source.lastIndexOf("if (", offset);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open + 3; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    // Only a condition that actually encloses the occurrence counts.
                    return i > offset ? source.substring(open, i + 1) : null;
                }
            }
        }
        return null;
    }

    private static List<Path> mainSources() throws IOException {
        Path root = Paths.get("src", "main", "java");
        if (!Files.isDirectory(root)) {
            // Surefire runs with the module as the working directory; fail loudly rather
            // than silently scanning nothing.
            throw new IOException("main sources not found at " + root.toAbsolutePath());
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
