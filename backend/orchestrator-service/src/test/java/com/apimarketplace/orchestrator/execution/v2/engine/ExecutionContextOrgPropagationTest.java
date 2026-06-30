package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR15 regression guards for {@link ExecutionContext} org-scope propagation.
 *
 * <p>Pins that every {@code with*()} mutator preserves the workspace identity
 * ({@code organizationId} / {@code organizationRole}). A regression where any
 * mutator drops the org fields would silently demote subsequent node
 * execution to personal scope - the exact bug class PR15 was designed to
 * prevent.</p>
 */
@DisplayName("ExecutionContext org propagation (PR15)")
class ExecutionContextOrgPropagationTest {

    private static final String RUN_ID = "run-1";
    private static final String WORKFLOW_RUN_ID = "wfrun-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-acme";
    private static final String ORG_ROLE = "OWNER";
    private static final String TRIGGER_ID = "trigger:start";

    private ExecutionContext baseContext() {
        return ExecutionContext.create(
                RUN_ID, WORKFLOW_RUN_ID, TENANT_ID,
                "item-0", 0,
                TRIGGER_ID, 0, 0,
                Map.of("k", "v"),
                (WorkflowPlan) null)
                .withOrganization(ORG_ID, ORG_ROLE);
    }

    @Nested
    @DisplayName("Canonical constructor / factory")
    class FactoryTests {

        @Test
        @DisplayName("withOrganization stamps both id and role")
        void withOrganizationStampsFields() {
            ExecutionContext ctx = ExecutionContext.create(
                    RUN_ID, WORKFLOW_RUN_ID, TENANT_ID, "i", 0,
                    TRIGGER_ID, 0, 0, Map.of(), null)
                    .withOrganization(ORG_ID, ORG_ROLE);

            assertThat(ctx.organizationId()).isEqualTo(ORG_ID);
            assertThat(ctx.organizationRole()).isEqualTo(ORG_ROLE);
        }

        @Test
        @DisplayName("legacy 12-arg constructor defaults org fields to null (personal scope)")
        void legacyConstructorDefaultsToPersonal() {
            ExecutionContext ctx = new ExecutionContext(
                    RUN_ID, WORKFLOW_RUN_ID, TENANT_ID, "i", 0,
                    TRIGGER_ID, 0, 0,
                    new HashMap<>(), new HashMap<>(),
                    com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create(),
                    null);

            assertThat(ctx.organizationId()).isNull();
            assertThat(ctx.organizationRole()).isNull();
        }

        @Test
        @DisplayName("withOrganization(null, null) reverts to personal scope")
        void withOrganizationNullsRevertsToPersonal() {
            ExecutionContext ctx = baseContext().withOrganization(null, null);

            assertThat(ctx.organizationId()).isNull();
            assertThat(ctx.organizationRole()).isNull();
        }
    }

    @Nested
    @DisplayName("Mutator preservation - every with*() keeps org context")
    class MutatorPreservationTests {

        @Test
        @DisplayName("withTriggerId preserves org")
        void withTriggerIdPreservesOrg() {
            assertOrgPreserved(baseContext().withTriggerId("trigger:other"));
        }

        @Test
        @DisplayName("withEpoch preserves org")
        void withEpochPreservesOrg() {
            assertOrgPreserved(baseContext().withEpoch(3));
        }

        @Test
        @DisplayName("withSpawn preserves org")
        void withSpawnPreservesOrg() {
            assertOrgPreserved(baseContext().withSpawn(2));
        }

        @Test
        @DisplayName("withDagCoordinates preserves org")
        void withDagCoordinatesPreservesOrg() {
            assertOrgPreserved(baseContext().withDagCoordinates("trigger:other", 1, 1));
        }

        @Test
        @DisplayName("withStart preserves org")
        void withStartPreservesOrg() {
            assertOrgPreserved(baseContext().withStart("nodeX"));
        }

        @Test
        @DisplayName("withResult preserves org")
        void withResultPreservesOrg() {
            NodeExecutionResult result = NodeExecutionResult.success("nodeX", Map.of("k", "v"));
            assertOrgPreserved(baseContext().withResult("nodeX", result));
        }

        @Test
        @DisplayName("withGlobalData preserves org")
        void withGlobalDataPreservesOrg() {
            assertOrgPreserved(baseContext().withGlobalData("k", "v"));
        }

        @Test
        @DisplayName("withItemIndex preserves org")
        void withItemIndexPreservesOrg() {
            assertOrgPreserved(baseContext().withItemIndex(5));
        }

        @Test
        @DisplayName("withStepOutput preserves org")
        void withStepOutputPreservesOrg() {
            assertOrgPreserved(baseContext().withStepOutput("nodeX", Map.of("k", "v")));
        }

        @Test
        @DisplayName("withoutNodes preserves org")
        void withoutNodesPreservesOrg() {
            assertOrgPreserved(baseContext().withoutNodes(Set.of("nodeX")));
        }

        @Test
        @DisplayName("merge preserves own org context (both branches share workspace)")
        void mergePreservesOrg() {
            ExecutionContext other = baseContext().withTriggerId("trigger:other");
            assertOrgPreserved(baseContext().merge(other));
        }

        private void assertOrgPreserved(ExecutionContext ctx) {
            assertThat(ctx.organizationId())
                    .as("Mutator MUST preserve organizationId - regression here demotes "
                      + "subsequent node execution to personal scope.")
                    .isEqualTo(ORG_ID);
            assertThat(ctx.organizationRole())
                    .as("Mutator MUST preserve organizationRole - same regression risk.")
                    .isEqualTo(ORG_ROLE);
        }
    }

    @Nested
    @DisplayName("Personal-scope context - null org stays null through mutators")
    class PersonalScopePropagationTests {

        @Test
        @DisplayName("personal context (null org) stays personal after withEpoch")
        void personalStaysPersonal() {
            ExecutionContext ctx = ExecutionContext.create(
                    RUN_ID, WORKFLOW_RUN_ID, TENANT_ID, "i", 0,
                    TRIGGER_ID, 0, 0, Map.of(), null);

            ExecutionContext after = ctx.withEpoch(5);

            assertThat(after.organizationId())
                    .as("Personal-scope context MUST NOT acquire an org id through a mutator")
                    .isNull();
            assertThat(after.organizationRole()).isNull();
        }
    }
}
