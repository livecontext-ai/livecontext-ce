package com.apimarketplace.trigger.archunit;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trigger-service mirror of orchestrator's
 * {@code OrgScopePredicateInvariantTest}. Same rule logic, different
 * classpath scan. See the reference implementation for the contract +
 * design rationale.
 */
@DisplayName("ScopeGuard callsite invariants (trigger-service mirror)")
class OrgScopePredicateInvariantTest {

    private static final List<String> RULE_1_ALLOWLIST = List.of(
            // Stamping / DTO mappers - copy entity scope fields into a DTO,
            // not a scope predicate.
            "InternalTriggerController#toScheduleDto",
            "InternalTriggerController#toStandaloneDto",
            "StandaloneChatEndpointService#toDto",
            "StandaloneScheduleService#toDto",
            "StandaloneWebhookService#toDto",
            // V253 - ensureTokenForTrigger stamps tenant+org on create and
            // logs both fields after. Not a scope predicate; the actual
            // predicate lives in deleteTokensForWorkflowScoped (ScopeGuard).
            "WebhookTokenService#ensureTokenForTrigger",
            // Stamping / pure copy: copies the request's tenant + org onto the
            // subscription row (owner scope for later event dispatch), no
            // owner-vs-org comparison. Same false-positive category as the
            // orchestrator-side twin DatasourceSubscriptionSyncService#syncFromPlan
            // (allow-listed in 646d20fd8).
            "DatasourceTriggerSubscriptionService#upsert"
    );

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.apimarketplace.trigger");

    @Test
    @DisplayName("Rule 1 - methods touching both scope getters must call ScopeGuard.*")
    void rule1NoHandRolledScopePredicate() {
        List<String> offenders = classes.stream()
                .filter(this::isCandidateClass)
                .flatMap(c -> c.getMethods().stream())
                .filter(this::touchesBothScopeGetters)
                .filter(m -> !callsScopeGuard(m))
                .filter(m -> !RULE_1_ALLOWLIST.contains(describe(m)))
                .map(this::describe)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("trigger-service: hand-rolled scope predicate detected. "
                        + "Route through ScopeGuard.isInStrictScope or, for documented "
                        + "internal channels, ScopeGuard.isInOwnerOrOrgScope + @TolerantScope.")
                .isEmpty();
    }

    @Test
    @DisplayName("Rule 2 - every isInOwnerOrOrgScope call site is @TolerantScope-annotated")
    void rule2TolerantCallSitesMustBeAnnotated() {
        List<String> offenders = classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(this::callsTolerantHelper)
                .filter(m -> !hasTolerantScopeAnnotation(m))
                .map(this::describe)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("trigger-service: isInOwnerOrOrgScope without @TolerantScope.")
                .isEmpty();
    }

    private boolean isCandidateClass(JavaClass c) {
        String pkg = c.getPackageName();
        return pkg.contains(".controller")
                || pkg.contains(".service");
    }

    private boolean touchesBothScopeGetters(JavaMethod m) {
        boolean hasTenantGetter = m.getMethodCallsFromSelf().stream().anyMatch(call -> {
            String name = call.getTarget().getName();
            return name.equals("getTenantId") || name.equals("getUserId");
        });
        boolean hasOrgGetter = m.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTarget().getName().equals("getOrganizationId"));
        return hasTenantGetter && hasOrgGetter;
    }

    private boolean callsScopeGuard(JavaMethod m) {
        return m.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTarget().getOwner().getFullName().equals(ScopeGuard.class.getName()));
    }

    private boolean callsTolerantHelper(JavaMethod m) {
        return m.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTarget().getOwner().getFullName().equals(ScopeGuard.class.getName())
                        && call.getTarget().getName().equals("isInOwnerOrOrgScope"));
    }

    private boolean hasTolerantScopeAnnotation(JavaMethod m) {
        return m.isAnnotatedWith(TolerantScope.class)
                || m.getOwner().isAnnotatedWith(TolerantScope.class);
    }

    private String describe(JavaMethod m) {
        return m.getOwner().getSimpleName() + "#" + m.getName();
    }
}
