package com.apimarketplace.orchestrator.archunit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: every agent-facing list action MUST emit its envelope via
 * {@code AgentListEnvelope.paginate*}, not by hand-rolling
 * {@code result.put("count", …) + result.put("total", …) + result.put("hasMore", …)}.
 *
 * <p>Without this guard the 2026-05 envelope-drift bug class returns: every list
 * action invents its own shape, agents read inconsistent payloads, and the
 * FlyFinder hard-refuse path is bypassed.
 *
 * <p><b>Scope is orchestrator-only by design.</b> ArchUnit (and this source-scan
 * variant) only sees the classpath of the service it runs in. {@code agent.list},
 * {@code skill.list} (agent-service), {@code interface.list} (interface-service)
 * have their own list code paths but they live in <i>other</i> JVMs and will get
 * their own guard test in their own module - not here. Audit v0.3 round-3 (audit C)
 * explicitly flagged cross-service allowlist entries as "false reassurance".
 *
 * <p>Allowlist below carries modules waiting on PR2 migration. Entries shrink to
 * zero as PR2 lands.
 */
@DisplayName("AgentListEnvelope adoption - orchestrator list actions")
class AgentListEnvelopeAdoptionTest {

    /**
     * Files exempt from the guard. PR2 closed the orchestrator allowlist (workflow.list,
     * application.my, application.search all migrated). Empty by design - any new list
     * action under {@code tools/} MUST adopt {@code AgentListEnvelope.paginate*}.
     */
    private static final List<String> PR2_ALLOWLIST = List.of();

    /**
     * Patterns that flag a hand-rolled list envelope. Conservative - three
     * companion keys together are the signature of a list response.
     */
    private static final List<Pattern> HAND_ROLLED_ENVELOPE_PATTERNS = List.of(
        Pattern.compile("result\\.put\\s*\\(\\s*\"hasMore\""),
        Pattern.compile("data\\.put\\s*\\(\\s*\"hasMore\""),
        Pattern.compile("\\.put\\s*\\(\\s*\"totalPages\"")
    );

    private static final Pattern HELPER_IMPORT = Pattern.compile(
        "import\\s+com\\.apimarketplace\\.agent\\.tools\\.common\\.AgentListEnvelope\\s*;");

    /**
     * Packages under the orchestrator that own agent-facing list actions.
     * Restricted: anything under {@code tools/} that exposes a list-style action.
     */
    private static final List<Path> SCAN_ROOTS = List.of(
        Paths.get("src/main/java/com/apimarketplace/orchestrator/tools/workflow"),
        Paths.get("src/main/java/com/apimarketplace/orchestrator/tools/application"),
        Paths.get("src/main/java/com/apimarketplace/orchestrator/tools/files")
    );

    @Test
    @DisplayName("List actions in orchestrator.tools.* must use AgentListEnvelope (allowlist: PR2 in-flight)")
    void allListActionsAdoptHelper() throws IOException {
        var violations = new java.util.ArrayList<String>();

        for (Path root : SCAN_ROOTS) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .filter(p -> !PR2_ALLOWLIST.contains(p.getFileName().toString()))
                      .forEach(p -> checkFile(p, violations));
            }
        }

        assertThat(violations)
            .as("Files emit a list envelope by hand without going through AgentListEnvelope. "
                + "Either add the file to PR2_ALLOWLIST with a migration reason, or migrate it "
                + "to AgentListEnvelope.paginateInMemory/paginateProjection.")
            .isEmpty();
    }

    private static void checkFile(Path file, List<String> violations) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            violations.add(file + " unreadable: " + e.getMessage());
            return;
        }
        boolean handRolled = HAND_ROLLED_ENVELOPE_PATTERNS.stream()
            .anyMatch(p -> p.matcher(content).find());
        if (!handRolled) return;
        if (HELPER_IMPORT.matcher(content).find()) return;
        violations.add(file + " emits a list envelope key (hasMore/totalPages) but does not import AgentListEnvelope");
    }

    @Test
    @DisplayName("Allowlist is empty post-PR2 - re-introduction of any entry requires explicit justification")
    void allowlistMustStayEmpty() {
        // PR2 migration is complete. Any new entry here means a list action regressed
        // away from AgentListEnvelope adoption.
        assertThat(PR2_ALLOWLIST)
            .as("Allowlist must stay empty. Migrate the new list action to AgentListEnvelope "
                + "instead of re-introducing an exemption.")
            .isEmpty();
    }
}
