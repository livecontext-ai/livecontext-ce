package com.apimarketplace.orchestrator.archunit;

import com.apimarketplace.orchestrator.controllers.workflow.WorkflowRunQueryController;
import com.apimarketplace.orchestrator.controllers.workflow.WorkflowStepOutputController;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the run surface from silently losing its intra-organization gate (LC-012,
 * security audit 2026-08-13).
 *
 * <p>The run endpoints were workspace-scoped ({@code ScopeGuard}) but had no layer-2
 * check at all: no handler took {@code X-Organization-Role} and the controllers did not
 * even hold an {@code OrgAccessGuard}. The frontend hid the launcher from a VIEWER on the
 * belief the backend refused, which was true for exactly one endpoint elsewhere. The gap
 * was invisible precisely because nothing failed when a handler skipped the check, so the
 * fix comes with this rule.
 *
 * <p>Second pass: the first version of this rule was pinned to one class, and that is how
 * {@link WorkflowStepOutputController} - eleven handlers in the same package serving the
 * SAME persisted step output, drillable by JSON path - stayed ungated through a pass whose
 * stated target was "the endpoint that returns the raw persisted payload". Two changes
 * follow from that:
 * <ul>
 *   <li>the gate check is TRANSITIVE within the declaring class, because a handler that
 *       delegates to a private {@code resolve...InRunScope} helper is gated just as
 *       properly as one that calls the guard inline;</li>
 *   <li>a third rule fails the build when a NEW class in the package reads persisted step
 *       output without joining the gated set, so the next sibling cannot be invisible the
 *       way this one was.</li>
 * </ul>
 */
@DisplayName("Workflow run/step read surface - org-gate invariants (LC-012)")
class WorkflowRunQueryOrgGateInvariantTest {

    private static final String ROLE_HEADER = "X-Organization-Role";

    /** Spring mapping annotations. A handler is any method carrying one of these. */
    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping");

    /**
     * The authorization questions that count. {@code canReadRun} /
     * {@code canReadWorkflowResource} are the read gate, {@code canWrite} /
     * {@code isRoleWriteBlocked} the write gate, and {@code getRestrictedResourceIds} the
     * bulk form of the read gate (one call instead of one per id).
     */
    private static final Set<String> GATE_METHODS = Set.of(
            "canReadRun", "canReadWorkflowResource",
            "canAccess", "canWrite", "isRoleWriteBlocked", "getRestrictedResourceIds");

    /** Every controller in the package that answers with persisted run / step-output data. */
    private static final List<Class<?>> GATED_CONTROLLERS =
            List.of(WorkflowRunQueryController.class, WorkflowStepOutputController.class);

    /**
     * Services that read a step's persisted output. A class in this package that depends on
     * one of them is, by definition, another way to obtain the same payload.
     */
    private static final Set<String> STEP_OUTPUT_READERS = Set.of(
            "com.apimarketplace.orchestrator.services.StorageNestedService",
            "com.apimarketplace.orchestrator.services.StorageSkeletonService",
            "com.apimarketplace.orchestrator.services.StepOutputService",
            "com.apimarketplace.orchestrator.stepdata.DetailedStepDataService");

    private final JavaClasses workflowControllers = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.apimarketplace.orchestrator.controllers.workflow");

