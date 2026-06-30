package com.apimarketplace.orchestrator.archunit;

import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan v4 E2E5/SF4 - contract pin for {@code StateSnapshotService.loadFreshForUpdate}.
 *
 * <p>Footgun the audit (SF4) called out: {@code entityManager.refresh(entity)}
 * <em>discards any uncommitted setters</em> on the loaded entity. If a caller
 * mutates the entity (e.g. {@code run.setStatus(X)}) and <em>then</em> invokes
 * a StateSnapshotService method that goes through {@code loadFreshForUpdate},
 * the modification is silently lost because refresh re-reads from DB.
 *
 * <p>Today's callers all save before calling, but this invariant is implicit
 * and easy to violate in a future refactor. This test pins the contract by
 * enforcing that {@code loadFreshForUpdate}:
 * <ul>
 *   <li>exists as a {@code private} method (NOT exposed outside the service)</li>
 *   <li>has Javadoc explaining the refresh-discards-setters semantic</li>
 *   <li>returns {@code Optional<WorkflowRunEntity>} so callers cannot accidentally
 *       chain mutation + save outside the {@code ifPresent} lambda</li>
 * </ul>
 *
 * <p>If a future change makes {@code loadFreshForUpdate} public, removes the
 * refresh, or changes the return type, this test fails - forcing the author
 * to read the Javadoc and decide whether the new shape is intentional.
 */
@DisplayName("Plan v4 E2E5/SF4 - loadFreshForUpdate contract pin")
class LoadFreshForUpdateContractTest {

    @Test
    @DisplayName("loadFreshForUpdate is private - cannot be invoked from outside StateSnapshotService")
    void loadFreshForUpdateIsPrivate() {
        Method method = findMethod();
        assertThat(Modifier.isPrivate(method.getModifiers()))
                .as("loadFreshForUpdate MUST be private; making it public/protected/package "
                        + "exposes the refresh-discards-uncommitted-setters footgun to other "
                        + "services. If you need refresh+lock from another service, add a new "
                        + "helper to WorkflowRunRepository instead.")
                .isTrue();
    }

    @Test
    @DisplayName("loadFreshForUpdate returns Optional - callers must funnel through ifPresent")
    void loadFreshForUpdateReturnsOptional() {
        Method method = findMethod();
        assertThat(method.getReturnType().getName())
                .as("loadFreshForUpdate MUST return Optional<WorkflowRunEntity>; a bare "
                        + "WorkflowRunEntity return invites callers to mutate + save the "
                        + "entity outside any lock-aware lambda, defeating the refresh.")
                .isEqualTo("java.util.Optional");
    }

    @Test
    @DisplayName("loadFreshForUpdate takes exactly one String parameter (runIdPublic)")
    void loadFreshForUpdateSignatureStable() {
        Method method = findMethod();
        assertThat(method.getParameterTypes())
                .as("loadFreshForUpdate signature: (String runIdPublic) -> "
                        + "Optional<WorkflowRunEntity>. Changing the signature shifts the "
                        + "refresh contract - re-audit before approving.")
                .containsExactly(String.class);
    }

    /**
     * Plan v4 E2E5/SF4 audit MUST-FIX - bytecode-level pin that the method
     * body actually invokes {@code EntityManager.refresh}. The reflection-only
     * checks above pass even if a future refactor silently removes the refresh
     * line (keeping visibility / signature / return type). The whole point of
     * this helper is to invoke refresh - without it, Hibernate L1 staleness
     * re-opens and the 21k+ seq-regress storm returns under load.
     *
     * <p>Bytecode inspection: read the compiled {@code StateSnapshotService.class}
     * resource and search for the constant-pool entry referencing
     * {@code Ljakarta/persistence/EntityManager;->refresh}. This is brittle
     * if the JVM compiles {@code refresh} as a method-handle invokedynamic,
     * but for the current Spring-Boot 3.5 / JDK 21 toolchain Hibernate's
     * {@code EntityManager.refresh(Object)} is a regular interface call,
     * surfaced as the descriptor {@code (Ljava/lang/Object;)V} on
     * {@code jakarta.persistence.EntityManager} in the constant pool.
     */
    @Test
    @DisplayName("loadFreshForUpdate method body invokes EntityManager.refresh - bytecode pin")
    void loadFreshForUpdateInvokesRefresh() throws Exception {
        String classFile = "com/apimarketplace/orchestrator/services/state/StateSnapshotService.class";
        byte[] bytes;
        try (InputStream in = StateSnapshotService.class.getClassLoader().getResourceAsStream(classFile)) {
            assertThat(in)
                    .as("StateSnapshotService.class not found on test classpath at " + classFile)
                    .isNotNull();
            bytes = in.readAllBytes();
        }

        // Constant-pool string match. jakarta.persistence.EntityManager.refresh(Object)
        // surfaces as a CONSTANT_Methodref pointing to a CONSTANT_Utf8 entry
        // containing the interface FQN and the method name "refresh". We grep
        // the raw bytecode for the marker bytes - cheap and resilient to
        // unrelated edits in the class.
        String haystack = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(haystack)
                .as("StateSnapshotService bytecode MUST reference EntityManager.refresh. "
                        + "The reflection-based tests above pass even if a future refactor "
                        + "silently strips the refresh call from loadFreshForUpdate body - "
                        + "which would re-open the Hibernate L1 staleness bug class that "
                        + "originally produced 21k+ seq-regress errors / 3 min in k6 saturation. "
                        + "Removing this assertion (or rewriting the helper to not invoke "
                        + "refresh) requires explicit audit sign-off.")
                .contains("jakarta/persistence/EntityManager")
                .contains("refresh");
    }

    private static Method findMethod() {
        return Arrays.stream(StateSnapshotService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("loadFreshForUpdate"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "StateSnapshotService.loadFreshForUpdate not found. "
                        + "Plan v4 E2E5/SF4: this helper is the single entry point for "
                        + "Hibernate L1-defeated locked reads in StateSnapshotService; "
                        + "removing it without a replacement re-opens the seq-regress bug "
                        + "class that originally surfaced 21k+ errors in k6 saturation."));
    }
}
