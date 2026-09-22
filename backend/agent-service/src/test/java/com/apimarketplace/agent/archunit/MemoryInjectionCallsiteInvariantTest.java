package com.apimarketplace.agent.archunit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the rule that makes long-term memory reach every agent: the block is
 * built in one place and appended at every point that assembles a system prompt
 * for execution.
 *
 * <p><b>Why a guard and not a comment.</b> The skills tree is assembled only in
 * conversation-service's {@code AgentContextBuilder}, so a sub-agent, a workflow
 * agent node and a CLI session never receive it. Nothing detects that: each path
 * builds a valid prompt, every run is green, and the gap only surfaces when
 * somebody asks why an agent knows its skills in chat and not in a workflow.
 * Memory has exactly the same shape and would drift exactly the same way, so the
 * invariant is enforced mechanically rather than remembered.
 *
 * <p>If a new execution entry point starts assembling its own system prompt, add
 * it to {@link #PROMPT_ASSEMBLY_SITES} and make it call
 * {@code MemoryPromptSection.appendTo(...)} - or, better, route it through
 * {@code AgentRemoteExecutionService} so there is one site fewer to keep in step.
 */
@DisplayName("Long-term memory injection call sites")
class MemoryInjectionCallsiteInvariantTest {

    /**
     * Every class in agent-service that composes a system prompt and hands it to
     * an execution. Both must append the memory block.
     */
    private static final List<Path> PROMPT_ASSEMBLY_SITES = List.of(
        Paths.get("src/main/java/com/apimarketplace/agent/service/execution/AgentRemoteExecutionService.java"),
        Paths.get("src/main/java/com/apimarketplace/agent/service/execution/SubAgentExecutionHandler.java")
    );

    private static final String RENDERER = "MemoryPromptSection";
    private static final String APPEND_CALL = "appendTo(";

    /**
     * The file with its comments and string literals blanked out.
     *
     * <p>Every check below matches against this rather than the raw text. Matching
     * the raw text made the whole invariant satisfiable by PROSE: a new execution
     * path that merely mentioned {@code MemoryPromptSection} in a javadoc line -
     * or, worse, in a comment explaining why it does not use it - passed the build
     * while assembling an amnesiac prompt. That is the precise failure this class
     * exists to catch, so the guard has to read code and only code.
     */
    private static String codeOnly(String source) {
        return scan(source, false);
    }

    /**
     * The file with its comments blanked out and its string literals KEPT.
     *
     * <p>For the checks whose evidence lives inside a literal: a table name in a
     * purge list, the marker a renderer writes into the block. {@link #codeOnly}
     * would blank exactly the thing being looked for. Dropping the comments is
     * still the point: without it, a line of prose that merely NAMES the table
     * satisfies the guard, so deleting the purge itself and leaving a comment
     * behind would pass - which is the failure mode these guards exist for.
     */
    private static String withoutComments(String source) {
        return scan(source, true);
    }

    private static String scan(String source, boolean keepLiterals) {
        // A single left-to-right scan rather than regexes. The regex version of this
        // blew the stack on the larger files in this module (catastrophic backtracking
        // in the string-literal alternation) and could not see Java text blocks at
        // all, which is where most of the prompt prose in this codebase lives.
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                code.append(' ');
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                code.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int close = end < 0 ? source.length() : end + 3;
                code.append(keepLiterals ? source.substring(i, close) : " \"\" ");
                i = close;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                int start = i;
                i++;
                while (i < source.length() && source.charAt(i) != quote) {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                int close = Math.min(i, source.length());
                code.append(keepLiterals ? source.substring(start, close) : "" + quote + quote);
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }

    @Test
    @DisplayName("every known system-prompt assembly site appends the memory block")
    void knownPromptAssemblySitesInjectMemory() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path site : PROMPT_ASSEMBLY_SITES) {
            assertThat(site)
                .as("known prompt-assembly site moved or was renamed; update PROMPT_ASSEMBLY_SITES")
                .exists();

            String source = codeOnly(Files.readString(site));
            if (!source.contains(RENDERER)) {
                violations.add(site + " assembles a system prompt but never references " + RENDERER + " in code");
            } else if (!source.matches("(?s).*\\b(?:MemoryPromptSection|memoryPromptSection)\\s*\\.\\s*appendTo\\s*\\(.*")) {
                // The call has to be ON the renderer. Accepting any '.appendTo(' in
                // the file let an unrelated builder call satisfy the check, which is
                // how a site could reference the renderer, never invoke it, and pass.
                violations.add(site + " references " + RENDERER + " but never calls " + APPEND_CALL + " on it");
            }
        }

        assertThat(violations)
            .as("An execution path that assembles a system prompt without appending memory silently "
                + "leaves its agents amnesiac - the exact drift the skills tree already has.")
            .isEmpty();
    }

    /**
     * The half that actually guards the invariant: an allow-list can only confirm
     * that the sites we already knew about still behave, which is the easy half. A
     * NEW class that starts assembling a prompt is the failure that matters, and it
     * is invisible to a fixed list, so this discovers candidates instead.
     *
     * <p>The signal is the shape every such site has: it builds the modular prompt
     * with {@code DefaultSystemPrompts.build(...)} or {@code buildAgentDefault(...)}
     * and then hands the result to an execution. Any file doing that must also
     * mention {@link #RENDERER}, or be listed here with a reason.
     */
    @Test
    @DisplayName("no NEW class assembles a system prompt without appending the memory block")
    void noUndiscoveredPromptAssemblySite() throws IOException {
        // Sites that legitimately build a prompt and must NOT carry memory, each
        // with the reason. Adding an entry here is a deliberate decision, which is
        // the point: silence is not.
        List<String> exempt = List.of(
            // Single-shot judges. Memory in a classifier is cost and noise: it never
            // changes a routing decision and is paid for on every call.
            "ClassifyService.java",
            "GuardrailService.java",
            // The bare completion behind COLD-summary compaction, served by a CLI in
            // restricted mode when a link sends it there. Its system prompt is the
            // SUMMARISER's (conversation-service builds it), not an agent's: memory
            // here would be summarised into the envelope and then recalled as if it
            // had been said in the chat. It also carries no agent id to load it for.
            "JsonCompletionService.java",
            // Builds a prompt for an EXTERNAL CLI session. The only consumer of that
            // response is the MCP stdio server (mcp/agent-cli-server.mjs), and it reads
            // exactly two fields from it: sessionId and availableTools. systemPrompt is
            // returned and never used, so appending a block there would render memory
            // into a string nobody reads. Those callers reach memory through
            // memory(action='list', as_index=true) instead.
            //
            // Worth stating precisely, because it is easy to confuse this with the
            // BRIDGE, which does feed systemPrompt to the CLI on stdin - that path is
            // covered, from conversation-service, and is asserted below.
            "CliAgentService.java"
        );

        List<String> candidates = new ArrayList<>();
        Path mainRoot = Paths.get("src/main/java");

        try (Stream<Path> stream = Files.walk(mainRoot)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String source = codeOnly(Files.readString(p));
                    // Building the modular result is not enough on its own: several
                    // classes call build(...) purely to derive the TOOL NAMES for a
                    // module set and never touch the prompt (CoreToolsCache,
                    // AgentRemoteExecutionService's auto-discover branch). The site
                    // that matters is the one that reads .systemPrompt() back out and
                    // hands it to an execution.
                    boolean buildsModularPrompt = source.contains("DefaultSystemPrompts.build(")
                        || source.contains("DefaultSystemPrompts.buildAgentDefault(");
                    boolean consumesThePrompt = source.contains(".systemPrompt()");
                    // Second signal, because the first one only sees a site that uses the
                    // MODULAR builder. A class that assembles a run from an agent's stored
                    // prompt string would slip past it entirely, and the guard's own
                    // javadoc promised to catch "any new class". Building an
                    // AgentLoopContext is the other shape a prompt-assembly site has:
                    // today exactly four classes do it, the two known sites and the two
                    // exempt single-shot judges, so this costs no false positives.
                    boolean buildsALoopContext = source.contains("AgentLoopContext.builder()");
                    if (!buildsALoopContext && (!buildsModularPrompt || !consumesThePrompt)) {
                        return;
                    }
                    String fileName = p.getFileName().toString();
                    if (exempt.contains(fileName) || source.contains(RENDERER)) {
                        return;
                    }
                    candidates.add(p.toString());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }

        assertThat(candidates)
            .as("These classes build a system prompt but never append long-term memory. Either call "
                + "MemoryPromptSection.appendTo(...) and add the file to PROMPT_ASSEMBLY_SITES, or add it "
                + "to the exempt list above with the reason it must not carry memory.")
            .isEmpty();
    }

    @Test
    @DisplayName("the memory block is rendered in exactly one place, so its caps and fence cannot fork")
    void onlyOneRendererExists() throws IOException {
        Path mainRoot = Paths.get("src/main/java");
        List<String> rendererDefinitions = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(mainRoot)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        // A class that both opens the fence and writes index lines is a
                        // second renderer, however it is named. Comments are dropped first:
                        // both markers read as ordinary prose, so a javadoc paragraph
                        // describing the block would otherwise be counted as a renderer and
                        // the guard would fail on documentation. The literals stay, because
                        // "## Index" IS a literal - blanking those would find nothing at all.
                        String source = withoutComments(Files.readString(p));
                        if (source.contains("FENCE_OPEN") && source.contains("## Index")) {
                            rendererDefinitions.add(p.toString());
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        }

        assertThat(rendererDefinitions)
            .as("A second renderer means two sets of char caps and two fences to keep in step; "
                + "one of them will drift and nothing will notice.")
            .hasSize(1);
        assertThat(rendererDefinitions.get(0)).endsWith("MemoryPromptSection.java");
    }

    @Test
    @DisplayName("the memory table is purged with the workspace, before the agents it points at")
    void memoryTableIsPurgedWithTheWorkspace() throws IOException {
        // Comments dropped, literals kept: the purge order lives in a literal list
        // of table names, while a comment that merely NAMES the table would satisfy
        // the check on its own - so the purge could be deleted, a note left in its
        // place, and this guard would still be green.
        String source = withoutComments(Files.readString(Paths.get(
            "src/main/java/com/apimarketplace/agent/service/purge/AgentPurgeFollower.java")));

        assertThat(source)
            .as("A workspace purge that leaves memories behind leaves the deleted workspace's private "
                + "facts on disk indefinitely.")
            .contains("agent.agent_memories");

        assertThat(source.indexOf("agent.agent_memories"))
            .as("agent_memories carries an FK to agent.agents and must be listed before it.")
            .isLessThan(source.indexOf("\"agent.agents\""));
    }
    /**
     * The dispatch that leaves this module entirely.
     *
     * <p>Everything above scans agent-service, and that scope is what let the
     * biggest hole in this feature go unnoticed: conversation-service posts to the
     * bridge itself whenever the chat model is a CLI provider, never calling
     * agent-service at all, so no amount of scanning THIS module could see that
     * those chats were running with no memory. A guard whose scope excludes the
     * failure it is meant to catch is worse than no guard, because it reads as
     * coverage.
     *
     * <p>So this reaches across to the sibling module and pins the shape that fixed
     * it: exactly one method posts to the bridge, and that method enriches the
     * prompt first. Two direct call sites is what made the omission possible.
     */
    @Test
    @DisplayName("the chat path that bypasses this service entirely still appends memory before dispatch")
    void conversationBridgeDispatchAppendsMemory() throws IOException {
        Path chatService = Paths.get("../conversation-service/src/main/java/com/apimarketplace/"
            + "conversation/service/ai/ConversationAgentService.java");
        assertThat(chatService)
            .as("the chat dispatcher moved or was renamed; this guard is now blind")
            .exists();

        String source = codeOnly(Files.readString(chatService));

        assertThat(source.split("bridgeClient\\s*\\.\\s*executeViaBridge\\s*\\(", -1).length - 1)
            .as("every bridge dispatch must go through the one method that appends memory; "
                + "a second direct call is how one branch gets the block and the other does not")
            .isEqualTo(1);

        int dispatcher = source.indexOf("private AgentExecutionResponseDto dispatchToBridge(");
        assertThat(dispatcher)
            .as("the single bridge dispatcher is gone; memory is no longer appended on this path")
            .isNotNegative();
        int endOfDispatcher = source.indexOf("bridgeClient", dispatcher);
        assertThat(source.substring(dispatcher, endOfDispatcher))
            .as("the bridge dispatcher must enrich the prompt BEFORE posting it")
            .contains("appendMemoryBlock(");
    }
}
