package com.apimarketplace.orchestrator.validation;

import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.InterfaceDef;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for PlanAliasValidator.
 *
 * <p>The validator catches alias collisions at boot - two nodes whose labels normalize
 * to the same alias would create a non-deterministic stepOutputs lookup at runtime.
 * Strict mode throws; lenient mode (default) logs a warning.
 */
@DisplayName("PlanAliasValidator")
class PlanAliasValidatorTest {

    private static Trigger trigger(String label) {
        Trigger t = mock(Trigger.class);
        when(t.label()).thenReturn(label);
        when(t.id()).thenReturn("trig-" + label);
        when(t.getNormalizedKey()).thenReturn(
            "trigger:" + label.toLowerCase().replace(" ", "_"));
        return t;
    }

    private static Step mcp(String label) {
        Step s = mock(Step.class);
        when(s.label()).thenReturn(label);
        when(s.getNormalizedKey()).thenReturn(
            "mcp:" + label.toLowerCase().replace(" ", "_"));
        return s;
    }

    private static Step table(String label) {
        Step s = mock(Step.class);
        when(s.label()).thenReturn(label);
        // The validator uses "table:" + normalizedLabel directly, bypassing getNormalizedKey
        when(s.getNormalizedKey()).thenReturn(
            "table:" + label.toLowerCase().replace(" ", "_"));
        return s;
    }

    private static Agent agent(String label) {
        Agent a = mock(Agent.class);
        when(a.label()).thenReturn(label);
        when(a.getNormalizedKey()).thenReturn(
            "agent:" + label.toLowerCase().replace(" ", "_"));
        return a;
    }

    private static Core core(String label) {
        Core c = mock(Core.class);
        when(c.label()).thenReturn(label);
        when(c.id()).thenReturn("core-" + label);
        when(c.getNormalizedKey()).thenReturn(
            "core:" + label.toLowerCase().replace(" ", "_"));
        return c;
    }

    private static InterfaceDef ifaceDef(String label) {
        InterfaceDef i = mock(InterfaceDef.class);
        when(i.label()).thenReturn(label);
        when(i.getNormalizedKey()).thenReturn(
            "interface:" + label.toLowerCase().replace(" ", "_"));
        return i;
    }

    private static WorkflowPlan plan(List<Trigger> triggers,
                                     List<Step> mcps,
                                     List<Agent> agents,
                                     List<Core> cores,
                                     List<Step> tables,
                                     List<InterfaceDef> interfaces) {
        WorkflowPlan p = mock(WorkflowPlan.class);
        when(p.getTriggers()).thenReturn(triggers);
        when(p.getMcps()).thenReturn(mcps);
        when(p.getAgents()).thenReturn(agents);
        when(p.getCores()).thenReturn(cores);
        when(p.getTables()).thenReturn(tables);
        when(p.getInterfaces()).thenReturn(interfaces);
        return p;
    }

    @Nested
    @DisplayName("strict mode")
    class StrictMode {

        private final PlanAliasValidator strictValidator = new PlanAliasValidator(true);

