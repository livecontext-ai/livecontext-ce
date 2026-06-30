package com.apimarketplace.catalog.service.http.bodypath;

import com.apimarketplace.catalog.service.http.HttpExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Symmetric guard tests for the bodyTransform mechanism. Two senses:
 *
 * <ol>
 *   <li>JSON -> Java: every bodyTransform string declared in any scripts/api-migrations
 *       JSON migration MUST be implemented (or explicitly accepted) in
 *       {@link HttpExecutionService#IMPLEMENTED_BODY_TRANSFORMS}. Catches the historical
 *       Forms / MS Graph silent fallthrough bugs.
 *   <li>Java -> JSON: every value in {@code IMPLEMENTED_BODY_TRANSFORMS} MUST be used by
 *       at least one JSON migration. Catches dead Java code added without a paired JSON
 *       declaration (eventual symmetry to prevent the inverse drift).
 * </ol>
 */
class BodyTransformImplementationGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path MIGRATIONS_DIR = resolveMigrationsDir();

    private static Path resolveMigrationsDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("scripts/api-migrations");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // catalog-service runs tests with cwd = backend/catalog-service
        return cwd.getParent().getParent().resolve("scripts/api-migrations");
    }

    private Set<String> collectDeclaredBodyTransforms() throws IOException {
        Set<String> declared = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS_DIR)) {
            files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> declared.addAll(extractFrom(p)));
        }
        return declared;
    }

    private static List<String> extractFrom(Path file) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            JsonNode endpoints = root.path("endpoints");
            if (!endpoints.isArray()) {
                return List.of();
            }
            return endpoints.findValues("bodyTransform").stream()
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .filter(s -> !s.isBlank())
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }

    @Test
    @DisplayName("Every JSON-declared bodyTransform has a Java implementation")
    void jsonDeclaredTransformsAreImplementedInJava() throws IOException {
        Set<String> declared = collectDeclaredBodyTransforms();
        Set<String> implemented = HttpExecutionService.IMPLEMENTED_BODY_TRANSFORMS;
        assertThat(declared)
                .as("bodyTransform values found in api-migrations JSON that are NOT "
                        + "implemented in HttpExecutionService.applyBodyTransform "
                        + "(would fall through to default and silently break the endpoint)")
                .allMatch(implemented::contains);
    }

    @Test
    @DisplayName("Every Java-implemented bodyTransform is referenced by at least one JSON migration")
    void javaImplementedTransformsAreUsedInSomeJson() throws IOException {
        Set<String> declared = collectDeclaredBodyTransforms();
        Set<String> implemented = HttpExecutionService.IMPLEMENTED_BODY_TRANSFORMS;
        assertThat(implemented)
                .as("bodyTransform values implemented in HttpExecutionService.applyBodyTransform "
                        + "but referenced by NO JSON migration - dead Java case, drop it or "
                        + "add the corresponding JSON declaration")
                .allMatch(declared::contains);
    }
}
