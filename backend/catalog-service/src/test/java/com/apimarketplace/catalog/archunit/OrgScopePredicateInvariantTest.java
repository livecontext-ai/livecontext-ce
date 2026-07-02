package com.apimarketplace.catalog.archunit;

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
 * catalog-service mirror of orchestrator's {@code OrgScopePredicateInvariantTest}.
 * Same rule logic, different classpath scan. See the reference implementation for
 * the contract + design rationale. Catalog data is mostly platform-global, but
 * CustomApiRegistrationService is org-scoped; this mirror keeps future predicates
 * funneled through ScopeGuard.
 */
@DisplayName("ScopeGuard callsite invariants (catalog-service mirror)")
class OrgScopePredicateInvariantTest {

    private static final List<String> RULE_1_ALLOWLIST = List.of(
    );

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.apimarketplace.catalog");

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
                .as("catalog-service: hand-rolled scope predicate detected.")
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
                .as("catalog-service: isInOwnerOrOrgScope without @TolerantScope.")
                .isEmpty();
    }

    private boolean isCandidateClass(JavaClass c) {
        String pkg = c.getPackageName();
        return pkg.contains(".controller")
                || pkg.contains(".service")
                || pkg.contains(".tools")
                || pkg.contains(".web")
                || pkg.contains(".mcp");
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
