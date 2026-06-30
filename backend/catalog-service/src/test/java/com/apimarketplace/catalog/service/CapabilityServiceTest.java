package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.SearchConfig;
import com.apimarketplace.catalog.domain.ToolSignalEntity;
import com.apimarketplace.catalog.dto.CapabilityRequest;
import com.apimarketplace.catalog.dto.CapabilityResponse;
import com.apimarketplace.catalog.repository.LexicalSearchIndexRepository;
import com.apimarketplace.catalog.repository.ToolSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CapabilityService")
class CapabilityServiceTest {

    @Mock
    private LexicalSearchService lexicalSearchService;
    @Mock
    private ToolSignalRepository toolSignalRepository;
    @Mock
    private LexicalSearchIndexRepository lexicalSearchIndexRepository;
    @Mock
    private SearchConfig searchConfig;
    @Mock
    private QueryUnderstandingService queryUnderstandingService;
    @Mock
    private RerankingService rerankingService;
    @Mock
    private SearchFeedbackService searchFeedbackService;

    private CapabilityService capabilityService;

    @BeforeEach
    void setUp() {
        SearchConfig.QueryUnderstandingConfig queryConfig = new SearchConfig.QueryUnderstandingConfig();
        queryConfig.setEnabled(false);
        when(searchConfig.getQueryUnderstanding()).thenReturn(queryConfig);

        SearchConfig.RerankingConfig rerankConfig = new SearchConfig.RerankingConfig();
        rerankConfig.setEnabled(false);
        when(searchConfig.getReranking()).thenReturn(rerankConfig);

        SearchConfig.AutoPickConfig autoPickConfig = new SearchConfig.AutoPickConfig();
        lenient().when(searchConfig.getAutoPick()).thenReturn(autoPickConfig);

        capabilityService = new CapabilityService(
                lexicalSearchService,
                toolSignalRepository,
                lexicalSearchIndexRepository,
                searchConfig,
                queryUnderstandingService,
                rerankingService,
                searchFeedbackService
        );
    }

    @Nested
    @DisplayName("rank - Success Cases")
    class RankSuccessCases {

        @Test
        @DisplayName("should return capability cards from lexical search")
        void returnsCardsOnSuccess() {
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();

            CapabilityRequest request = new CapabilityRequest("get user data", 10, null, true);

            when(lexicalSearchService.searchForFusion(eq(request.q()), eq(100), any()))
                    .thenReturn(List.of(
                            Map.of(toolId1, 0.9),
                            Map.of(toolId2, 0.7)
                    ));

            ToolSignalEntity signal1 = createSignal(toolId1, "instagram", "user", "get", false);
            ToolSignalEntity signal2 = createSignal(toolId2, "facebook", "profile", "read", true);
            when(toolSignalRepository.findByToolIdIn(anyCollection()))
                    .thenReturn(List.of(signal1, signal2));
            when(lexicalSearchIndexRepository.batchGetByToolIds(anyCollection()))
                    .thenReturn(Collections.emptyMap());
            when(lexicalSearchIndexRepository.batchGetToolNames(anyCollection()))
                    .thenReturn(Map.of(
                            toolId1.toString(), "Test Tool 1",
                            toolId2.toString(), "Test Tool 2"
                    ));

            CapabilityResponse response = capabilityService.rank(request);

            assertThat(response.cards()).hasSize(2);
            assertThat(response.meta()).containsEntry("query", "get user data");
            assertThat(response.meta()).containsKey("processing_time_ms");
        }

        @Test
        @DisplayName("should use default K when not provided")
        void usesDefaultK() {
            CapabilityRequest request = new CapabilityRequest("query", null, null, true);

            when(lexicalSearchService.searchForFusion(anyString(), anyInt(), any()))
                    .thenReturn(Collections.emptyList());

            CapabilityResponse response = capabilityService.rank(request);

            assertThat(response.meta()).containsEntry("k", 12);
        }
    }

    @Nested
    @DisplayName("rank - Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should return empty results when search fails")
        void returnsEmptyWhenSearchFails() {
            CapabilityRequest request = new CapabilityRequest("query", 5, null, true);

            when(lexicalSearchService.searchForFusion(anyString(), anyInt(), any()))
                    .thenThrow(new RuntimeException("Search error"));

            CapabilityResponse response = capabilityService.rank(request);

            assertThat(response.cards()).isEmpty();
            assertThat(response.meta()).containsEntry("total_results", 0);
        }
    }

    @Nested
    @DisplayName("rank - Signal Fallback")
    class SignalFallbackTests {

        @Test
        @DisplayName("should use lexical index when signal not found")
        void usesLexicalIndexFallback() {
            UUID toolId = UUID.randomUUID();
            CapabilityRequest request = new CapabilityRequest("query", 5, null, true);

            when(lexicalSearchService.searchForFusion(anyString(), anyInt(), any()))
                    .thenReturn(List.of(Map.of(toolId, 0.8)));

            when(toolSignalRepository.findByToolIdIn(anyCollection()))
                    .thenReturn(Collections.emptyList());

            Map<String, Object> lexicalData = Map.of(
                    "provider", "fallback_provider",
                    "resource", "fallback_resource",
                    "action", "fallback_action"
            );
            when(lexicalSearchIndexRepository.batchGetByToolIds(anyCollection()))
                    .thenReturn(Map.of(toolId.toString(), lexicalData));
            when(lexicalSearchIndexRepository.batchGetToolNames(anyCollection()))
                    .thenReturn(Map.of(toolId.toString(), "Fallback Tool"));

            CapabilityResponse response = capabilityService.rank(request);

            assertThat(response.cards()).hasSize(1);
            assertThat(response.cards().get(0).prov()).isEqualTo("fallback_provider");
        }
    }

    private ToolSignalEntity createSignal(UUID toolId, String provider, String resource, String action, boolean requiresCreds) {
        ToolSignalEntity signal = new ToolSignalEntity();
        signal.setToolId(toolId);
        signal.setProvider(provider);
        signal.setResource(resource);
        signal.setAction(action);
        signal.setRequiresUserCredentials(requiresCreds);
        return signal;
    }
}
