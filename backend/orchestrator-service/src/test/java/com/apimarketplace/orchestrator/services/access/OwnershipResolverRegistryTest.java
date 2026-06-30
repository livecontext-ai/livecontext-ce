package com.apimarketplace.orchestrator.services.access;

import com.apimarketplace.auth.client.access.ResourceOwnershipResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OwnershipResolverRegistry")
class OwnershipResolverRegistryTest {

    private static ResourceOwnershipResolver resolverFor(String type, String fixedOrgId) {
        return new ResourceOwnershipResolver() {
            @Override public Set<String> handledTypes() { return Set.of(type); }
            @Override public Optional<String> resolveOwnerOrgId(String resourceType, String resourceId) {
                if (!type.equals(resourceType)) return Optional.empty();
                return resourceId == null ? Optional.empty() : Optional.of(fixedOrgId);
            }
        };
    }

    @Test
    @DisplayName("Dispatches to the matching resolver by type")
    void dispatchesByType() {
        OwnershipResolverRegistry registry = new OwnershipResolverRegistry(List.of(
                resolverFor("workflow", "org-w"),
                resolverFor("agent", "org-a")
        ));

        assertThat(registry.resolveOwnerOrgId("workflow", "id-1")).contains("org-w");
        assertThat(registry.resolveOwnerOrgId("agent", "id-2")).contains("org-a");
    }

    @Test
    @DisplayName("Returns empty for unknown type")
    void unknownTypeReturnsEmpty() {
        OwnershipResolverRegistry registry = new OwnershipResolverRegistry(List.of(
                resolverFor("workflow", "org-w")
        ));

        assertThat(registry.resolveOwnerOrgId("datasource", "id-x")).isEmpty();
    }

    @Test
    @DisplayName("handledTypes reflects every resolver type")
    void handledTypesReportsAll() {
        OwnershipResolverRegistry registry = new OwnershipResolverRegistry(List.of(
                resolverFor("workflow", "org-w"),
                resolverFor("agent", "org-a")
        ));

        assertThat(registry.handledTypes()).containsExactlyInAnyOrder("workflow", "agent");
    }

    @Test
    @DisplayName("Empty resolver list yields empty registry")
    void emptyResolverList() {
        OwnershipResolverRegistry registry = new OwnershipResolverRegistry(List.of());

        assertThat(registry.handledTypes()).isEmpty();
        assertThat(registry.resolveOwnerOrgId("workflow", "id")).isEmpty();
    }

    @Test
    @DisplayName("Two resolvers claiming the same type throw at construction")
    void duplicateTypeFailsFast() {
        ResourceOwnershipResolver a = resolverFor("workflow", "org-1");
        ResourceOwnershipResolver b = resolverFor("workflow", "org-2");

        assertThatThrownBy(() -> new OwnershipResolverRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ResourceOwnershipResolver for type 'workflow'");
    }
}
