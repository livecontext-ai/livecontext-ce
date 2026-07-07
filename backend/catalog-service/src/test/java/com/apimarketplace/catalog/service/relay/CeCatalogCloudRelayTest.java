package com.apimarketplace.catalog.service.relay;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CeCatalogCloudRelay")
class CeCatalogCloudRelayTest {

    private static final String TOOL = "telegram/send-message";
    private static final String USER_ID = "42";
    private static final String REQUEST_ID = "req-1";
    private static final String INTEGRATION = "telegram";
    private static final CloudLlmRuntimeCredentials CREDENTIALS =
            new CloudLlmRuntimeCredentials("token-1", "install-1", "https://livecontext.ai/api");

    @Mock
    private CloudLlmRuntimeAccess runtimeAccess;
    @Mock
    private ToolContextService toolContextService;
    @Mock
    private ApiRepository apiRepository;
    @Mock
    private ApiToolRepository apiToolRepository;
    @Mock
    private CredentialClient credentialClient;
    @Mock
    private CloudCatalogRelayClient relayClient;

    private CeCatalogCloudRelay relay;

    private final UUID apiId = UUID.randomUUID();
    private final UUID toolId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        relay = new CeCatalogCloudRelay(runtimeAccess, toolContextService, apiRepository,
                apiToolRepository, credentialClient, relayClient);
        stubRelayableTool();
    }

    /** Stubs a tool whose API offers a platform credential, no LOCAL credential, and an active cloud runtime. */
    private void stubRelayableTool() {
        ToolContextService.ToolContext context = new ToolContextService.ToolContext();
        context.setToolId(toolId.toString());
        context.setApiId(apiId.toString());
        context.setToolName("send_message");
        context.setIconSlug("telegram");
        lenient().when(toolContextService.loadToolContext(TOOL)).thenReturn(Optional.of(context));

        ApiEntity api = new ApiEntity();
        api.setApiSlug("telegram");
        api.setPlatformCredentialName(INTEGRATION);
        lenient().when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));

        ApiToolEntity toolEntity = mock(ApiToolEntity.class);
        lenient().when(toolEntity.getToolSlug()).thenReturn("send-message");
        lenient().when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(toolEntity));

        lenient().when(credentialClient.findPlatformCredentialByName(INTEGRATION)).thenReturn(Optional.empty());
        lenient().when(runtimeAccess.resolveCatalogCloudRuntime(USER_ID)).thenReturn(Optional.of(CREDENTIALS));
    }

    private static PlatformCredentialLookupDto foundLocalCredential() {
        PlatformCredentialLookupDto dto = new PlatformCredentialLookupDto();
        dto.setFound(true);
        dto.setId(7L);
        dto.setIntegrationName(INTEGRATION);
        return dto;
    }

    private static ToolExecutionRequest platformRequest() {
        return ToolExecutionRequest.builder()
                .credentialSource("platform")
                .parameters(Map.of("chat_id", "123", "text", "hello"))
                .expand(List.of("payload.body"))
                .maxItems(5)
                .inlineBinaries(Boolean.TRUE)
                .build();
    }

    @Nested
    @DisplayName("skip conditions (empty = local path unchanged)")
    class SkipConditions {

        @Test
        @DisplayName("inert when no CloudLlmRuntimeAccess bean exists (cloud deployment)")
        void inertWithoutRuntimeAccess() {
            CeCatalogCloudRelay cloudSide = new CeCatalogCloudRelay(null, toolContextService,
                    apiRepository, apiToolRepository, credentialClient, relayClient);

            assertThat(cloudSide.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(toolContextService, credentialClient, relayClient);
        }

        @Test
        @DisplayName("skips when request is null")
        void skipsNullRequest() {
            assertThat(relay.tryRelay(TOOL, null, USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("skips when credentialSource is 'user' (author pinned own credential)")
        void skipsUserCredentialSource() {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .credentialSource("user")
                    .build();

            assertThat(relay.tryRelay(TOOL, request, USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(toolContextService, relayClient);
        }

        @Test
        @DisplayName("skips when credentialSource is null (agentic fallback path)")
        void skipsNullCredentialSource() {
            ToolExecutionRequest request = ToolExecutionRequest.builder().build();

            assertThat(relay.tryRelay(TOOL, request, USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(toolContextService, relayClient);
        }

        @Test
        @DisplayName("skips when the tool context cannot be loaded (local ToolNotFoundException wins)")
        void skipsUnknownTool() {
            when(toolContextService.loadToolContext("nope")).thenReturn(Optional.empty());

            assertThat(relay.tryRelay("nope", platformRequest(), USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("skips when the API has no platformCredentialName (integration never offers platform creds)")
        void skipsApiWithoutPlatformCredentialName() {
            ApiEntity api = new ApiEntity();
            api.setApiSlug("telegram");
            api.setPlatformCredentialName(null);
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));

            assertThat(relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("LOCAL-FIRST regression: skips when the direct by-name lookup finds a local platform credential")
        void localPlatformCredentialAlwaysWins() {
            when(credentialClient.findPlatformCredentialByName(INTEGRATION))
                    .thenReturn(Optional.of(foundLocalCredential()));

            assertThat(relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("skips when resolveCatalogCloudRuntime is empty (unlinked or BYOK catalog source)")
        void skipsWhenNoCatalogCloudRuntime() {
            when(runtimeAccess.resolveCatalogCloudRuntime(USER_ID)).thenReturn(Optional.empty());

            assertThat(relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("regression: a publication-service hiccup during runtime resolution falls back to the local path, never a 500")
        void runtimeResolutionFailureFallsBackToLocalPathInsteadOf500() {
            // The relay branch in ToolExecutionManager.executeTool runs BEFORE the
            // method's try/catch, so pre-fix this IllegalStateException escaped the
            // tool call as a 500 instead of preserving the local-path behavior.
            when(runtimeAccess.resolveCatalogCloudRuntime(USER_ID))
                    .thenThrow(new IllegalStateException("Unable to resolve CE LLM source from publication-service"));

            assertThat(relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID)).isEmpty();
            verifyNoInteractions(relayClient);
        }
    }

    @Nested
    @DisplayName("relay execution")
    class RelayExecution {

        @Test
        @DisplayName("relays with apiSlug + toolSlug + execution payload only, and returns the cloud response as-is")
        void relaysAndReturnsCloudResponse() {
            ToolExecutionResponse cloudResponse = ToolExecutionResponse.builder()
                    .success(true)
                    .result(Map.of("ok", true))
                    .metadata(Map.of("toolName", "send_message"))
                    .toolId(toolId.toString())
                    .requestId(REQUEST_ID)
                    .build();
            when(relayClient.execute(eq(CREDENTIALS), eq("telegram"), eq("send-message"),
                    any(), any(), any(), any())).thenReturn(cloudResponse);

            ToolExecutionRequest request = platformRequest();
            Optional<ToolExecutionResponse> result = relay.tryRelay(TOOL, request, USER_ID, REQUEST_ID);

            assertThat(result).containsSame(cloudResponse);
            verify(relayClient).execute(CREDENTIALS, "telegram", "send-message",
                    request.getParameters(), request.getExpand(),
                    request.getMaxItems(), request.getInlineBinaries());
        }

        @Test
        @DisplayName("regression: local precedence probes the DIRECT by-name lookup, never the cloud-delegating public-info probe")
        void localPrecedenceNeverUsesCloudAwarePublicInfoProbe() {
            // Pre-fix, the LOCAL-FIRST gate called platformCredentialAvailable, which routes
            // through public-info. This feature made public-info delegate to the cloud when no
            // local row exists, so on a linked + subscribed install the probe answered
            // available=true, "local wins" swallowed every relay, and the local path then
            // failed credentials_required: the happy path was circularly unreachable.
            ToolExecutionResponse cloudResponse = ToolExecutionResponse.builder().success(true).build();
            when(relayClient.execute(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .thenReturn(cloudResponse);
            when(credentialClient.findPlatformCredentialByName(INTEGRATION)).thenReturn(Optional.empty());

            Optional<ToolExecutionResponse> result = relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID);

            assertThat(result).containsSame(cloudResponse);
            verify(credentialClient).findPlatformCredentialByName(INTEGRATION);
            verify(credentialClient, never()).platformCredentialAvailable(any());
        }

        @Test
        @DisplayName("cloud success=false (upstream tool failure) is returned as-is, not remapped")
        void upstreamFailurePassesThrough() {
            ToolExecutionResponse cloudFailure = ToolExecutionResponse.builder()
                    .success(false)
                    .error("Telegram returned 400: chat not found")
                    .build();
            when(relayClient.execute(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .thenReturn(cloudFailure);

            Optional<ToolExecutionResponse> result = relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID);

            assertThat(result).containsSame(cloudFailure);
        }

        @ParameterizedTest(name = "{0} maps to a failed response mentioning \"{1}\"")
        @DisplayName("relay error codes map to success=false with agent-actionable messages")
        @CsvSource(delimiter = '|', value = {
                "SUBSCRIPTION_REQUIRED|active subscription on the linked cloud account",
                "INSUFFICIENT_CREDITS|top up the linked cloud account",
                "CE_LINK_NOT_ACTIVE|reconnect the cloud account in settings",
                "OAUTH_NOT_RELAYABLE|locally configured OAuth credential",
                "PLATFORM_NOT_AVAILABLE|not offered for this integration",
                "TOOL_NOT_FOUND|not available for platform-credential execution",
                "RATE_LIMITED|Retry later",
                "AUTHENTICATION_REQUIRED|reconnect the cloud account in settings",
        })
        void mapsRelayErrorCodes(String errorCode, String expectedFragment) {
            when(relayClient.execute(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .thenThrow(new CloudCatalogRelayClient.CatalogRelayException(errorCode, false, "refused"));

            Optional<ToolExecutionResponse> result = relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID);

            assertThat(result).isPresent();
            ToolExecutionResponse response = result.get();
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).contains(expectedFragment);
            assertThat(response.getToolId()).isEqualTo(toolId.toString());
            assertThat(response.getRequestId()).isEqualTo(REQUEST_ID);
            assertThat(response.getMetadata())
                    .containsEntry("toolName", "send_message")
                    .containsEntry("iconSlug", "telegram")
                    .containsEntry("cloudRelay", true);
        }

        @Test
        @DisplayName("INSUFFICIENT_CREDITS with delinquent=true also mentions the outstanding payment")
        void insufficientCreditsDelinquentMentionsPayment() {
            when(relayClient.execute(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .thenThrow(new CloudCatalogRelayClient.CatalogRelayException(
                            "INSUFFICIENT_CREDITS", true, "refused"));

            Optional<ToolExecutionResponse> result = relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getError()).contains("outstanding payment");
        }

        @Test
        @DisplayName("transport failure yields a failed 'Cloud relay unreachable' response, never an exception")
        void transportFailureBecomesFailedResponse() {
            when(relayClient.execute(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            Optional<ToolExecutionResponse> result = relay.tryRelay(TOOL, platformRequest(), USER_ID, REQUEST_ID);

            assertThat(result).isPresent();
            ToolExecutionResponse response = result.get();
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).contains("Cloud relay unreachable");
            assertThat(response.getMetadata()).containsEntry("cloudRelay", true);
        }

        @Test
        @DisplayName("credentialSource matching is case-insensitive ('PLATFORM' relays too)")
        void platformSourceMatchIsCaseInsensitive() {
            ToolExecutionResponse cloudResponse = ToolExecutionResponse.builder().success(true).build();
            when(relayClient.execute(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .thenReturn(cloudResponse);

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .credentialSource("PLATFORM")
                    .parameters(Map.of())
                    .build();

            assertThat(relay.tryRelay(TOOL, request, USER_ID, REQUEST_ID)).containsSame(cloudResponse);
        }
    }
}
