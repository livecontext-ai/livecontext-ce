package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.testsupport.SourceTrees;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static-analysis guard preventing the Java-text-block concat anti-pattern that
 * caused the Gmail-save 500 in commit 602c525c5.
 *
 * <p>Java text blocks ({@code """..."""}, JEP 378) <b>strip trailing whitespace</b>
 * from every content line. When a constant is spliced between two text blocks via
 * {@code """ + CONST + """}, the trailing space before the closing {@code """} is
 * stripped, and {@code CONST} fuses onto the previous token. For SQL:
 *
 * <pre>
 *   WHERE ... AND """ + TENANT_FILTER + """
 * </pre>
 *
 * becomes {@code ... AND(tenant_id...} - a syntax error.
 *
 * <p>This test reads every {@code *Repository.java} in the main sources of every
 * {@code backend/} module and
 * fails the build if the anti-pattern is present. New core-tool repositories
 * written in the future are automatically covered.
 *
 * <p>The canonical replacement is:
 *
 * <pre>
 *   String sql = """
 *       WHERE ... AND %s
 *       ORDER BY %s
 *       """.formatted(TENANT_FILTER, TENANT_ORDER);
 * </pre>
 */
@DisplayName("Text-block SQL concat anti-pattern guard")
class TextBlockSqlConcatGuardTest {

    // `""" + IDENT + """` - three double-quotes, optional whitespace, `+`, a Java
    // identifier, `+`, three double-quotes. Deliberately strict to avoid false
    // positives on prose-style triple-quote concatenation (which is also suspect
    // but not a SQL-crashing bug).
    private static final Pattern BAD_CONCAT = Pattern.compile(
            "\"{3}\\s*\\+\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\+\\s*\"{3}");

    @Test
    @DisplayName("No *Repository.java file in backend/ concatenates identifiers between two text blocks")
    void noTextBlockIdentConcatInAnyRepository() throws IOException {
        Path backendRoot = locateBackendRoot();

        // Main sources only, never a module's target/: the ce-auth shard runs another Maven
        // lane at the same time, whose surefire deletes files there while a walk is inside.
        List<Path> repositories = SourceTrees.mainJavaSources(backendRoot).stream()
                .filter(p -> p.getFileName().toString().endsWith("Repository.java"))
                .toList();

        // A scan that found nothing would pass forever while guarding nothing.
        assertThat(repositories)
                .as("the scan must reach the repositories of every service, not just this one")
                .hasSizeGreaterThan(100)
                .anyMatch(p -> p.startsWith(backendRoot.resolve("auth-service")))
                .anyMatch(p -> p.startsWith(backendRoot.resolve("orchestrator-service")));

        List<String> offences = new ArrayList<>();
        repositories.forEach(p -> scanForAntiPattern(p, offences));

        assertThat(offences)
                .as("Repositories must not use `\"\"\" + IDENT + \"\"\"` concat - "
                        + "Java text blocks strip trailing whitespace on every content line, "
                        + "fusing the identifier onto the previous token. "
                        + "Use `.formatted(IDENT)` with explicit `%s` slots instead.")
                .isEmpty();
    }

    private static void scanForAntiPattern(Path file, List<String> offences) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String stripped = stripBlockComments(content);
        String[] lines = stripped.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String code = stripLineComment(line);
            Matcher m = BAD_CONCAT.matcher(code);
            if (m.find()) {
                offences.add(file + ":" + (i + 1) + "  " + line.trim());
            }
        }
    }

    /** Blank out {@code /* ... *\/} spans so identifiers inside them don't trip the regex. */
    private static String stripBlockComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
                // preserve newlines so line numbers stay accurate
                int end = src.indexOf("*/", i + 2);
                int stop = end < 0 ? src.length() : end + 2;
                for (int j = i; j < stop; j++) {
                    char c = src.charAt(j);
                    out.append(c == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else {
                out.append(src.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** Drop any {@code //} trailing comment on a single line. */
    private static String stripLineComment(String line) {
        int idx = line.indexOf("//");
        // Be careful: `//` may appear inside a string literal. For our use case -
        // finding `""" + IDENT + """` - the pattern never legitimately lives
        // after a `//`, so a naive strip is safe enough.
        return idx < 0 ? line : line.substring(0, idx);
    }

    /**
     * Walk upward until we find a directory containing {@code auth-service}
     * (that's the {@code backend/} root). Works from multiple cwds:
     * <ul>
     *   <li>{@code backend/auth-service} (single-module run)</li>
     *   <li>{@code backend} (aggregate run)</li>
     *   <li>repo root (CI)</li>
     * </ul>
     */
    private static Path locateBackendRoot() {
        List<Path> candidates = List.of(
                Paths.get(".."),                      // from backend/auth-service
                Paths.get("."),                       // from backend
                Paths.get("backend")                  // from repo root
        );
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized.resolve("auth-service"))
                    && Files.isDirectory(normalized.resolve("orchestrator-service"))) {
                return normalized;
            }
        }
        throw new IllegalStateException(
                "Could not locate backend/ root from cwd " + Paths.get(".").toAbsolutePath());
    }
}
