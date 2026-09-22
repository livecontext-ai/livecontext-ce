package com.apimarketplace.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every builder that actually starts an agent loop must hand it the model's context window.
 *
 * <p>Without this, the context-monitor fix can stop reaching production with every behavioural
 * test still green: {@code AgentLoopExecutor} falls back to "window unknown", claims no severity,
 * and the monitor goes quiet - which reads exactly like "fixed". That is the failure mode the fix
 * was written against, so the wiring needs a guard and not only the behaviour.
 *
 * <p>The set of builders is DISCOVERED, never listed here. A hand-maintained list would need the
 * same human step that caused the original bug, and would silently shrink the moment a fifth
 * builder appeared.
 *
 * <p><b>What this cannot see:</b>
 * <ul>
 *   <li>Discovery needs both signals in the SAME file: the builder call and
 *       {@code agentLoopService.execute}. Split context-building into its own factory class and
 *       neither half matches.</li>
 *   <li>{@code RUNS_THE_LOOP} is a literal field spelling, so renaming that field defeats
 *       discovery without renaming anything this test mentions.</li>
 *   <li><b>Scope is agent-service only.</b> A loop-running builder added in shared-agent-lib,
 *       conversation-service or monolith-service is invisible here. All four of today's live
 *       in this module.</li>
 *   <li><b>The PAIR is not checked, only the source.</b> Resolving the window on the billed
 *       pair instead of the execution pair passes here, even though three of the four call
 *       sites carry a comment saying the execution pair is the one that can overflow. Checking
 *       that would mean parsing which identifiers the neighbouring {@code .provider(...)} uses,
 *       which is more parsing than this guard is worth.</li>
 * </ul>
 * {@code isNotEmpty()} below catches total discovery failure, not partial shrinkage, so widening
 * the discovery is the fix when one of these happens - never deleting the assertion.
 */
@DisplayName("Context-window wiring - discovered agent-loop builders resolve it")
class ContextWindowWiringTest {

    private static final Path MAIN_SOURCES =
        Path.of("src", "main", "java", "com", "apimarketplace", "agent");

    /** A builder only matters here if it goes on to RUN the loop. */
    private static final String RUNS_THE_LOOP = "agentLoopService.execute";

    @Test
    @DisplayName("every builder that runs the loop resolves a context window from the model catalog")
    void everyLoopRunningBuilderResolvesTheWindow() throws IOException {
        List<Path> sources = buildersThatRunTheLoop();

        assertThat(sources)
            .as("expected to discover the agent-loop builders under %s; finding none means the "
                + "discovery broke, not that the invariant holds", MAIN_SOURCES.toAbsolutePath())
            .isNotEmpty();

        for (Path source : sources) {
            String body = Files.readString(source);
            String name = source.getFileName().toString();

            // Counted, not merely present: a file with two builders and one wiring would pass
            // a contains() check while leaving one loop unwatched.
            int builderCount = occurrencesOf(body, "AgentLoopContext.builder()");
            List<String> wirings = contextWindowArgumentsOf(body);
            assertThat(wirings)
                .as("%s starts an agent loop %d time(s), so each must pass the model's context "
                    + "window - without it the loop reports 'window unknown' and the monitor "
                    + "silently stops judging anything", name, builderCount)
                .hasSize(builderCount);

            // The realistic wrong source is the pricing snapshot reached through the budget
            // guard's calculator: in production 744 of its 816 rows carry a null window,
            // deepseek-v4-pro among them, so a monitor fed from there reports "unknown" for the
            // very models it was built to watch - quiet, and indistinguishable from fixed.
            assertThat(wirings)
                .as("%s must resolve the window from the model catalog", name)
                .allSatisfy(argument -> assertThat(argument)
                    .as("in %s", name)
                    .contains("resolveContextWindow(")
                    .doesNotContain("guardChainFactory")
                    .doesNotContain("resolveCalculator"));
        }
    }

    /** Text passed to {@code .contextWindow(...)}, one entry per call site in the file. */
    private static List<String> contextWindowArgumentsOf(String body) {
        Matcher matcher = Pattern.compile("\\.contextWindow\\(([^;]*?)\\)\\s*$",
            Pattern.MULTILINE).matcher(body);
        List<String> arguments = new ArrayList<>();
        while (matcher.find()) {
            arguments.add(matcher.group(1));
        }
        return arguments;
    }

    /** Counts {@code needle} in code only - a builder named in a comment is not a builder. */
    private static int occurrencesOf(String rawBody, String needle) {
        String body = rawBody.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        int count = 0;
        for (int i = body.indexOf(needle); i >= 0; i = body.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static List<Path> buildersThatRunTheLoop() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    String body = readQuietly(path);
                    return body.contains("AgentLoopContext.builder()") && body.contains(RUNS_THE_LOOP);
                })
                .toList();
        }
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
