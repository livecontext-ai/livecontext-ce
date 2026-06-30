package com.apimarketplace.orchestrator.archunit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for the StepOutputsWriter contract - Piste 1.
 *
 * <p>Detects regressions to the legacy inline pattern that caused 3 production bugs
 * in 7 days (Daily Email Digest split alias drift). The pattern is:
 * <pre>
 *   newOutputs.put(nodeId, value);
 *   String alias = nodeId.substring(nodeId.indexOf(':') + 1);   // ← duplicated 6 times
 *   newOutputs.put(alias, value);
 * </pre>
 *
 * <p>If anyone re-introduces this pattern in known writer sites, the test fails with a
 * pointer to {@code StepOutputsWriter.writeWithAlias}. This is not full ArchUnit but
 * a cheap, high-signal source scan - pragmatic for the bug class we're closing.
 *
 * <p>The whitelist of "writers" (files allowed to call low-level Map.put on stepOutputs
 * because they ARE the writer or own a maintenance escape hatch) is explicit and small.
 * Any new writer site must either be added here or, preferably, refactored to use
 * {@code StepOutputsWriter.writeWithAlias}.
 */
@DisplayName("StepOutputsWriter usage - anti-drift guard")
class StepOutputsWriterUsageTest {

    /**
     * Files that ARE allowed to compute aliases inline because they implement the contract
     * themselves or are tests that intentionally construct fixture data.
     */
    private static final List<String> WRITER_WHITELIST = List.of(
        "StepOutputsWriter.java",
        // ExecutionContext.withResult / withStepOutput now delegate to StepOutputsWriter;
        // listed here so we tolerate the small "newOutputs.put(nodeId, ...)" lines they emit
        // - they no longer compute aliases inline.
        "ExecutionContext.java"
    );

    /**
     * Patterns that flag the legacy inline alias extraction in any of its common shapes.
     * Conservative - only the patterns we've seen produce the bug or that are trivially
     * equivalent to it. Audit #2 + audit #3 recommended widening from the single
     * {@code indexOf(':') + 1} shape since it was easily circumventable.
     */
    private static final java.util.List<Pattern> LEGACY_ALIAS_EXTRACTION_PATTERNS = java.util.List.of(
        // 1. substring(indexOf(':') + 1)
        Pattern.compile("\\.indexOf\\(\\s*'\\s*:\\s*'\\s*\\)\\s*\\+\\s*1"),
        // 2. substring(lastIndexOf(':') + 1) - the same bug class with lastIndexOf
        Pattern.compile("\\.lastIndexOf\\(\\s*'\\s*:\\s*'\\s*\\)\\s*\\+\\s*1"),
        // 3. split(":")[1] - array access form
        Pattern.compile("\\.split\\(\\s*\"\\s*:\\s*\"\\s*(?:,\\s*\\d+\\s*)?\\)\\s*\\[\\s*1\\s*\\]"),
        // 4. replaceFirst("^[^:]+:", "") - regex form. Allows for optional escapes/spaces.
        Pattern.compile("\\.replaceFirst\\(\\s*\"\\^\\[\\^:\\]\\+:\"\\s*,\\s*\"\"\\s*\\)")
    );

    @Test
    @DisplayName("No production file outside the whitelist computes a bare alias inline "
            + "(any of the 4 legacy patterns) - use StepOutputsWriter.bareAlias instead")
    void noInlineAliasExtractionOutsideWriter() throws IOException {
        Path srcRoot = Paths.get("src", "main", "java", "com", "apimarketplace", "orchestrator");
        // Fallback if the test runs from a different working directory (e.g. multi-module
        // build from the repo root). Skip-with-message rather than silent pass.
        if (!Files.exists(srcRoot)) {
            srcRoot = Paths.get("backend", "orchestrator-service", "src", "main", "java",
                "com", "apimarketplace", "orchestrator");
        }
        assertThat(Files.exists(srcRoot))
            .as("Source root not found - this test must run from backend/orchestrator-service "
                + "or the repo root. cwd=%s", System.getProperty("user.dir"))
            .isTrue();

        List<String> offenders = new ArrayList<>();
        final Path root = srcRoot;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> WRITER_WHITELIST.stream().noneMatch(name -> p.getFileName().toString().equals(name)))
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        for (Pattern pat : LEGACY_ALIAS_EXTRACTION_PATTERNS) {
                            if (pat.matcher(content).find()) {
                                offenders.add(p.getFileName().toString()
                                    + " (matches: " + pat.pattern() + ")");
                                break;
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }

        assertThat(offenders)
            .as("These files reintroduce an inline alias-extraction pattern that bypasses "
                + "StepOutputsWriter and risks the Daily Email Digest bug class. "
                + "Use StepOutputsWriter.writeWithAlias / bareAlias (or LabelNormalizer."
                + "extractLabelFromKey for display-only extraction) instead. Offenders: %s",
                offenders)
            .isEmpty();
    }
}
