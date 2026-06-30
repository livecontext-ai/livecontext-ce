package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.resume.state.StateReconstructor;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Set;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowResumeService#clearCachedStateForRerun} overloads.
 *
 * <p>Regression: SBS refire path must skip {@link RunScopedCache.CacheDomain#STREAMING}
 * caches to keep the monotonic seq counter alive across fires (2026-05-05 audit:
 * fire #2 UI freeze caused by deferred fire #N publish colliding with fire #N+1 seqs).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowResumeService - clearCachedStateForRerun")
class WorkflowResumeServiceClearCacheTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private RunStateStore runStateStore;
    @Mock private WorkflowCacheManager cacheManager;
    @Mock private StateReconstructor stateReconstructor;
    @Mock private RunCacheRegistry cacheRegistry;
    @Mock private ExecutionContextManager contextManager;
    @Mock private StepByStepExecutor stepByStepExecutor;
    @Mock private TriggerEpochManager epochManager;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private UnifiedSignalService unifiedSignalService;

    private WorkflowResumeService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkflowResumeService(
                runRepository, executionService, persistenceService,
                streamingService, runStateStore,
                cacheManager, stateReconstructor, cacheRegistry,
                contextManager, stepByStepExecutor,
                epochManager, stateSnapshotService
        );
        Field signalField = WorkflowResumeService.class.getDeclaredField("unifiedSignalService");
        signalField.setAccessible(true);
        signalField.set(service, unifiedSignalService);
    }

    @Test
    @DisplayName("clearCachedStateForRerun(runId) delegates to registry with empty exclude set (full purge)")
    void noArgOverloadPurgesAllDomains() {
        service.clearCachedStateForRerun("run-1");

        verify(cacheRegistry).cleanupRun("run-1", Set.of());
    }

    @Test
    @DisplayName("clearCachedStateForRerun(runId, excludeDomains) propagates exclude set to registry")
    void excludeOverloadPropagatesDomains() {
        Set<RunScopedCache.CacheDomain> exclude = Set.of(RunScopedCache.CacheDomain.STREAMING);

        service.clearCachedStateForRerun("run-1", exclude);

        verify(cacheRegistry).cleanupRun("run-1", exclude);
    }

    @Test
    @DisplayName("SBS refire regression: STREAMING domain is excluded so seq counter survives")
    void sbsRefireExcludesStreamingDomain() {
        // This is the call the ReusableTriggerService:606 makes on every SBS refire.
        // If STREAMING is NOT in the exclude set, WsEventSequencer.counters[runId] gets
        // purged mid-run → fire #N+1 reseeds from DB → race with deferred fire #N
        // publishes → duplicate seqs → frontend strict-< drops events → UI freezes.
        Set<RunScopedCache.CacheDomain> sbsRefireExclude = Set.of(RunScopedCache.CacheDomain.STREAMING);

        service.clearCachedStateForRerun("run-1", sbsRefireExclude);

        verify(cacheRegistry).cleanupRun(eq("run-1"),
                argThat(s -> s.contains(RunScopedCache.CacheDomain.STREAMING)));
    }
}
