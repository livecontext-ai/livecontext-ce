package com.apimarketplace.orchestrator.services.streaming.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StatePrePopulator")
class StatePrePopulatorTest {

    @Mock
    private NodeEventStore nodeEventStore;

    @Mock
    private RunStateStoreAccessor stateAccessor;

    private StatePrePopulator prePopulator;

    @BeforeEach
    void setUp() {
        prePopulator = new StatePrePopulator(nodeEventStore, stateAccessor);
    }

    @Nested
    @DisplayName("prePopulateNodeCounts()")
    class PrePopulateNodeCountsTests {

        @Test
        @DisplayName("Should delegate to nodeEventStore")
        void shouldDelegateToNodeEventStore() {
            Map<String, Integer> counts = Map.of("SUCCESS", 15, "FAILED", 2);

            prePopulator.prePopulateNodeCounts("run-1", "mcp:step1", counts);

            verify(nodeEventStore).prePopulateCounts("run-1", "mcp:step1", counts);
        }

        @Test
        @DisplayName("Should skip when runId is null")
        void shouldSkipWhenRunIdIsNull() {
            prePopulator.prePopulateNodeCounts(null, "mcp:step1", Map.of("SUCCESS", 1));
            verifyNoInteractions(nodeEventStore);
        }

        @Test
        @DisplayName("Should skip when nodeId is null")
        void shouldSkipWhenNodeIdIsNull() {
            prePopulator.prePopulateNodeCounts("run-1", null, Map.of("SUCCESS", 1));
            verifyNoInteractions(nodeEventStore);
        }

        @Test
        @DisplayName("Should skip when statusCounts is null")
        void shouldSkipWhenStatusCountsIsNull() {
            prePopulator.prePopulateNodeCounts("run-1", "mcp:step1", null);
            verifyNoInteractions(nodeEventStore);
        }

        @Test
        @DisplayName("Should skip when statusCounts is empty")
        void shouldSkipWhenStatusCountsIsEmpty() {
            prePopulator.prePopulateNodeCounts("run-1", "mcp:step1", Map.of());
            verifyNoInteractions(nodeEventStore);
        }
    }
}
