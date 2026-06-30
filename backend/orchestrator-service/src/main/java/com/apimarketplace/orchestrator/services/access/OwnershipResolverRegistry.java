package com.apimarketplace.orchestrator.services.access;

import com.apimarketplace.auth.client.access.ResourceOwnershipResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates every {@link ResourceOwnershipResolver} bean discovered by Spring
 * and dispatches a lookup to the first resolver that handles the given type.
 *
 * <p>A type is registered at most once. Duplicates fail loudly at bootstrap
 * - two services claiming the same type is a wiring bug.
 */
@Service
public class OwnershipResolverRegistry {

    private static final Logger log = LoggerFactory.getLogger(OwnershipResolverRegistry.class);

    private final Map<String, ResourceOwnershipResolver> resolversByType = new HashMap<>();

    public OwnershipResolverRegistry(List<ResourceOwnershipResolver> resolvers) {
        for (ResourceOwnershipResolver resolver : resolvers) {
            for (String type : resolver.handledTypes()) {
                ResourceOwnershipResolver existing = resolversByType.putIfAbsent(type, resolver);
                if (existing != null && existing != resolver) {
                    throw new IllegalStateException(
                            "Duplicate ResourceOwnershipResolver for type '" + type + "': "
                                    + existing.getClass().getName() + " and "
                                    + resolver.getClass().getName());
                }
            }
        }
        log.info("OwnershipResolverRegistry: {} type(s) registered: {}",
                resolversByType.size(), resolversByType.keySet());
    }

    /**
     * Resolve the owning org of a given resource. Returns empty when the type
     * is unknown to this service or the resource is not found.
     */
    public Optional<String> resolveOwnerOrgId(String resourceType, String resourceId) {
        ResourceOwnershipResolver resolver = resolversByType.get(resourceType);
        if (resolver == null) {
            return Optional.empty();
        }
        return resolver.resolveOwnerOrgId(resourceType, resourceId);
    }

    /** Set of resource types this orchestrator instance can resolve. */
    public java.util.Set<String> handledTypes() {
        return java.util.Collections.unmodifiableSet(resolversByType.keySet());
    }
}
