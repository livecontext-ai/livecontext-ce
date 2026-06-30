package com.apimarketplace.orchestrator.archunit;

import com.apimarketplace.orchestrator.services.state.patch.AdvisoryLockHolding;
import com.apimarketplace.orchestrator.services.state.patch.JsonbPatch;
import com.apimarketplace.orchestrator.services.state.patch.PatchClass;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Plan v4 §1.4 + §1.6 - 3 ArchUnit rules enforcing the patch-builder + advisory-lock
 * architectural contracts. The 4th planned rule (try-finally enforcement around
 * openCoalescing/closeCoalescing) is harder to express in ArchUnit cleanly - left
 * as a code-review checklist item plus the 10-min idle reaper as the runtime
 * backstop for unbalanced opens.
 *
 * <p>Imports skip test classes (which legitimately construct {@code JsonbPatch}
 * directly in unit tests) via {@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS}.
 */
@DisplayName("Plan v4 §1.4 + §1.6 - Patch-builder + advisory-lock ArchUnit invariants")
class PatchClassArchUnitRules {

    private static final String PATCH_PACKAGE =
            "com.apimarketplace.orchestrator.services.state.patch";

    /** Production-class FQNs that builders MUST NOT depend on. */
    private static final Set<String> BANNED_BUILDER_DEPS = Set.of(
            "org.springframework.web.client.RestTemplate",
            "org.springframework.web.reactive.function.client.WebClient",
            "org.springframework.data.redis.core.RedisTemplate",
            "org.springframework.data.redis.core.StringRedisTemplate",
            "org.springframework.data.jpa.repository.JpaRepository",
            "com.apimarketplace.orchestrator.services.state.StateSnapshotService",
            "com.apimarketplace.orchestrator.repository.WorkflowRunRepository"
    );

    /** HTTP-client dependencies that @AdvisoryLockHolding classes MUST NOT inject. */
    private static final Set<String> BANNED_HTTP_DEPS = Set.of(
            "org.springframework.web.client.RestTemplate",
            "org.springframework.web.reactive.function.client.WebClient",
            "feign.Client"
    );

    private static JavaClasses prodClasses;

    @BeforeAll
    static void importClasses() {
        prodClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.apimarketplace.orchestrator");
    }

    @Test
    @DisplayName("Plan §1.4 - @PatchClass builders MUST NOT inject "
            + "RestTemplate / RedisTemplate / JpaRepository / StateSnapshotService / WorkflowRunRepository "
            + "(builder purity contract)")
    void builderPurity() {
        noClasses()
                .that().areAnnotatedWith(PatchClass.class)
                .should(dependOnAnyOf(BANNED_BUILDER_DEPS,
                        "plan v4 §1.4 banned builder dep"))
                .because("plan v4 §1.4: @PatchClass-annotated builders are pure functions of "
                        + "(snapshot, params). Injecting RestTemplate/RedisTemplate/JpaRepository/"
                        + "StateSnapshotService/WorkflowRunRepository breaks reproducibility under "
                        + "CAS retry - the same builder MUST produce the same patches given the "
                        + "same inputs.")
                .check(prodClasses);
    }

    @Test
    @DisplayName("Plan §1.4 - only @PatchClass-annotated classes (or in the patch package) may "
            + "instantiate JsonbPatch (factory ban)")
    void factoryBan() {
        classes()
                .that(instantiateJsonbPatch())
                .should(beAnnotatedWithPatchClassOrInPatchPackage())
                .because("plan v4 §1.4: JsonbPatch instances must originate from a builder "
                        + "registered via @PatchClass. Allowing free instantiation defeats the "
                        + "registry's collision-detection and op-kind classification, opening the "
                        + "door to silent DELTA/ASSIGN mis-merge.")
                .check(prodClasses);
    }

    @Test
    @DisplayName("Audit S5 - at least 4 production classes carry @AdvisoryLockHolding methods "
            + "(positive-coverage guard: negative tests pass trivially with empty input)")
    void advisoryLockMarkerHasNonTrivialCoverage() {
        long classCount = prodClasses.stream()
                .filter(c -> {
                    for (JavaMethod m : c.getMethods()) {
                        if (m.isAnnotatedWith(AdvisoryLockHolding.class)) {
                            return true;
                        }
                    }
                    return false;
                })
                .count();
        org.assertj.core.api.Assertions.assertThat(classCount)
                .as("plan v4 §1.6 lists ≥4 advisory-lock carve-out classes; "
                        + "if no classes carry the annotation, the no-HTTP rule above passes "
                        + "trivially and gives false safety. Verify carve-out coverage is real.")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Plan §1.6 - classes with @AdvisoryLockHolding methods MUST NOT inject "
            + "HTTP clients DIRECTLY (does not cover transitive deps via *-client wrapper beans)")
    void advisoryLockNoHttp() {
        noClasses()
                .that(haveAnyMethodAnnotatedWith(AdvisoryLockHolding.class))
                .should(dependOnAnyOf(BANNED_HTTP_DEPS, "plan v4 §1.6 banned HTTP dep"))
                .because("plan v4 §1.6: holding pg_advisory_xact_lock during a network round-trip "
                        + "extends the lock tail beyond Postgres-internal time and creates CAS "
                        + "retry-storm risk on concurrent writers (audit A H2). Drop the HTTP call "
                        + "or move it to a separate non-locked method.")
                .check(prodClasses);
    }

    // --- Helpers ---

    private static ArchCondition<JavaClass> dependOnAnyOf(Set<String> fqns, String description) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (JavaClass dep : clazz.getAllRawSuperclasses()) {
                    if (fqns.contains(dep.getFullName())) {
                        events.add(SimpleConditionEvent.violated(clazz,
                                clazz.getFullName() + " extends banned " + dep.getFullName()));
                        return;
                    }
                }
                for (JavaClass dep : clazz.getDirectDependenciesFromSelf().stream()
                        .map(d -> d.getTargetClass()).toList()) {
                    if (fqns.contains(dep.getFullName())) {
                        events.add(SimpleConditionEvent.violated(clazz,
                                clazz.getFullName() + " depends on banned " + dep.getFullName()));
                        return;
                    }
                }
            }
        };
    }

    private static DescribedPredicate<JavaClass> instantiateJsonbPatch() {
        return new DescribedPredicate<>("instantiate JsonbPatch") {
            @Override
            public boolean test(JavaClass clazz) {
                for (JavaConstructorCall call : clazz.getConstructorCallsFromSelf()) {
                    if (JsonbPatch.class.getName().equals(call.getTargetOwner().getFullName())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static ArchCondition<JavaClass> beAnnotatedWithPatchClassOrInPatchPackage() {
        return new ArchCondition<>("be annotated @PatchClass or live in the patch package") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                boolean annotated = clazz.isAnnotatedWith(PatchClass.class);
                boolean inPackage = clazz.getPackageName().equals(PATCH_PACKAGE)
                        || clazz.getPackageName().startsWith(PATCH_PACKAGE + ".");
                if (!annotated && !inPackage) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            String.format("%s instantiates JsonbPatch but is neither "
                                            + "@PatchClass-annotated nor in the patch package.",
                                    clazz.getFullName())));
                }
            }
        };
    }

    private static DescribedPredicate<JavaClass> haveAnyMethodAnnotatedWith(
            Class<? extends java.lang.annotation.Annotation> annotation) {
        return new DescribedPredicate<>(
                "have at least one method annotated with @" + annotation.getSimpleName()) {
            @Override
            public boolean test(JavaClass clazz) {
                for (JavaMethod m : clazz.getMethods()) {
                    if (m.isAnnotatedWith(annotation)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