    @Test
    @DisplayName("every handler on a gated controller asks an authorization question")
    void everyHandlerReachesTheGate() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : GATED_CONTROLLERS) {
            JavaClass javaClass = workflowControllers.get(controller);
            javaClass.getMethods().stream()
                    .filter(WorkflowRunQueryOrgGateInvariantTest::isHandler)
                    .filter(m -> !reachesGate(m, javaClass, new HashSet<>()))
                    .map(m -> controller.getSimpleName() + "." + m.getName())
                    .sorted()
                    .forEach(offenders::add);
        }

        assertThat(offenders)
                .as("Handlers that never ask an authorization question (%s), directly or through a "
                        + "helper of their own class. The workspace check (ScopeGuard) says the caller "
                        + "is IN the organization; only the role gate says what they may do inside it.",
                        GATE_METHODS)
                .isEmpty();
    }

    @Test
    @DisplayName("every gated controller reads the caller's role from the gateway header")
    void everyGatedControllerReadsTheRoleHeader() {
        // Reaching the gate is not enough on its own: a handler could reach it with a
        // hardcoded role. The role has exactly one legitimate source, the header the gateway
        // injects (and strips from client input), so a controller that never names it cannot
        // be passing the caller's real role.
        List<String> offenders = GATED_CONTROLLERS.stream()
                .filter(c -> !mentionsRoleHeader(workflowControllers.get(c)))
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("Gated controllers that never obtain " + ROLE_HEADER + ", either as a "
                        + "@RequestHeader parameter or by reading it off the request")
                .isEmpty();
    }

    @Test
    @DisplayName("every WorkflowRunQueryController handler declares the role header parameter")
    void everyRunQueryHandlerDeclaresTheRoleHeader() {
        // This controller takes its identity headers as parameters, so the header can be
        // required per handler here - the stricter of the two forms, kept from the first pass.
        JavaClass javaClass = workflowControllers.get(WorkflowRunQueryController.class);
        List<String> offenders = javaClass.getMethods().stream()
                .filter(WorkflowRunQueryOrgGateInvariantTest::isHandler)
                .filter(m -> !declaresRoleHeader(m))
                .map(JavaMethod::getName)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("Handlers on WorkflowRunQueryController with no @RequestHeader(\"" + ROLE_HEADER
                        + "\") parameter. Add the header and pass it to "
                        + "WorkflowControllerHelper.canReadRun / canReadWorkflowResource next to the "
                        + "existing scope check.")
                .isEmpty();
    }

    @Test
    @DisplayName("no ungated sibling in the package serves persisted step output")
    void noUngatedSiblingServesStepOutput() {
        Set<String> gatedNames = GATED_CONTROLLERS.stream()
                .map(Class::getName)
                .collect(java.util.stream.Collectors.toSet());

        List<String> offenders = workflowControllers.stream()
                .filter(c -> !gatedNames.contains(c.getFullName()))
                .filter(c -> c.getMethods().stream().anyMatch(WorkflowRunQueryOrgGateInvariantTest::isHandler))
                .filter(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> STEP_OUTPUT_READERS.contains(d.getTargetClass().getFullName())))
                .map(JavaClass::getSimpleName)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("Controllers reading persisted step output (%s) without being part of the gated "
                        + "set. Add them to GATED_CONTROLLERS - which makes the two rules above apply "
                        + "to them - rather than removing them from this list. A deny-listed member "
                        + "refused on one endpoint reads the identical payload here otherwise.",
                        STEP_OUTPUT_READERS)
                .isEmpty();
    }

    private static boolean isHandler(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(a -> MAPPING_ANNOTATIONS.contains(a.getRawType().getFullName()));
    }

    /**
     * Does {@code method} call a gate method, directly or through another method of the SAME
     * class? Every handler of {@link WorkflowStepOutputController} delegates its whole
     * scope+role decision to one private helper, and a rule that only looked one level deep
     * would report all eleven as offenders.
     */
    private static boolean reachesGate(JavaMethod method, JavaClass owner, Set<String> visited) {
        if (!visited.add(method.getFullName())) {
            return false;
        }
        for (var call : method.getMethodCallsFromSelf()) {
            if (GATE_METHODS.contains(call.getTarget().getName())) {
                return true;
            }
            if (!call.getTargetOwner().equals(owner)) {
                continue;
            }
            for (JavaMethod candidate : owner.getMethods()) {
                if (candidate.getName().equals(call.getTarget().getName())
                        && reachesGate(candidate, owner, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The role header can arrive as a {@code @RequestHeader} parameter (annotation, visible on
     * the method) or be read off the {@code HttpServletRequest} (a string constant, visible in
     * the class's field/annotation-free constant usage). Both count; neither may be absent.
     */
    private static boolean mentionsRoleHeader(JavaClass controller) {
        if (controller.getMethods().stream().anyMatch(
                WorkflowRunQueryOrgGateInvariantTest::declaresRoleHeader)) {
            return true;
        }
        // Read off the request: the constant is in the class, not in an annotation.
        return SourceFiles.of(controller).contains("\"" + ROLE_HEADER + "\"");
    }

    private static boolean declaresRoleHeader(JavaMethod method) {
        return method.getParameterAnnotations().stream()
                .flatMap(java.util.Collection::stream)
                .anyMatch(a -> "org.springframework.web.bind.annotation.RequestHeader"
                        .equals(a.getRawType().getFullName())
                        && ROLE_HEADER.equals(String.valueOf(a.getProperties().get("value"))));
    }

    /** Reads a controller's own source file, so a string constant is observable. */
    private static final class SourceFiles {
        static String of(JavaClass controller) {
            java.nio.file.Path dir = java.nio.file.Paths.get("").toAbsolutePath();
            while (dir != null && !java.nio.file.Files.exists(
                    dir.resolve("backend/orchestrator-service"))) {
                dir = dir.getParent();
            }
            assertThat(dir).as("repository root not found from the working directory").isNotNull();
            java.nio.file.Path file = dir.resolve("backend/orchestrator-service/src/main/java/"
                    + controller.getFullName().replace('.', '/') + ".java");
            assertThat(java.nio.file.Files.exists(file))
                    .as("source of %s not found at %s", controller.getSimpleName(), file).isTrue();
            try {
                return java.nio.file.Files.readString(file);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Unable to read " + file, e);
            }
        }
    }
}
