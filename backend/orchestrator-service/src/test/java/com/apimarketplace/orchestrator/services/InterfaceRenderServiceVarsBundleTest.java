package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.WorkflowVariableBundleCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link InterfaceRenderService#resolveVarsBundle(String, String)},
 * the helper that supplies the per-run {@code {{$vars.*}}} bundle to the interface
 * render path (Niveau 1: variable_mapping resolution at render time).
 *
 * <p>Two branches matter:
 * <ul>
 *   <li>the cache is wired -> the bundle is fetched with the run OWNER's tenant + the
 *       run's org (read off the run row) so a workspace variable resolves for any viewer;</li>
 *   <li>the cache field is unset (plain unit context / feature-off) -> the helper degrades
 *       to {@code Map.of()} with no NPE, matching the execution engine's graceful degradation.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceRenderService.resolveVarsBundle")
class InterfaceRenderServiceVarsBundleTest {

    private static final String RUN_ID = "run_vars_001";
    private static final String OWNER_TENANT = "tenant-owner";
    private static final String ORG_ID = "org-abc";

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private WorkflowVariableBundleCache workflowVariableBundleCache;

    @InjectMocks
    private InterfaceRenderService service;

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeResolveVarsBundle(String runId, String ownerTenantId) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(
            service, "resolveVarsBundle", runId, ownerTenantId);
    }

    @Test
    @DisplayName("Returns the cache bundle fetched with the run owner's tenant and the run's org")
    void returnsCacheBundleForRun() {
        // @InjectMocks does not populate the @Autowired(required=false) cache field by type
        // reliably across Mockito versions, so wire it explicitly.
        ReflectionTestUtils.setField(service, "workflowVariableBundleCache", workflowVariableBundleCache);

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setOrganizationId(ORG_ID);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        Map<String, Object> bundle = Map.of("api_base_url", "https://vars.example.com");
        when(workflowVariableBundleCache.getBundle(RUN_ID, OWNER_TENANT, ORG_ID)).thenReturn(bundle);

        Map<String, Object> result = invokeResolveVarsBundle(RUN_ID, OWNER_TENANT);

        assertThat(result).isEqualTo(bundle);
    }

    @Test
    @DisplayName("Degrades to an empty map (no NPE) when the bundle cache is not wired")
    void nullCacheDegradesToEmptyMap() {
        // Cache field left unset (null) - the feature-off / plain-unit context.
        Map<String, Object> result = invokeResolveVarsBundle(RUN_ID, OWNER_TENANT);

        assertThat(result).isEmpty();
    }
}
