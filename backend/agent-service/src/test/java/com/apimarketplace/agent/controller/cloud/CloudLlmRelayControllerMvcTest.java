package com.apimarketplace.agent.controller.cloud;

import com.apimarketplace.agent.cloud.CloudLlmRelayRequest;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.cloud.CeRelayAccrualStore;
import com.apimarketplace.agent.service.cloud.CeRelaySettlementService;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-integration test of {@link CloudLlmRelayController} over a real Spring-MVC stack
 * ({@code standaloneSetup} = DispatcherServlet routing + {@code @RequestHeader}/{@code @RequestBody}
 * binding + Jackson (de)serialization + media-type negotiation), with every collaborator mocked so
 * no real LLM provider, bridge, or network is touched. The sibling {@link CloudLlmRelayControllerTest}
 * calls the controller methods directly and so never exercises the wire contract; this class pins it:
 *  - {@code POST /api/ce-llm/complete} routes, binds {@code X-User-ID} (String to Long) and
 *    {@code X-LiveContext-Install-Id}, deserializes the nested {@link CloudLlmRelayRequest} body,
 *    and serializes the {@link CompletionResponse} as a JSON envelope;
 *  - {@code POST /api/ce-llm/stream} negotiates the {@code application/x-ndjson} media type and writes
 *    newline-delimited {@code CloudLlmStreamEvent} JSON over the streaming response body.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLlmRelayController - HTTP wire contract over MVC")
class CloudLlmRelayControllerMvcTest {

    private static final String CLOUD_USER_ID = "42";
    private static final String INSTALL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PROVIDER = "deepseek";
    private static final String MODEL = "deepseek-chat";

    @Mock private AuthClient authClient;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private LLMProviderFactory providerFactory;
    @Mock private LLMProvider provider;
    @Mock private CeRelayAccrualStore accrualStore;
    @Mock private CeRelaySettlementService settlementService;
    @Mock private ModelConfigOverrideRepository modelConfigRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CloudLlmRelayController controller = new CloudLlmRelayController(
                authClient, creditClient, providerFactory, objectMapper, accrualStore, settlementService,
                modelConfigRepository, true);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        // Default model IS curated: the unmanaged-model guard must not reject the happy paths.
        lenient().when(modelConfigRepository.findByProviderAndModelId(PROVIDER, MODEL))
                .thenReturn(Optional.of(new ModelConfigOverrideEntity()));
    }

    @Test
    @DisplayName("POST /complete routes, binds X-User-ID + install headers, deserializes the body, and serializes the JSON response")
    void completeOverMvcBindsHeadersDeserializesBodyAndSerializesJson() throws Exception {
        when(authClient.userOwnsActiveCeLink(CLOUD_USER_ID, INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(provider.complete(any())).thenReturn(response("done", 11, 7));
        when(creditClient.checkChatBudget(eq(CLOUD_USER_ID), eq(PROVIDER), eq(MODEL), anyInt(), anyInt()))
                .thenReturn(true);
        when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));
        String body = objectMapper.writeValueAsString(
                new CloudLlmRelayRequest(PROVIDER, completionRequest()));

        mockMvc.perform(post("/api/ce-llm/complete")
                        .header("X-User-ID", CLOUD_USER_ID)
                        .header("X-LiveContext-Install-Id", INSTALL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // The provider's CompletionResponse is serialized back as the JSON envelope.
                .andExpect(jsonPath("$.content").value("done"))
                .andExpect(jsonPath("$.usage.promptTokens").value(11));

        // X-User-ID bound String to Long and was stringified for the link check (header extraction).
        verify(authClient).userOwnsActiveCeLink(CLOUD_USER_ID, INSTALL_ID);
        // The nested CompletionRequest deserialized from the body and the tenant was rewritten to the
        // cloud user before dispatch, proving @RequestBody binding reached the controller intact.
        ArgumentCaptor<CompletionRequest> dispatched = ArgumentCaptor.forClass(CompletionRequest.class);
        verify(provider).complete(dispatched.capture());
        assertThat(dispatched.getValue().tenantId()).isEqualTo(CLOUD_USER_ID);
        assertThat(dispatched.getValue().model()).isEqualTo(MODEL);
    }

    @Test
    @DisplayName("POST /stream negotiates application/x-ndjson and writes newline-delimited stream events")
    void streamOverMvcNegotiatesNdjsonAndWritesEvents() throws Exception {
        when(authClient.userOwnsActiveCeLink(CLOUD_USER_ID, INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(creditClient.checkChatBudget(eq(CLOUD_USER_ID), eq(PROVIDER), eq(MODEL), anyInt(), anyInt()))
                .thenReturn(true);
        lenient().when(creditClient.consumeCredits(any(), any(), any(), any(), any(), anyInt(), anyInt(),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));
        org.mockito.Mockito.doAnswer(invocation -> {
            StreamingCallback callback = invocation.getArgument(1);
            callback.onChunk("hi");
            callback.onComplete(response("hi", 5, 2));
            return null;
        }).when(provider).completeStreaming(any(), any());
        String body = objectMapper.writeValueAsString(
                new CloudLlmRelayRequest(PROVIDER, completionRequest()));

        // StreamingResponseBody is handled asynchronously: start, then dispatch to flush the body.
        MvcResult started = mockMvc.perform(post("/api/ce-llm/stream")
                        .header("X-User-ID", CLOUD_USER_ID)
                        .header("X-LiveContext-Install-Id", INSTALL_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                // The stream endpoint advertises the NDJSON media type (not application/json).
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                // The content chunk is serialized as a CloudLlmStreamEvent line on the wire.
                .andExpect(content().string(containsString("\"type\":\"CONTENT_CHUNK\"")))
                .andExpect(content().string(containsString("\"content\":\"hi\"")));
    }

    private CompletionRequest completionRequest() {
        return CompletionRequest.builder()
                .tenantId("ce-local-user")
                .model(MODEL)
                .systemPrompt("You route tools locally.")
                .userPrompt("Use the local lookup tool.")
                .maxTokens(256)
                .tools(List.of(ToolDefinition.builder()
                        .id("tool-1")
                        .name("local_lookup")
                        .description("Local tool that must not run in Cloud")
                        .build()))
                .build();
    }

    private static CompletionResponse response(String content, int promptTokens, int completionTokens) {
        return CompletionResponse.builder()
                .content(content)
                .finishReason("stop")
                .model(MODEL)
                .usage(UsageInfo.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .build())
                .build();
    }
}
