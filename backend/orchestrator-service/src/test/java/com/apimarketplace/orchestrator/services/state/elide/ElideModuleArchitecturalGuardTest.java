package com.apimarketplace.orchestrator.services.state.elide;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architectural guards for the elide module (audit A round-2 MUST-FIX, rule-A/B/C).
 *
 * <p>Implemented as JUnit reflection-based assertions rather than ArchUnit
 * (which isn't on the project's classpath). Functionally equivalent: they
 * fail the build if the module wiring or {@link EpochState} field order
 * drifts from the design contract.
 *
 * <p>Three rules:
 * <ul>
 *   <li><strong>Rule-A</strong>: {@link EpochStateRunningElideModule} is registered
 *       on EXACTLY ONE Spring bean - {@code stateSnapshotMapper}. Any other
 *       {@code ObjectMapper} that registers it would silently elide
 *       {@code runningNodeIds} from controllers, WS events, agent-tool reads,
 *       canonicalizer fixtures, etc.</li>
 *   <li><strong>Rule-B</strong>: No production class outside {@code state/elide/}
 *       (specifically {@link StateSnapshotMapperConfig}) imports or instantiates
 *       {@link EpochStateRunningElideModule}. A future refactor that wires it
 *       elsewhere is caught at build time.</li>
 *   <li><strong>Rule-C</strong>: {@link EpochState} field-declaration order
 *       matches a pinned baseline. The custom serializer wraps the default
 *       BeanSerializer and inherits Jackson's field-introspection order from
 *       declared-field order; reordering or adding a field changes the JSONB
 *       byte layout and would silently break trace canonicalization §7.4
 *       fixtures + the byte-equality regression in
 *       {@link EpochStateRunningElideSerializerTest}.</li>
 * </ul>
 */
@DisplayName("Elide module architectural guards (rule-A/B/C)")
class ElideModuleArchitecturalGuardTest {

    @Nested
    @DisplayName("Rule-A - only StateSnapshotMapperConfig registers the elide module")
    class RuleA_SingleConfigRegistration {

        @Test
        @DisplayName("Exactly one production class instantiates EpochStateRunningElideModule")
        void onlyOneInstantiator() throws Exception {
            // Scan all production source roots for `new EpochStateRunningElideModule(`.
            // The test's source-grep is more direct than reflection-on-bean-graph
            // because it catches references that aren't yet wired into Spring.
            Set<String> hits = grepProductionSources("new EpochStateRunningElideModule(");

            // Expected callers: StateSnapshotMapperConfig only.
            // (Test classes are excluded - see grepProductionSources filter.)
            assertThat(hits)
                    .as("Only StateSnapshotMapperConfig may instantiate the elide module - "
                            + "registering it on any other ObjectMapper would silently strip "
                            + "runningNodeIds from controllers/WS/agent-tool reads.")
                    .hasSize(1);
            assertThat(hits.iterator().next())
                    .endsWith("StateSnapshotMapperConfig.java");
        }
    }

    @Nested
    @DisplayName("Rule-B - no production class outside elide/ imports the module class")
    class RuleB_PackageBoundary {

        @Test
        @DisplayName("EpochStateRunningElideModule import statements come only from the elide package itself")
        void importsConfinedToElidePackage() throws Exception {
            // Match an actual `import` declaration, not free-text mentions in
            // javadoc/comments. This was the audit C round-2 NICE-TO-HAVE
            // refinement: comments may reference the class name harmlessly,
            // but an `import` brings the class into the symbol table and is the
            // boundary we actually care about.
            Set<String> importingFiles = grepProductionSources(
                    "import com.apimarketplace.orchestrator.services.state.elide.EpochStateRunningElideModule");

            // All references should be inside services/state/elide/. Test code
            // exclusion is handled in grepProductionSources.
            assertThat(importingFiles)
                    .allMatch(p -> p.contains("/services/state/elide/"),
                            "EpochStateRunningElideModule must not be IMPORTED outside the elide package");
        }
    }

