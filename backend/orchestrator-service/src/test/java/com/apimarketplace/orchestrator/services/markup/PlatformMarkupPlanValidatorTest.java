package com.apimarketplace.orchestrator.services.markup;

import com.apimarketplace.orchestrator.domain.workflow.CredentialSource;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlatformMarkupPlanValidator - submit-time invariants")
class PlatformMarkupPlanValidatorTest {

    PlatformMarkupPlanValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PlatformMarkupPlanValidator();
    }

    private Step mcpPlatform(String label, Long credId) {
        return new Step("id-" + label, "mcp", label, null, Map.of(), null, null, null,
                CredentialSource.PLATFORM, credId);
    }

    private Step mcpUser(String label) {
        return new Step("id-" + label, "mcp", label, null, Map.of(), null, null, null,
                CredentialSource.USER, null);
    }

    private Step tablePlatform(String label, Long credId) {
        // Bypass Step compact-constructor check by using CRUD type
        return new Step("id-" + label, "crud-read-row", label, null, Map.of(), 7L, null, null,
                CredentialSource.PLATFORM, credId);
    }

    private WorkflowPlan planOf(List<Step> mcps, List<Step> tables) {
        return new WorkflowPlan(
                "plan-1", "tenant-1",
                List.of(), mcps == null ? List.of() : mcps,
                List.of(), List.of(), List.of(),
                tables == null ? List.of() : tables,
                List.of(), List.of(), Map.of());
    }

    @Test
    @DisplayName("null plan is a no-op - never throws")
    void nullPlanIsNoOp() {
        assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("empty plan passes validation")
    void emptyPlanPasses() {
        assertThatCode(() -> validator.validate(planOf(List.of(), List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("all user-credential steps pass")
    void allUserStepsPass() {
        WorkflowPlan plan = planOf(List.of(mcpUser("send"), mcpUser("fetch")), List.of());
        assertThatCode(() -> validator.validate(plan)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("well-formed platform mcp step passes")
    void wellFormedPlatformStepPasses() {
        WorkflowPlan plan = planOf(List.of(mcpPlatform("send", 42L)), List.of());
        assertThatCode(() -> validator.validate(plan)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("platform step on a table/CRUD is rejected - markup only applies to mcp")
    void platformOnTableIsRejected() {
        WorkflowPlan plan = planOf(List.of(), List.of(tablePlatform("query_customers", 99L)));

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(PlatformMarkupPlanValidator.InvalidMarkupPlanException.class)
                .hasMessageContaining("query_customers")
                .hasMessageContaining("only mcp steps bill markup");
    }

    @Test
    @DisplayName("user step with a platformCredentialId is rejected - ambiguous billing")
    void userStepWithPlatformCredentialRejected() {
        Step ambiguous = new Step("id-1", "mcp", "send", null, Map.of(), null, null, null,
                CredentialSource.USER, 77L);
        WorkflowPlan plan = planOf(List.of(ambiguous), List.of());

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(PlatformMarkupPlanValidator.InvalidMarkupPlanException.class)
                .hasMessageContaining("ambiguous billing");
    }

    @Test
    @DisplayName("multiple violations are surfaced together, not one at a time")
    void multipleViolationsAggregated() {
        Step ambiguous = new Step("id-2", "mcp", "fetch", null, Map.of(), null, null, null,
                CredentialSource.USER, 55L);
        WorkflowPlan plan = planOf(List.of(ambiguous), List.of(tablePlatform("bad_crud", 100L)));

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(PlatformMarkupPlanValidator.InvalidMarkupPlanException.class)
                .satisfies(ex -> {
                    var e = (PlatformMarkupPlanValidator.InvalidMarkupPlanException) ex;
                    assertThat(e.getViolations()).hasSize(2);
                    assertThat(e.getViolations())
                            .anyMatch(v -> v.contains("fetch"))
                            .anyMatch(v -> v.contains("bad_crud"));
                });
    }

    @Test
    @DisplayName("mix of valid + invalid steps - only the invalid ones produce violations")
    void mixedStepsReportOnlyInvalid() {
        Step good1 = mcpPlatform("good_platform", 42L);
        Step good2 = mcpUser("good_user");
        Step bad = new Step("id-bad", "mcp", "bad_user_with_credid", null, Map.of(), null, null, null,
                CredentialSource.USER, 999L);
        WorkflowPlan plan = planOf(List.of(good1, good2, bad), List.of());

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(PlatformMarkupPlanValidator.InvalidMarkupPlanException.class)
                .satisfies(ex -> {
                    var e = (PlatformMarkupPlanValidator.InvalidMarkupPlanException) ex;
                    assertThat(e.getViolations()).hasSize(1);
                    assertThat(e.getViolations().get(0)).contains("bad_user_with_credid");
                });
    }

    @Test
    @DisplayName("violation message includes the field name so agent can auto-correct")
    void violationMessageIsActionable() {
        Step ambiguous = new Step("id-3", "mcp", "my_step", null, Map.of(), null, null, null,
                CredentialSource.USER, 77L);
        WorkflowPlan plan = planOf(List.of(ambiguous), List.of());

        assertThatThrownBy(() -> validator.validate(plan))
                .hasMessageContaining("platformCredentialId")
                .hasMessageContaining("credentialSource=user");
    }
}
