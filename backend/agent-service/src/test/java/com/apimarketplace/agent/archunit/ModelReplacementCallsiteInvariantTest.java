package com.apimarketplace.agent.archunit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the rule that keeps a DISABLED model from failing runs (V515): every execution entry
 * point that resolves a model execution link swaps a disabled billed pair for its replacement
 * FIRST ({@code ModelReplacementResolver.substituteIfDisabled}).
 *
 * <p>Why a guard: an entry point that forgets the swap still compiles and still runs every
 * enabled model fine. It only breaks on the day an admin turns a model off, which is exactly
 * when nobody is looking at that code. Asking for a link is the reliable sign that a class
 * decides what a run executes on, so that is what the guard keys on.
 */
@DisplayName("Model replacement call sites")
class ModelReplacementCallsiteInvariantTest {

    private static final Path MAIN = Paths.get("src/main/java");
    /** The two ways a caller asks the link store where a billed pair executes. */
    private static final List<String> LINK_CALLS = List.of("runnableRoute(", "resolveSingleCompletionTarget(");
    private static final String SWAP_CALL = "substituteIfDisabled(";
    /**
     * Not execution entry points: the router and the store DEFINE the link calls (the router
     * follows link TARGETS, not billed pairs). CloudLlmRelayController reaches neither call and
     * is left out on purpose: it relays a pair a linked CE install already resolved against its
     * own catalog, where the same resolver runs, so a cloud-side swap would second-guess it.
     */
    private static final Set<String> DEFINERS = Set.of("ExecutionLinkRouter.java", "ModelExecutionLinkService.java");

    @Test
    @DisplayName("every class that resolves an execution link swaps a disabled model first")
    void everyLinkCallerSwapsDisabledModels() throws IOException {
        List<String> linkCallers = new ArrayList<>();
        List<String> missingSwap = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (DEFINERS.contains(file.getFileName().toString())) {
                    continue;
                }
                String code = withoutComments(Files.readString(file));
                if (LINK_CALLS.stream().noneMatch(code::contains)) {
                    continue;
                }
                linkCallers.add(file.getFileName().toString());
                if (!code.contains(SWAP_CALL)) {
                    missingSwap.add(file.toString());
                }
            }
        }

        // Guards the guard: if the scan found nothing, the path or the method name moved and
        // this test would pass vacuously.
        assertThat(linkCallers).contains(
            "AgentRemoteExecutionService.java", "ClassifyService.java", "GuardrailService.java",
            "SubAgentExecutionHandler.java", "JsonCompletionService.java", "AvatarGenerationService.java");
        assertThat(missingSwap)
            .as("These classes resolve an execution link but never swap a disabled model; a model an admin"
                + " disables would keep being sent to the provider. Call"
                + " ModelReplacementResolver.substituteIfDisabled(provider, model) before resolving the link.")
            .isEmpty();
    }

    /** The source with comments blanked, so a mention in prose never satisfies the guard. */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i += 2;
            } else if (c == '"') {
                out.append(c);
                i++;
                while (i < n && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\') {
                        out.append(source.charAt(i++));
                    }
                    if (i < n) out.append(source.charAt(i++));
                }
                if (i < n) out.append(source.charAt(i++));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
