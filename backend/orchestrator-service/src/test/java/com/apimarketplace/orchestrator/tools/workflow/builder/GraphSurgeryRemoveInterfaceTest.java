package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.workflow.InterfaceDef;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for {@link GraphSurgery#removeNodeFromPlan(WorkflowPlan, String)}.
 *
 * <p>Before the fix, removing an interface node via
 * {@code workflow(action='remove')} stripped the edges connected to it but
 * left the {@code interfaces[]} entry untouched, leaving the plan with a
 * dangling reference to a deleted interface and breaking the builder.
 */
@DisplayName("GraphSurgery.removeNodeFromPlan - interface scrub regression guard")
class GraphSurgeryRemoveInterfaceTest {

    @Test
    @DisplayName("Removes the matching InterfaceDef from plan.interfaces[] when its normalized key matches the deleted node id")
    void removesInterfaceDefMatchingNodeId() {
        // Surgery operates on Java domain objects, no DB needed - pass null
        // dependencies because the only thing under test is the pure helper.
        // SAFE ONLY for removeNodeFromPlan(...) - every other GraphSurgery
        // method dereferences these fields. Do not reuse this construction
        // pattern for new tests in this class without adding real mocks.
        GraphSurgery surgery = new GraphSurgery(null, null, null, null);

        InterfaceDef deletedIface = new InterfaceDef(
            "368c7061-696d-4dde-a282-2111a4efc4af",
            "Comparison Dashboard",
            Map.of(),
            Map.of(),
            true,
            Map.of("x", 0, "y", 0),
            true
        );
        InterfaceDef survivingIface = new InterfaceDef(
            "11111111-1111-1111-1111-111111111111",
            "Other Form",
            Map.of(),
            Map.of(),
            true,
            Map.of("x", 0, "y", 0),
            false
        );

        WorkflowPlan plan = new WorkflowPlan(
            "wf-1", "tenant-1",
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(deletedIface, survivingIface),
            Map.of()
        );

        WorkflowPlan after = surgery.removeNodeFromPlan(plan, "interface:comparison_dashboard");

        // The deleted interface is gone; the unrelated one is preserved. If
        // this regression test starts failing, the bug we fixed (dangling
        // interfaces[] entry after workflow(action='remove')) is back.
        assertThat(after.getInterfaces()).extracting(InterfaceDef::id)
            .doesNotContain("368c7061-696d-4dde-a282-2111a4efc4af")
            .contains("11111111-1111-1111-1111-111111111111");
    }
}