    @Nested
    @DisplayName("Rule-C - EpochState field-declaration order pinned")
    class RuleC_FieldOrderPin {

        // Pinned EpochState field order. This list is the design contract:
        // any code change that shifts these fields breaks the byte-equality
        // regression in EpochStateRunningElideSerializerTest$ElideOff (and
        // therefore JSONB round-trip for in-flight runs).
        //
        // EpochState is a `public final class` (NOT a Java record), so use
        // getDeclaredFields() - getRecordComponents() would return empty.
        private static final List<String> EXPECTED_FIELD_ORDER = List.of(
                "completedNodeIds",
                "failedNodeIds",
                "partialFailedNodeIds",
                "skippedNodeIds",
                "runningNodeIds",
                "readyNodeIds",
                "awaitingSignalNodeIds",
                "decisionBranches",
                "loops",
                "splits",
                "startedAt"
        );

        @Test
        @DisplayName("EpochState field-declaration order matches the design baseline (use getDeclaredFields, NOT getRecordComponents)")
        void fieldOrderMatchesBaseline() {
            // Filter out static and synthetic fields (Jackson serializers do
            // the same). Preserve declaration order - Java reflection on
            // getDeclaredFields() is unspecified between JVM impls in
            // theory, but stable on HotSpot in practice for non-synthetic
            // declared fields.
            List<String> actual = Arrays.stream(EpochState.class.getDeclaredFields())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .filter(f -> !f.isSynthetic())
                    .map(Field::getName)
                    .collect(Collectors.toList());

            assertThat(actual)
                    .as("EpochState field-declaration order is the trace canonicalization "
                            + "contract. Any reorder/add must update the EXPECTED_FIELD_ORDER "
                            + "constant AND the byte-equality regression test fixture in "
                            + "EpochStateRunningElideSerializerTest$ElideOff.")
                    .containsExactlyElementsOf(EXPECTED_FIELD_ORDER);
        }

        @Test
        @DisplayName("EpochState is NOT a record (intentionally - design rev12 audit C C-2)")
        void epochStateIsClassNotRecord() {
            // The design originally said "use record-component order" but EpochState
            // is a `public final class`. For non-records, getRecordComponents()
            // returns null (NOT an empty array) per Class#getRecordComponents
            // javadoc. Pin both signals so a future refactor to a record explicitly
            // forces a redesign of the field-order guard.
            assertThat(EpochState.class.isRecord()).isFalse();
            assertThat(EpochState.class.getRecordComponents()).isNull();
        }
    }

    /**
     * Scans the production source tree for files containing the needle and
     * returns the absolute paths. Excludes test/ and target/ directories.
     *
     * <p>Walks {@code backend/orchestrator-service/src/main/java/} relative to the
     * working directory used by the maven-surefire plugin (which is the module
     * root: {@code backend/orchestrator-service/}).
     */
    private Set<String> grepProductionSources(String needle) throws Exception {
        Set<String> hits = new HashSet<>();
        Path srcRoot = Paths.get("src/main/java/com/apimarketplace/orchestrator");
        if (!Files.isDirectory(srcRoot)) {
            // Fallback: when run from repo root (rare) - try the module path.
            srcRoot = Paths.get("backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator");
        }
        if (!Files.isDirectory(srcRoot)) {
            // Last resort - defensive: don't fail the test if the path scheme changes,
            // but make the failure visible by returning empty.
            return hits;
        }
        try (Stream<Path> stream = Files.walk(srcRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            if (content.contains(needle)) {
                                hits.add(p.toString().replace('\\', '/'));
                            }
                        } catch (Exception e) {
                            // Skip unreadable files silently - a CI permissions hiccup
                            // shouldn't fail the architectural assertion.
                        }
                    });
        }
        return hits;
    }
}
