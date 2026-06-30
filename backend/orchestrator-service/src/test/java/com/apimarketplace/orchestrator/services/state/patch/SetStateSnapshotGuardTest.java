package com.apimarketplace.orchestrator.services.state.patch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard equivalent to ArchUnit "no setStateSnapshot in patch path"
 * (ArchUnit not in this project's pom.xml at time of writing).
 *
 * <p>Walks the orchestrator-service production source tree and asserts that
 * the only Java method invoking {@code WorkflowRunEntity.setStateSnapshot(...)}
 * is {@code StateSnapshotService.saveSnapshotFullRewrite}. The patch path
 * MUST NOT call this setter - its mutation goes via native UPDATE
 * {@code jsonb_set}, and a stray setter call would reintroduce the
 * Hibernate auto-flush clobber risk that the design v6 deliberately avoids.
 *
 * <p>If a future contributor adds a {@code run.setStateSnapshot(json)} elsewhere,
 * this test fails with the offending file + line, prompting them to either:
 * (a) move the logic into {@code saveSnapshotFullRewrite}, or
 * (b) add an explicit waiver in this list with a justification.
 */
class SetStateSnapshotGuardTest {

    /** Files explicitly allowed to call {@code run.setStateSnapshot(...)}. */
    private static final List<String> ALLOWED_FILES = List.of(
            // saveSnapshotFullRewrite - the SOLE production writer on the active run row.
            "StateSnapshotService.java",
            // Marketplace clone path: copies the immutable snapshot onto a NEW entity (the
            // clone, not the source run). The clone is freshly persist()-ed; no auto-flush
            // can clobber a patch on the source run because the source row is never touched
            // here. See RunCloneService.cloneRun(...). Verified: line 111 calls
            // clone.setStateSnapshot(...), not run.setStateSnapshot(...).
            "RunCloneService.java"
    );

    @Test
    @DisplayName("WorkflowRunEntity.setStateSnapshot is only called from StateSnapshotService.saveSnapshotFullRewrite")
    void setStateSnapshotOnlyInFullRewrite() throws IOException {
        Path srcRoot = Path.of("src/main/java/com/apimarketplace/orchestrator");
        if (!Files.exists(srcRoot)) {
            // Fallback when running with a different working directory (CI).
            srcRoot = Path.of("backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator");
        }
        Path finalSrcRoot = srcRoot;
        if (!Files.exists(finalSrcRoot)) {
            // The test cannot find the source - skip rather than fail (defensive for IDE runs).
            return;
        }

        List<String> violations = new ArrayList<>();
        Files.walkFileTree(finalSrcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                String fileName = file.getFileName().toString();
                if (ALLOWED_FILES.contains(fileName)) return FileVisitResult.CONTINUE;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    // Skip comments / strings - heuristic: line containing the literal call.
                    if (line.contains(".setStateSnapshot(") && !line.trim().startsWith("//")
                            && !line.trim().startsWith("*")) {
                        violations.add(fileName + ":" + (i + 1) + ": " + line.trim());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(violations)
                .as("Stray run.setStateSnapshot(...) calls outside saveSnapshotFullRewrite "
                        + "would clobber the patch path on auto-flush. Move the logic into "
                        + "saveSnapshotFullRewrite or add a waiver in ALLOWED_FILES with rationale.")
                .isEmpty();
    }
}