        @Test
        @DisplayName("Passes when every alias is unique across node types")
        void passesWhenAllAliasesUnique() {
            WorkflowPlan p = plan(
                List.of(trigger("Inbound Email")),
                List.of(mcp("Send Slack"), mcp("Save To DB")),
                List.of(agent("Classify Intent")),
                List.of(core("Route Decision")),
                List.of(),
                List.of()
            );

            assertThatCode(() -> strictValidator.validate(p)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Throws when trigger and mcp share the same normalized alias")
        void throwsOnTriggerMcpCollision() {
            WorkflowPlan p = plan(
                List.of(trigger("Read Email")),
                List.of(mcp("read email")),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );

            assertThatThrownBy(() -> strictValidator.validate(p))
                .isInstanceOf(PlanAliasValidationException.class)
                .hasMessageContaining("read_email")
                .hasMessageContaining("trigger:read_email")
                .hasMessageContaining("mcp:read_email");
        }

        @Test
        @DisplayName("Throws when mcp and table share alias")
        void throwsOnMcpTableCollision() {
            WorkflowPlan p = plan(
                List.of(trigger("Start")),
                List.of(mcp("Users")),
                List.of(),
                List.of(),
                List.of(table("Users")),
                List.of()
            );

            assertThatThrownBy(() -> strictValidator.validate(p))
                .isInstanceOf(PlanAliasValidationException.class)
                .hasMessageContaining("users");
        }

        @Test
        @DisplayName("Throws and lists all collisions when several aliases collide")
        void throwsWithAllCollisions() {
            WorkflowPlan p = plan(
                List.of(trigger("Read Email")),
                List.of(mcp("Read Email"), mcp("Send Slack")),
                List.of(),
                List.of(core("send slack")),
                List.of(),
                List.of()
            );

            assertThatThrownBy(() -> strictValidator.validate(p))
                .isInstanceOf(PlanAliasValidationException.class)
                .satisfies(ex -> {
                    PlanAliasValidationException paex = (PlanAliasValidationException) ex;
                    assertThat(paex.getCollisions()).hasSize(2);
                    assertThat(paex.getCollisions().keySet()).contains("read_email", "send_slack");
                });
        }

        @Test
        @DisplayName("Duplicate label within same node type yields one full-key (no false positive)")
        void duplicateInSameTypeIsTolerated() {
            // Both mcps produce the same full-key 'mcp:send_slack'. The validator dedups
            // full-keys, so this is a single entry, not a collision.
            WorkflowPlan p = plan(
                List.of(),
                List.of(mcp("Send Slack"), mcp("Send Slack")),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );

            assertThatCode(() -> strictValidator.validate(p)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Catches accent-insensitive collisions (Réception vs Reception)")
        void catchesAccentNormalizedCollision() {
            WorkflowPlan p = plan(
                List.of(),
                List.of(mcp("Reception")),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
            // Inject another mcp that normalizes to the same slug as the first via the
            // LabelNormalizer accent stripping.
            Step accented = mock(Step.class);
            when(accented.label()).thenReturn("Réception");
            when(accented.getNormalizedKey()).thenReturn("mcp:reception_v2"); // distinct full-key
            // Build a plan with both
            WorkflowPlan p2 = plan(
                List.of(),
                List.of(mcp("Reception"), accented),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );

            // Both labels normalize to "reception" but their full-keys differ → collision.
            assertThatThrownBy(() -> strictValidator.validate(p2))
                .isInstanceOf(PlanAliasValidationException.class)
                .hasMessageContaining("reception");
        }

        @Test
        @DisplayName("Null plan is tolerated (no NPE)")
        void nullPlanDoesNotThrow() {
            assertThatCode(() -> strictValidator.validate(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Plan with all-null sub-lists is tolerated")
        void emptyPlanDoesNotThrow() {
            WorkflowPlan p = mock(WorkflowPlan.class);
            when(p.getTriggers()).thenReturn(null);
            when(p.getMcps()).thenReturn(null);
            when(p.getAgents()).thenReturn(null);
            when(p.getCores()).thenReturn(null);
            when(p.getTables()).thenReturn(null);
            when(p.getInterfaces()).thenReturn(null);

            assertThatCode(() -> strictValidator.validate(p)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("lenient mode (default)")
    class LenientMode {

        private final PlanAliasValidator lenientValidator = new PlanAliasValidator(false);

        @Test
        @DisplayName("Does not throw on collision (warn-only)")
        void warnsButDoesNotThrowOnCollision() {
            WorkflowPlan p = plan(
                List.of(trigger("Read Email")),
                List.of(mcp("Read Email")),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );

            // Lenient mode must NEVER throw - keeps the run alive while we audit prod logs.
            assertThatCode(() -> lenientValidator.validate(p)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Passes silently when no collision (debug log only)")
        void passesSilentlyWhenClean() {
            WorkflowPlan p = plan(
                List.of(trigger("Inbound")),
                List.of(mcp("Send Slack")),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );

            assertThatCode(() -> lenientValidator.validate(p)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("interfaces")
    class Interfaces {

        @Test
        @DisplayName("Detects collision between interface and mcp")
        void detectsInterfaceMcpCollision() {
            PlanAliasValidator strict = new PlanAliasValidator(true);
            WorkflowPlan p = plan(
                List.of(),
                List.of(mcp("Confirm")),
                List.of(),
                List.of(),
                List.of(),
                List.of(ifaceDef("Confirm"))
            );

            assertThatThrownBy(() -> strict.validate(p))
                .isInstanceOf(PlanAliasValidationException.class)
                .hasMessageContaining("confirm");
        }
    }
}
