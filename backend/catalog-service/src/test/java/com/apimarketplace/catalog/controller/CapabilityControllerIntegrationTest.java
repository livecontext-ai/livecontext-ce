package com.apimarketplace.catalog.controller;

import com.apimarketplace.catalog.dto.CapabilityCard;
import com.apimarketplace.catalog.dto.CapabilityRequest;
import com.apimarketplace.catalog.dto.CapabilityResponse;
import com.apimarketplace.catalog.repository.LexicalSearchIndexRepository;
import com.apimarketplace.catalog.service.CapabilityService;
import com.apimarketplace.catalog.service.LexicalIndexSyncService;
import com.apimarketplace.catalog.util.SearchScoreClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CapabilityController Integration Tests")
class CapabilityControllerIntegrationTest {

    @Mock
    private CapabilityService capabilityService;
    @Mock
    private LexicalIndexSyncService lexicalIndexSyncService;
    @Mock
    private LexicalSearchIndexRepository lexicalSearchIndexRepository;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        CapabilityController controller = new CapabilityController(
                capabilityService,
                lexicalIndexSyncService,
                lexicalSearchIndexRepository
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("GET /api/tools/search")
    class ToolsSearchTests {

        @Test
        @DisplayName("restricts search to APIs parsed from bracket query prefix")
        void restrictsSearchToApisParsedFromBracketQueryPrefix() throws Exception {
            when(lexicalSearchIndexRepository.searchOptimizedWithScoring(
                any(), any(), any(), any(), anyInt(), any()
            )).thenReturn(List.of(searchRow("Slack", "slack")));

            mockMvc.perform(get("/api/tools/search")
                    .param("q", "[gmail, slack] send message")
                    .param("k", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.search_query").value("send message"))
                .andExpect(jsonPath("$.api_filters[0]").value("gmail"))
                .andExpect(jsonPath("$.api_filters[1]").value("slack"))
                .andExpect(jsonPath("$.api_scope_source").value("query"))
                .andExpect(jsonPath("$.tools[0].apiName").value("Slack"));

            verify(lexicalSearchIndexRepository).searchOptimizedWithScoring(
                eq("send message"), any(), any(), eq(List.of("gmail", "slack")), eq(1), any()
            );
        }

        @Test
        @DisplayName("uses explicit api parameter without requiring API words in the query")
        void usesExplicitApiParameterWithoutRequiringApiWordsInTheQuery() throws Exception {
            when(lexicalSearchIndexRepository.searchOptimizedWithScoring(
                any(), any(), any(), any(), anyInt(), any()
            )).thenReturn(List.of(searchRow("Gmail", "gmail")));

            mockMvc.perform(get("/api/tools/search")
                    .param("q", "list messages")
                    .param("api", "gmail")
                    .param("k", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.search_query").value("list messages"))
                .andExpect(jsonPath("$.api_filters[0]").value("gmail"))
                .andExpect(jsonPath("$.api_scope_source").value("parameter"))
                .andExpect(jsonPath("$.tools[0].apiSlug").value("gmail"));

            verify(lexicalSearchIndexRepository).searchOptimizedWithScoring(
                eq("list messages"), any(), any(), eq(List.of("gmail")), eq(1), any()
            );
        }

        @Test
        @DisplayName("keeps API scope when provider fallback runs")
        void keepsApiScopeWhenProviderFallbackRuns() throws Exception {
            when(lexicalSearchIndexRepository.getUniqueValues("provider")).thenReturn(List.of("Slack"));
            when(lexicalSearchIndexRepository.searchOptimizedWithScoring(
                any(), any(), any(), any(), anyInt(), any()
            )).thenReturn(List.of());
            when(lexicalSearchIndexRepository.searchFuzzy(
                any(), any(), any(), anyInt(), any()
            )).thenReturn(List.of());
            when(lexicalSearchIndexRepository.searchByApiFilters(
                any(), anyInt(), any()
            )).thenReturn(List.of());
            when(lexicalSearchIndexRepository.searchByProvider(
                eq("slack"), eq(List.of("missing-api")), anyInt(), any()
            )).thenReturn(List.of());

            mockMvc.perform(get("/api/tools/search")
                    .param("q", "slack send message")
                    .param("api", "missing-api")
                    .param("k", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.api_filters[0]").value("missing-api"));

            verify(lexicalSearchIndexRepository).searchByProvider(
                eq("slack"), eq(List.of("missing-api")), eq(1), any()
            );
            verify(lexicalSearchIndexRepository, never()).searchByProvider(eq("slack"), eq(1), any());
        }

        @Test
        @DisplayName("keeps API scope when API-only fallback returns partial results")
        void keepsApiScopeWhenApiOnlyFallbackReturnsPartialResults() throws Exception {
            when(lexicalSearchIndexRepository.getUniqueValues("provider")).thenReturn(List.of("Slack"));
            when(lexicalSearchIndexRepository.searchOptimizedWithScoring(
                any(), any(), any(), any(), anyInt(), any()
            )).thenReturn(List.of());
            when(lexicalSearchIndexRepository.searchFuzzy(
                any(), any(), any(), anyInt(), any()
            )).thenReturn(List.of());
            when(lexicalSearchIndexRepository.searchByApiFilters(
                eq(List.of("gmail")), eq(2), any()
            )).thenReturn(List.of(searchRow("Gmail", "gmail")));
            when(lexicalSearchIndexRepository.searchByProvider(
                eq("slack"), eq(List.of("gmail")), anyInt(), any()
            )).thenReturn(List.of());

            mockMvc.perform(get("/api/tools/search")
                    .param("q", "slack send message")
                    .param("api", "gmail")
                    .param("k", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.tools[0].apiSlug").value("gmail"))
                .andExpect(jsonPath("$.api_filters[0]").value("gmail"));

            verify(lexicalSearchIndexRepository).searchByProvider(
                eq("slack"), eq(List.of("gmail")), eq(6), any()
            );
            verify(lexicalSearchIndexRepository, never()).searchByProvider(eq("slack"), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("POST /api/capability_knn")
    class CapabilityKnnTests {

        @Test
        @DisplayName("should return capability response for valid request")
        void returnsCapabilityResponse() throws Exception {
            CapabilityCard card = new CapabilityCard(
                    "tool-123",
                    "Analytics Tool",
                    "Instagram",
                    List.of("userId"),
                    Map.of("start_end", "external"),
                    false,
                    0.95,
                    SearchScoreClassifier.Quality.EXCELLENT,
                    "Excellent",
                    95
            );

            CapabilityResponse response = CapabilityResponse.of(
                    List.of(card),
                    Map.of("total", 1, "query", "analytics")
            );

            when(capabilityService.rank(any(CapabilityRequest.class), any())).thenReturn(response);

            String requestBody = """
                {
                    "q": "get analytics",
                    "k": 10
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards").isArray())
                    .andExpect(jsonPath("$.cards[0].name").value("Analytics Tool"))
                    .andExpect(jsonPath("$.cards[0].prov").value("Instagram"))
                    .andExpect(jsonPath("$.meta.total").value(1));
        }

        @Test
        @DisplayName("should return capability response with hints")
        void returnsCapabilityResponseWithHints() throws Exception {
            CapabilityResponse response = CapabilityResponse.of(List.of());

            when(capabilityService.rank(any(CapabilityRequest.class), any())).thenReturn(response);

            String requestBody = """
                {
                    "q": "get user profile",
                    "k": 5,
                    "hints": {
                        "action": "get",
                        "resource": "profile",
                        "provider": "instagram"
                    },
                    "useOpenAI": true
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards").isArray());
        }

        @Test
        @DisplayName("should return 400 when query is missing")
        void returnsBadRequestWhenQueryMissing() throws Exception {
            String requestBody = """
                {
                    "k": 10
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when query is blank")
        void returnsBadRequestWhenQueryBlank() throws Exception {
            String requestBody = """
                {
                    "q": "   ",
                    "k": 10
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when k is less than 1")
        void returnsBadRequestWhenKTooSmall() throws Exception {
            String requestBody = """
                {
                    "q": "analytics",
                    "k": 0
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when k is greater than 50")
        void returnsBadRequestWhenKTooLarge() throws Exception {
            String requestBody = """
                {
                    "q": "analytics",
                    "k": 100
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 500 when service throws exception")
        void returnsServerErrorOnException() throws Exception {
            when(capabilityService.rank(any(CapabilityRequest.class), any()))
                    .thenThrow(new RuntimeException("Database error"));

            String requestBody = """
                {
                    "q": "analytics"
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.meta.error").exists());
        }

        @Test
        @DisplayName("should use default k when not provided")
        void usesDefaultK() throws Exception {
            CapabilityResponse response = CapabilityResponse.of(List.of());

            when(capabilityService.rank(any(CapabilityRequest.class), any())).thenReturn(response);

            String requestBody = """
                {
                    "q": "analytics"
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /api/catalog/v1/capabilities/knn")
    class CapabilityV1Tests {

        @Test
        @DisplayName("should return capability response for v1 endpoint")
        void returnsCapabilityResponseForV1() throws Exception {
            CapabilityResponse response = CapabilityResponse.of(
                    List.of(),
                    Map.of("total", 0)
            );

            when(capabilityService.rank(any(CapabilityRequest.class), any())).thenReturn(response);

            String requestBody = """
                {
                    "q": "get analytics"
                }
                """;

            mockMvc.perform(post("/api/catalog/v1/capabilities/knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cards").isArray());
        }

        @Test
        @DisplayName("should return 500 when v1 service throws exception")
        void returnsServerErrorOnV1Exception() throws Exception {
            when(capabilityService.rank(any(CapabilityRequest.class), any()))
                    .thenThrow(new RuntimeException("Service unavailable"));

            String requestBody = """
                {
                    "q": "analytics"
                }
                """;

            mockMvc.perform(post("/api/catalog/v1/capabilities/knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.meta.error").exists());
        }
    }

    private Map<String, Object> searchRow(String apiName, String apiSlug) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("tool_name", "Search Test Tool");
        row.put("api_name", apiName);
        row.put("api_slug", apiSlug);
        row.put("provider", apiSlug);
        row.put("resource", "messages");
        row.put("action", "send");
        row.put("score", 8.0);
        row.put("summary", "Search test tool");
        return row;
    }

    @Nested
    @DisplayName("Response Content Types")
    class ContentTypeTests {

        @Test
        @DisplayName("should return JSON content type")
        void returnsJsonContentType() throws Exception {
            CapabilityResponse response = CapabilityResponse.of(List.of());

            when(capabilityService.rank(any(CapabilityRequest.class), any())).thenReturn(response);

            String requestBody = """
                {
                    "q": "test"
                }
                """;

            mockMvc.perform(post("/api/capability_knn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }
    }
}
