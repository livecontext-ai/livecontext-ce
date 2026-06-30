package com.apimarketplace.agent.gemini.cache;

import com.apimarketplace.agent.domain.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 2.4 - R55 / R6 security invariant. Reflectively enforces that
 * the {@link GeminiCachedContentKey#compute} entry point only accepts
 * the fields allowed to reach the cache hash: the <em>static</em> system
 * block text and the tool list.
 *
 * <p><b>Why a reflection test, not prose.</b> A reviewer checklist
 * ("please don't add tenant-id to the hash") is a lagging control - the
 * PR might ship before anyone reads the checklist. This test is a
 * leading control: it fails at build time if a future refactor adds a
 * new parameter, forcing the author either to narrow the parameter
 * back out or to edit this test (and trigger a security review in the
 * diff).
 *
 * <p><b>What goes wrong without it.</b> Adding {@code tenantId} to the
 * hash multiplies Gemini cache storage fees by the number of tenants
 * and produces zero additional token savings (the static prefix is the
 * same bytes for every tenant). Worse, it silently leaks tenant
 * isolation into a shared cache if the prefix doesn't ALSO include
 * tenant-scoped data - we'd be keying a shared secret on a tenant
 * identifier.
 *
 * <p>The allow-list is expressed as an inline {@link Set} at the top of
 * this test; to intentionally widen it, a new entry must be added AND
 * reviewed together with the production change.
 */
@DisplayName("GeminiCachedContentKey - allow-list invariant (Stage 2.4 / R55)")
class GeminiCacheKeyAllowedFieldsTest {

    /**
     * Parameter <em>types</em> (as fully-qualified strings) that
     * {@link GeminiCachedContentKey#compute} is allowed to accept.
     * Strings are used so a dependency swap (say, {@code ToolDefinition}
     * → {@code SlimToolDefinition}) surfaces as a test failure rather
     * than a silent signature change.
     */
    private static final Set<String> ALLOWED_COMPUTE_PARAM_TYPES = Set.of(
            "java.lang.String",
            "java.util.List<" + ToolDefinition.class.getName() + ">"
    );

    /**
     * Simple names of the declared {@code public} methods on the class.
     * Any additional public entry point would be a wider surface that
     * the reflection test can't see; pin the list.
     */
    private static final Set<String> ALLOWED_PUBLIC_METHOD_NAMES = Set.of("compute");

    @Test
    @DisplayName("compute() accepts exactly (String, List<ToolDefinition>) - no tenant-scoped types")
    void computeSignatureIsRestrictedToAllowList() {
        Method compute = findSingleComputeMethod();

        List<String> paramTypes = Arrays.stream(compute.getGenericParameterTypes())
                .map(java.lang.reflect.Type::getTypeName)
                .collect(Collectors.toList());

        assertThat(paramTypes)
                .as("compute() parameter types must exactly match the allow-list. "
                        + "Adding a new parameter (e.g. tenantId) requires a deliberate edit to this test.")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_COMPUTE_PARAM_TYPES);
    }

    @Test
    @DisplayName("GeminiCachedContentKey exposes exactly the `compute` entry point - no alternative paths")
    void publicApiIsASingleEntryPoint() {
        // A helper like `computeWithTenantHint(tenantId, ...)` would
        // ride next to compute() and pass the reviewer because
        // compute()'s own signature is clean. Enforce that ONLY
        // compute() is public; anything else must be private helpers
        // that compute() owns.
        Set<String> publicMethods = Arrays.stream(GeminiCachedContentKey.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(publicMethods)
                .as("any additional public method would bypass the compute() contract")
                .isEqualTo(ALLOWED_PUBLIC_METHOD_NAMES);
    }

    @Test
    @DisplayName("class is final - a subclass could override and leak tenant-scoped fields into the hash")
    void classIsFinalToPreventSubclassingWithLeakedFields() {
        // If someone extended GeminiCachedContentKey to add a
        // @Override compute() that concatenates a tenantId, the
        // allow-list signature check above wouldn't catch it.
        // Enforce at class level.
        assertThat(Modifier.isFinal(GeminiCachedContentKey.class.getModifiers()))
                .as("GeminiCachedContentKey must be final - no subclassed hash variants")
                .isTrue();
    }

    private static Method findSingleComputeMethod() {
        Method[] found = Arrays.stream(GeminiCachedContentKey.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("compute"))
                .toArray(Method[]::new);
        assertThat(found)
                .as("exactly one compute() overload must exist - overloads enable silent field leaks")
                .hasSize(1);
        return found[0];
    }
}
