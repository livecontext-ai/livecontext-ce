package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that SignalResumeService.extractSpawnFromRun() correctly reads
 * the spawn value from run metadata instead of hardcoding 0.
 *
 * The private method is tested via reflection since the fix is specifically
 * about the correctness of spawn extraction from run metadata.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalResumeService spawn extraction")
class SignalResumeServiceSpawnTest {

    @Mock private StringRedisTemplate mockRedis;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private StorageService storageService;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private ExecutionCacheManager executionCacheManager;
    @Mock private NodeSearchService nodeSearchService;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private WorkflowEpochService workflowEpochService;

    private SignalResumeService service;
    private Method extractSpawnMethod;

    @BeforeEach
    void setUp() throws Exception {
        // SplitContextManager is a concrete class - use mock
        com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager splitContextManager =
                mock(com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager.class);

        service = new SignalResumeService(
                mockRedis,
                runRepository,
                splitContextManager,
                storageService,
                stateSnapshotService,
                stepDataRepository,
                executionCacheManager,
                nodeSearchService,
                runningNodeTracker,
                workflowEpochService
        );

        // Get the private method via reflection
        extractSpawnMethod = SignalResumeService.class.getDeclaredMethod(
                "extractSpawnFromRun", WorkflowRunEntity.class);
        extractSpawnMethod.setAccessible(true);
    }

    /**
     * When metadata has lastRerunSpawn=3, extractSpawnFromRun should return 3.
     */
    @Test
    @DisplayName("extractSpawnFromRun with metadata returns correct spawn value")
    void extractSpawnFromRun_withMetadata_returnsSpawn() throws Exception {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("lastRerunSpawn", 3);
        when(run.getMetadata()).thenReturn(metadata);

        int result = (int) extractSpawnMethod.invoke(service, run);

        assertEquals(3, result, "Should return spawn value from metadata");
    }

    /**
     * When metadata is null, extractSpawnFromRun should return 0.
     */
    @Test
    @DisplayName("extractSpawnFromRun with null metadata returns 0")
    void extractSpawnFromRun_withNullMetadata_returns0() throws Exception {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getMetadata()).thenReturn(null);

        int result = (int) extractSpawnMethod.invoke(service, run);

        assertEquals(0, result, "Should return 0 when metadata is null");
    }

    /**
     * When metadata exists but does not contain lastRerunSpawn key, returns 0.
     */
    @Test
    @DisplayName("extractSpawnFromRun with missing key returns 0")
    void extractSpawnFromRun_withMissingKey_returns0() throws Exception {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("someOtherKey", "value");
        when(run.getMetadata()).thenReturn(metadata);

        int result = (int) extractSpawnMethod.invoke(service, run);

        assertEquals(0, result, "Should return 0 when lastRerunSpawn key is missing");
    }

    /**
     * When lastRerunSpawn is a Long (possible from JSON deserialization), should still work.
     */
    @Test
    @DisplayName("extractSpawnFromRun with Long value returns correct spawn")
    void extractSpawnFromRun_withLongValue_returnsSpawn() throws Exception {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("lastRerunSpawn", 7L); // Long instead of Integer
        when(run.getMetadata()).thenReturn(metadata);

        int result = (int) extractSpawnMethod.invoke(service, run);

        assertEquals(7, result, "Should handle Long values correctly via Number.intValue()");
    }

    /**
     * When lastRerunSpawn is a String (edge case), should return 0 since it's not a Number.
     */
    @Test
    @DisplayName("extractSpawnFromRun with String value returns 0")
    void extractSpawnFromRun_withStringValue_returns0() throws Exception {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("lastRerunSpawn", "notANumber");
        when(run.getMetadata()).thenReturn(metadata);

        int result = (int) extractSpawnMethod.invoke(service, run);

        assertEquals(0, result, "Should return 0 when lastRerunSpawn is not a Number");
    }

    /**
     * When metadata is empty map, should return 0.
     */
    @Test
    @DisplayName("extractSpawnFromRun with empty metadata returns 0")
    void extractSpawnFromRun_withEmptyMetadata_returns0() throws Exception {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getMetadata()).thenReturn(new HashMap<>());

        int result = (int) extractSpawnMethod.invoke(service, run);

        assertEquals(0, result, "Should return 0 when metadata is empty");
    }
}
