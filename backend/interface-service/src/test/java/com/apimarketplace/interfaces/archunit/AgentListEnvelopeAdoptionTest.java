package com.apimarketplace.interfaces.archunit;

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
 * Structural guard for interface-service list actions - sister to the orchestrator + agent-service
 * twins. Scope is interface-service-only by design.
 */
@DisplayName("AgentListEnvelope adoption - interface-service list actions")
class AgentListEnvelopeAdoptionTest {

    /** PR3 closed the interface-service allowlist (InterfaceCrudModule migrated). */
    private static final List<String> ALLOWLIST = List.of();

    private static final List<Pattern> HAND_ROLLED_ENVELOPE_PATTERNS = List.of(
        Pattern.compile("result\\.put\\s*\\(\\s*\"hasMore\""),
        Pattern.compile("data\\.put\\s*\\(\\s*\"hasMore\""),
        Pattern.compile("\\.put\\s*\\(\\s*\"totalPages\"")
    );

    private static final Pattern HELPER_IMPORT = Pattern.compile(
        "import\\s+com\\.apimarketplace\\.agent\\.tools\\.common\\.AgentListEnvelope\\s*;");

    private static final List<Path> SCAN_ROOTS = List.of(
        Paths.get("src/main/java/com/apimarketplace/interfaces/tools")
    );

    @Test
    @DisplayName("List actions in interface-service tools.* must use AgentListEnvelope")
    void allListActionsAdoptHelper() throws IOException {
        var violations = new java.util.ArrayList<String>();

        for (Path root : SCAN_ROOTS) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .filter(p -> !ALLOWLIST.contains(p.getFileName().toString()))
                      .forEach(p -> checkFile(p, violations));
            }
        }

        assertThat(violations)
            .as("Files emit a list envelope by hand without going through AgentListEnvelope.")
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
    @DisplayName("Allowlist is empty post-PR3")
    void allowlistMustStayEmpty() {
        assertThat(ALLOWLIST).isEmpty();
    }
}
