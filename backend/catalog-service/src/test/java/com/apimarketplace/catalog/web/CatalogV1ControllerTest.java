package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.IntentResolutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolListResponse;
import com.apimarketplace.catalog.service.CatalogV1Service;
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

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogV1Controller")
class CatalogV1ControllerTest {

    @Mock
    private CatalogV1Service catalogV1Service;

    @Mock
    private com.apimarketplace.catalog.service.execution.MockToolExecutionService mockToolExecutionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CatalogV1Controller controller = new CatalogV1Controller(catalogV1Service, mockToolExecutionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("GET /catalog/v1/tools")
    class GetToolsTests {

        @Test
        @DisplayName("should return list response with default limit")
        void getToolsWithDefaultLimit() throws Exception {
            ToolListResponse response = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .limit(20)
                    .offset(0)
                    .build();
            when(catalogV1Service.getTools(20, null, null, null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/tools"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.limit").value(20));
        }

        @Test
        @DisplayName("should return list response with custom limit")
        void getToolsWithCustomLimit() throws Exception {
            ToolListResponse response = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .limit(50)
                    .build();
            when(catalogV1Service.getTools(50, null, null, null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/tools")
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.limit").value(50));
        }

        @Test
        @DisplayName("should pass category and search parameters")
        void getToolsWithCategoryAndSearch() throws Exception {
            ToolListResponse response = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .build();
            when(catalogV1Service.getTools(20, "analytics", "instagram", null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/tools")
                            .param("category", "analytics")
                            .param("search", "instagram"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should pass user and org headers")
        void getToolsWithHeaders() throws Exception {
            ToolListResponse response = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .build();
            when(catalogV1Service.getTools(20, null, null, "user123", "org456")).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/tools")
                            .header("X-User-ID", "user123")
                            .header("X-Organization-ID", "org456"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void getToolsReturnsErrorOnException() throws Exception {
            when(catalogV1Service.getTools(anyInt(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Database error"));

            mockMvc.perform(get("/catalog/v1/tools"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("Database error"));
        }
    }

    @Nested
    @DisplayName("POST /catalog/v1/tools/{toolId}/execute")
    class ExecuteToolTests {

        @Test
        @DisplayName("should execute tool successfully")
        void executeTool() throws Exception {
            UUID toolId = UUID.randomUUID();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolId.toString())
                    .build();
            when(catalogV1Service.executeTool(eq(toolId.toString()), any(ToolExecutionRequest.class), any(), any(), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.toolId").value(toolId.toString()));
        }

        @Test
        @DisplayName("the generation pricing headers reach the request object, or nothing downstream can price the call")
        void generationHeadersReachTheRequest() throws Exception {
            // THE BOUNDARY NOTHING WATCHED. Three separate tests prove the body
            // cannot set these, that the sender emits them, and that the gateway
            // strips them from outside. None proved the RECEIVER reads them.
            // Disabling the model binding here left 72 tests green and BUILD
            // SUCCESS, while downstream the per-model price row is never
            // selected: on an endpoint with per-model rows only, every
            // generation is refused "no price published"; on one that also
            // carries an endpoint-wide row, it is charged that rate instead of
            // the model's.
            UUID toolId = UUID.randomUUID();
            when(catalogV1Service.executeTool(eq(toolId.toString()), any(ToolExecutionRequest.class),
                    any(), any(), any()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Lc-Generation-Model", "seedance-2.0")
                            .header("X-Lc-Generation-Quantity", "10")
                            .header("X-Lc-Generation-Unit", "second")
                            .header("X-Lc-Billing-Scope-Kind", "RUN")
                            .header("X-Lc-Billing-Scope-Id", "run-1")
                            .header("X-Lc-Billing-Step-Id", "step-1")
                            .content("{}"))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<ToolExecutionRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ToolExecutionRequest.class);
            verify(catalogV1Service).executeTool(eq(toolId.toString()), captor.capture(),
                    any(), any(), any());
            ToolExecutionRequest sent = captor.getValue();

            assertEquals("seedance-2.0", sent.getGenerationModelId(),
                    "which published row prices this call");
            assertEquals(0, new java.math.BigDecimal("10").compareTo(sent.getGenerationQuantity()),
                    "a ten second clip must not arrive as one");
            assertEquals("second", sent.getGenerationQuantityUnit(),
                    "without the unit a per-image rate can multiply a count of seconds");
            assertEquals("RUN", sent.getBillingScopeKind());
            assertEquals("run-1", sent.getBillingScopeId());
            assertEquals("step-1", sent.getBillingStepId());
        }

        @Test
        @DisplayName("the price factor is read from its header, and an absurd one is dropped")
        void generationPriceFactorHeaderIsBound() throws Exception {
            // It multiplies the amount charged. Lost on the way in, a 1080p render is billed at the
            // 720p rate on every call; read as ZERO, the generation is free. Neither shows anywhere.
            UUID toolId = UUID.randomUUID();
            when(catalogV1Service.executeTool(eq(toolId.toString()), any(ToolExecutionRequest.class),
                    any(), any(), any()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Lc-Generation-Model", "seedance-2.0")
                            .header("X-Lc-Generation-Quantity", "10")
                            .header("X-Lc-Generation-Unit", "second")
                            .header("X-Lc-Generation-Multiplier", "2.4")
                            .content("{}"))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<ToolExecutionRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ToolExecutionRequest.class);
            verify(catalogV1Service).executeTool(eq(toolId.toString()), captor.capture(),
                    any(), any(), any());
            assertEquals(0, new java.math.BigDecimal("2.4")
                            .compareTo(captor.getValue().getGenerationPriceMultiplier()),
                    "the factor decides the amount, so it has to arrive intact");

            // Zero and below cannot come from any descriptor this platform accepts. Dropped rather
            // than honoured: an absent factor means "at the published rate", which is the
            // conservative reading, while a zero would multiply the whole charge away.
            // Above the CEILING is dropped too, and that is the case this door was hardened for:
            // it checked only `<= 0` and passed everything else to the biller, so a forged
            // factor of a million on a row with no maxCredits was reserved and committed. The
            // bound is the descriptor parser's own constant, so nothing a seed can produce is
            // refused here.
            for (String absurd : new String[] { "0", "-3", "100.01", "1000000" }) {
                org.mockito.Mockito.clearInvocations(catalogV1Service);
                mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Lc-Generation-Multiplier", absurd)
                                .content("{}"))
                        .andExpect(status().isOk());
                verify(catalogV1Service).executeTool(eq(toolId.toString()), captor.capture(),
                        any(), any(), any());
                assertNull(captor.getValue().getGenerationPriceMultiplier(),
                        "a factor of " + absurd + " is not a price");
            }
        }

        @Test
        @DisplayName("the ceiling ITSELF is bound, so the bound is not off by one against a real seed")
        void theCeilingItselfIsAccepted() throws Exception {
            // The parser refuses a model whose modifiers reach MORE than this together, so a
            // product exactly equal to it is a descriptor this platform accepts and must charge.
            UUID toolId = UUID.randomUUID();
            when(catalogV1Service.executeTool(eq(toolId.toString()), any(ToolExecutionRequest.class),
                    any(), any(), any()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Lc-Generation-Multiplier",
                                    com.apimarketplace.common.web.BillingContextHeaders
                                            .MAX_GENERATION_MULTIPLIER.toPlainString())
                            .content("{}"))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<ToolExecutionRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ToolExecutionRequest.class);
            verify(catalogV1Service).executeTool(eq(toolId.toString()), captor.capture(),
                    any(), any(), any());
            assertEquals(0, com.apimarketplace.common.web.BillingContextHeaders
                            .MAX_GENERATION_MULTIPLIER
                            .compareTo(captor.getValue().getGenerationPriceMultiplier()),
                    "a factor the parser accepts must be chargeable");
        }

        @Test
        @DisplayName("an ordinary call carries no factor, so nothing about it changed")
        void anOrdinaryCallCarriesNoFactor() throws Exception {
            UUID toolId = UUID.randomUUID();
            when(catalogV1Service.executeTool(eq(toolId.toString()), any(ToolExecutionRequest.class),
                    any(), any(), any()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<ToolExecutionRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ToolExecutionRequest.class);
            verify(catalogV1Service).executeTool(eq(toolId.toString()), captor.capture(),
                    any(), any(), any());
            assertNull(captor.getValue().getGenerationPriceMultiplier());
        }

        @Test
        @DisplayName("analytics attribution comes from X-Lc-Workflow-Id / X-Lc-Node-Id headers only; a body cannot claim a workflow")
        void analyticsAttributionIsHeaderOnly() throws Exception {
            when(catalogV1Service.executeTool(eq("slack/send-message"), any(ToolExecutionRequest.class), any(), any(), any()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            // Header form (the orchestrator gateway): bound.
            mockMvc.perform(post("/catalog/v1/tools/{apiSlug}/{toolSlug}/execute", "slack", "send-message")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Lc-Workflow-Id", "6f1c2a3e-1234-4bcd-9ef0-123456789abc")
                            .header("X-Lc-Node-Id", "mcp:slack/send_message")
                            .content("{}"))
                    .andExpect(status().isOk());
            org.mockito.ArgumentCaptor<ToolExecutionRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ToolExecutionRequest.class);
            verify(catalogV1Service).executeTool(eq("slack/send-message"), captor.capture(), any(), any(), any());
            assertEquals("6f1c2a3e-1234-4bcd-9ef0-123456789abc", captor.getValue().getAnalyticsWorkflowId());
            assertEquals("mcp:slack/send_message", captor.getValue().getAnalyticsNodeId());

            // Malformed values (the gateway does not strip these headers) are dropped, not stored.
            org.mockito.Mockito.clearInvocations(catalogV1Service);
            mockMvc.perform(post("/catalog/v1/tools/{apiSlug}/{toolSlug}/execute", "slack", "send-message")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Lc-Workflow-Id", "not a uuid <script>")
                            .header("X-Lc-Node-Id", "x".repeat(300))
                            .content("{}"))
                    .andExpect(status().isOk());
            verify(catalogV1Service).executeTool(eq("slack/send-message"), captor.capture(), any(), any(), any());
            assertNull(captor.getValue().getAnalyticsWorkflowId(), "a non-UUID workflow id is dropped");
            assertNull(captor.getValue().getAnalyticsNodeId(), "an oversized node id is dropped");

            // Body form (any caller): ignored, the fields are sealed off the wire.
            org.mockito.Mockito.clearInvocations(catalogV1Service);
            mockMvc.perform(post("/catalog/v1/tools/{apiSlug}/{toolSlug}/execute", "slack", "send-message")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"analyticsWorkflowId\":\"someone-elses-workflow\",\"analyticsNodeId\":\"mcp:x\",\"parameters\":{}}"))
                    .andExpect(status().isOk());
            verify(catalogV1Service).executeTool(eq("slack/send-message"), captor.capture(), any(), any(), any());
            assertNull(captor.getValue().getAnalyticsWorkflowId(), "body must not be able to set the workflow attribution");
            assertNull(captor.getValue().getAnalyticsNodeId(), "body must not be able to set the node attribution");
        }

        @Test
        @DisplayName("an ordinary call carries no generation context, so nothing is priced as a generation")
        void anOrdinaryCallCarriesNoGenerationContext() throws Exception {
            // Hard-coding the three values would satisfy the test above while
            // pricing every ordinary endpoint as a generation.
            UUID toolId = UUID.randomUUID();
            when(catalogV1Service.executeTool(eq(toolId.toString()), any(ToolExecutionRequest.class),
                    any(), any(), any()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<ToolExecutionRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ToolExecutionRequest.class);
            verify(catalogV1Service).executeTool(eq(toolId.toString()), captor.capture(),
                    any(), any(), any());

            assertNull(captor.getValue().getGenerationModelId());
            assertNull(captor.getValue().getGenerationQuantity());
            assertNull(captor.getValue().getGenerationQuantityUnit());
        }

        @Test
        @DisplayName("should execute tool with slug identifier")
        void executeToolWithSlug() throws Exception {
            String toolSlug = "my-api-get-users";
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolSlug)
                    .build();
            when(catalogV1Service.executeTool(eq(toolSlug), any(), any(), any(), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("should execute tool without request body")
        void executeToolWithoutBody() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .build();
            when(catalogV1Service.executeTool(eq(toolId), any(), any(), any(), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should pass headers to service")
        void executeToolWithHeaders() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .requestId("req-123")
                    .build();
            when(catalogV1Service.executeTool(eq(toolId), any(), eq("user123"), eq("org456"), eq("req-123")))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-User-ID", "user123")
                            .header("X-Organization-ID", "org456")
                            .header("X-Request-Id", "req-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("req-123"));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void executeToolReturnsErrorOnException() throws Exception {
            String toolId = UUID.randomUUID().toString();
            when(catalogV1Service.executeTool(anyString(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Execution failed"));

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.toolId").value(toolId));
        }

        @Test
        @DisplayName("should propagate credentialSource and selectedCredentialId during request, then clear() in finally")
        void executeToolSetsAndClearsCredentialModeContext() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true).toolId(toolId).requestId("req-1").build();

            // Capture explicitSource seen during the service call (proves
            // setExplicitSource was applied BEFORE the catalog service ran),
            // then assert the ThreadLocal is cleared after the response
            // returns (proves the finally block fired). Workflow direct calls
            // POST credentialSource on the request body; legacy
            // credentialModeOverride is now @JsonIgnore-sealed and cannot
            // resurrect the legacy code path.
            String[] seenInsideRequest = new String[]{"NOT_CAPTURED"};
            Long[] seenSelectedCredentialId = new Long[]{Long.MIN_VALUE};
            org.mockito.Mockito.doAnswer(invocation -> {
                seenInsideRequest[0] = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();
                seenSelectedCredentialId[0] = com.apimarketplace.catalog.service.http.CredentialModeContext.getSelectedCredentialId();
                return response;
            }).when(catalogV1Service).executeTool(anyString(), any(ToolExecutionRequest.class), any(), any(), any());

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"credentialSource\":\"user\",\"selectedCredentialId\":123}"))
                    .andExpect(status().isOk());

            org.junit.jupiter.api.Assertions.assertEquals("user", seenInsideRequest[0],
                    "explicitSource must be visible to the service via CredentialModeContext.getExplicitSource() during the request");
            org.junit.jupiter.api.Assertions.assertEquals(123L, seenSelectedCredentialId[0],
                    "selectedCredentialId must be visible to the service during the request");
            org.junit.jupiter.api.Assertions.assertNull(
                    com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource(),
                    "CredentialModeContext must be cleared after the request (finally block); else 'platform' leaks to next thread-pool task");
            org.junit.jupiter.api.Assertions.assertNull(
                    com.apimarketplace.catalog.service.http.CredentialModeContext.getSelectedCredentialId(),
                    "selectedCredentialId must be cleared after the request");
        }

        @Test
        @DisplayName("should clear CredentialModeContext even when the service throws (regression: leaked source on next request)")
        void executeToolClearsCredentialModeContextOnException() throws Exception {
            String toolId = UUID.randomUUID().toString();
            when(catalogV1Service.executeTool(anyString(), any(ToolExecutionRequest.class), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            // Pre-flight: nothing in the ThreadLocal.
            com.apimarketplace.catalog.service.http.CredentialModeContext.clear();

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", toolId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"credentialSource\":\"platform\"}"))
                    .andExpect(status().isInternalServerError());

            org.junit.jupiter.api.Assertions.assertNull(
                    com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource(),
                    "finally must clear() even on exception path; otherwise the next request on this thread sees a stale 'platform'");
        }
    }

    @Nested
    @DisplayName("POST /catalog/v1/tools/.../execute-mock")
    class ExecuteMockToolTests {

        @Test
        @DisplayName("should serve the mock execution response for a single-segment tool id")
        void executeMockHappyPath() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolId)
                    .build();
            when(mockToolExecutionService.executeMockTool(eq(toolId), anyString())).thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", toolId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.toolId").value(toolId));
        }

        @Test
        @DisplayName("two-segment form resolves the tool id to 'apiSlug/toolSlug' (orchestrator step id format)")
        void executeMockTwoSegmentSlugForm() throws Exception {
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId("gmail/gmail-list-messages")
                    .build();
            when(mockToolExecutionService.executeMockTool(eq("gmail/gmail-list-messages"), anyString()))
                    .thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{apiSlug}/{toolSlug}/execute-mock",
                            "gmail", "gmail-list-messages"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            org.mockito.Mockito.verify(mockToolExecutionService)
                    .executeMockTool(eq("gmail/gmail-list-messages"), anyString());
        }

        @Test
        @DisplayName("should forward the X-Request-Id header to the service when provided")
        void executeMockForwardsRequestId() throws Exception {
            String toolId = UUID.randomUUID().toString();
            ToolExecutionResponse response = ToolExecutionResponse.builder()
                    .success(true)
                    .requestId("req-mock-9")
                    .build();
            when(mockToolExecutionService.executeMockTool(toolId, "req-mock-9")).thenReturn(response);

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", toolId)
                            .header("X-Request-Id", "req-mock-9"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("req-mock-9"));
        }

        @Test
        @DisplayName("ToolNotFoundException translates to 404 with {success:false, message:'Tool not found', toolId} (orchestrator's CatalogMockClient reads this shape)")
        void executeMockToolNotFoundIs404() throws Exception {
            when(mockToolExecutionService.executeMockTool(eq("gmail/nope"), anyString()))
                    .thenThrow(new com.apimarketplace.catalog.service.exception.ToolNotFoundException("gmail/nope"));

            mockMvc.perform(post("/catalog/v1/tools/{apiSlug}/{toolSlug}/execute-mock", "gmail", "nope"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Tool not found"))
                    .andExpect(jsonPath("$.toolId").value("gmail/nope"));
        }

        @Test
        @DisplayName("MockExampleNotFoundException translates to 404 with the exception message as 'message' + toolId")
        void executeMockExampleNotFoundIs404() throws Exception {
            String toolId = UUID.randomUUID().toString();
            when(mockToolExecutionService.executeMockTool(eq(toolId), anyString()))
                    .thenThrow(new com.apimarketplace.catalog.service.execution.MockToolExecutionService
                            .MockExampleNotFoundException("No default example response configured for tool " + toolId));

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", toolId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message")
                            .value("No default example response configured for tool " + toolId))
                    .andExpect(jsonPath("$.toolId").value(toolId));
        }

        @Test
        @DisplayName("an unexpected service exception translates to 500 with the error detail")
        void executeMockUnexpectedErrorIs500() throws Exception {
            String toolId = UUID.randomUUID().toString();
            when(mockToolExecutionService.executeMockTool(eq(toolId), anyString()))
                    .thenThrow(new RuntimeException("projection blew up"));

            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", toolId))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("projection blew up"))
                    .andExpect(jsonPath("$.toolId").value(toolId));
        }
    }

    @Nested
    @DisplayName("GET /catalog/v1/intents/resolve")
    class ResolveIntentTests {

        @Test
        @DisplayName("should require query parameter")
        void resolveIntentRequiresQuery() throws Exception {
            mockMvc.perform(get("/catalog/v1/intents/resolve"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return response for valid query")
        void resolveIntent() throws Exception {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                    .query("analytics")
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();
            when(catalogV1Service.resolveIntent("analytics", 5, null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/intents/resolve")
                            .param("q", "analytics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.query").value("analytics"))
                    .andExpect(jsonPath("$.totalCandidates").value(0));
        }

        @Test
        @DisplayName("should use custom limit")
        void resolveIntentWithCustomLimit() throws Exception {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                    .query("search")
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();
            when(catalogV1Service.resolveIntent("search", 10, null, null)).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/intents/resolve")
                            .param("q", "search")
                            .param("limit", "10"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should pass headers to service")
        void resolveIntentWithHeaders() throws Exception {
            IntentResolutionResponse response = IntentResolutionResponse.builder()
                    .query("test")
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();
            when(catalogV1Service.resolveIntent("test", 5, "user123", "org456")).thenReturn(response);

            mockMvc.perform(get("/catalog/v1/intents/resolve")
                            .param("q", "test")
                            .header("X-User-ID", "user123")
                            .header("X-Organization-ID", "org456"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void resolveIntentReturnsErrorOnException() throws Exception {
            when(catalogV1Service.resolveIntent(anyString(), anyInt(), any(), any()))
                    .thenThrow(new RuntimeException("Resolution error"));

            mockMvc.perform(get("/catalog/v1/intents/resolve")
                            .param("q", "test"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
