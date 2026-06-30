package com.apimarketplace.orchestrator.controllers.cloud;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.orchestrator.tools.websearch.CeWebSearchRelayRequest;
import com.apimarketplace.orchestrator.tools.websearch.WebSearchModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cloud-side relay endpoint tests: install-link validation, request shaping and
 * the single WEB_SEARCH debit on the linked cloud account.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudWebSearchRelayController (cloud)")
class CloudWebSearchRelayControllerTest {

    private static final long CLOUD_USER_ID = 42L;
    private static final String INSTALL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Mock
    private AuthClient authClient;
    @Mock
    private WebSearchModule searchModule;

    private CloudWebSearchRelayController controller;

    @BeforeEach
    void setUp() {
        controller = new CloudWebSearchRelayController(authClient, searchModule);
    }

    private static CeWebSearchRelayRequest request(String query) {
        return new CeWebSearchRelayRequest(query, 5, "week", "stream-1", "tc-1");
    }

    @Nested
    @DisplayName("link validation")
    class LinkValidation {

        @Test
        @DisplayName("rejects installs the caller does not own with 403 and never executes a search")
        void rejectsNonOwnerInstall() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "CE_LINK_NOT_ACTIVE"));
            verifyNoInteractions(searchModule);
        }

        @Test
        @DisplayName("rejects a missing user id with 401")
        void rejectsMissingUserId() {
            ResponseEntity<Map<String, Object>> response =
                    controller.search(null, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(authClient, searchModule);
        }

        @Test
        @DisplayName("rejects a blank query with 400 after link validation")
        void rejectsBlankQuery() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("  "));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "INVALID_RELAY_REQUEST"));
            verifyNoInteractions(searchModule);
        }
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("executes the search as the cloud user and threads the CE chat ids into the billing context")
        void executesAsCloudUserWithCeChatIds() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            Map<String, Object> searchData = Map.of("results", List.of(Map.of("url", "https://e.com")));
            when(searchModule.execute(eq("search"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(searchData)));

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java 21"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(searchData);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<ToolExecutionContext> contextCaptor =
                    ArgumentCaptor.forClass(ToolExecutionContext.class);
            verify(searchModule, times(1)).execute(eq("search"), paramsCaptor.capture(),
                    eq("42"), contextCaptor.capture());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("query", "java 21")
                    .containsEntry("max_results", 5)
                    .containsEntry("time_range", "week");
            // regression (audit F1): CE-supplied chat identifiers must NEVER reach the
            // billing credentials - the ledger dedups on a globally-unique source_id, so
            // a client-controlled key would let a linked install replay one pair for
            // unlimited searches billed once. Billing falls back to a server-side UUID.
            assertThat(contextCaptor.getValue().tenantId()).isEqualTo("42");
            assertThat(contextCaptor.getValue().credentials()).isEmpty();
        }

        @Test
        @DisplayName("clamps max_results into [1, 50] and omits absent optional params")
        void clampsMaxResultsAndOmitsAbsentParams() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("results", List.of()))));

            controller.search(CLOUD_USER_ID, INSTALL_ID,
                    new CeWebSearchRelayRequest("java", 999, null, null, null));
            controller.search(CLOUD_USER_ID, INSTALL_ID,
                    new CeWebSearchRelayRequest("java", -3, null, null, null));
            controller.search(CLOUD_USER_ID, INSTALL_ID,
                    new CeWebSearchRelayRequest("java", null, null, null, null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(searchModule, times(3)).execute(eq("search"), paramsCaptor.capture(), eq("42"), any());
            List<Map<String, Object>> calls = paramsCaptor.getAllValues();
            assertThat(calls.get(0)).containsEntry("max_results", 50);
            assertThat(calls.get(1)).containsEntry("max_results", 1);
            assertThat(calls.get(2)).doesNotContainKeys("max_results", "time_range");
            assertThat(calls.get(2)).doesNotContainKey("__streamId__");
        }
    }

    @Nested
    @DisplayName("failure propagation")
    class FailurePropagation {

        @Test
        @DisplayName("search failure maps to 502 with the module error")
        void searchFailureMapsTo502() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.failure(
                            ToolErrorCode.EXTERNAL_SERVICE_ERROR, "No response from websearch-service")));

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "No response from websearch-service"));
        }

        @Test
        @DisplayName("module returning empty maps to 502")
        void emptyModuleResultMapsTo502() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * End-to-end billing proof with the REAL {@link WebSearchModule}: every successful
     * relayed search posts exactly ONE flat WEB_SEARCH debit on the cloud account under
     * a SERVER-generated sourceId (client identifiers never form the dedup key); a
     * failed search posts none.
     */
    @Nested
    @DisplayName("billing through the real WebSearchModule")
    class BillingThroughRealModule {

        @Mock
        private RestTemplate searxRestTemplate;
        @Mock
        private WebSearchConfig config;
        @Mock
        private CreditConsumptionClient creditClient;

        @BeforeEach
        void wireRealModule() {
            when(searxRestTemplate.getInterceptors()).thenReturn(List.of());
            lenient().when(config.getServiceUrl()).thenReturn("http://websearch:8085");
            WebSearchModule realModule = new WebSearchModule(searxRestTemplate, config, creditClient);
            clearInvocations(searxRestTemplate);
            controller = new CloudWebSearchRelayController(authClient, realModule);
        }

        @Test
        @DisplayName("regression (audit F1): each relayed search bills once with a SERVER-generated sourceId - replaying client ids cannot dedupe-dodge the debit")
        void billsWebSearchOnceOnCloudUser() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searxRestTemplate.postForObject(eq("http://websearch:8085/search"), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of()));

            ResponseEntity<Map<String, Object>> first =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));
            ResponseEntity<Map<String, Object>> replay =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            ArgumentCaptor<String> sourceIds = ArgumentCaptor.forClass(String.class);
            verify(creditClient, times(2)).consumeCreditsAsync(
                    eq("42"), eq("WEB_SEARCH"),
                    sourceIds.capture(),
                    eq("websearch"), eq("default"), eq(0), eq(0));
            // Server-side fallback scheme, never the client-controlled CHAT scheme.
            assertThat(sourceIds.getAllValues()).allSatisfy(id -> {
                assertThat(id).contains(":FALLBACK:");
                assertThat(id).doesNotContain("stream-1").doesNotContain("tc-1");
            });
            // Two searches with IDENTICAL client identifiers debit under DISTINCT ids.
            assertThat(sourceIds.getAllValues().get(0)).isNotEqualTo(sourceIds.getAllValues().get(1));
        }

        @Test
        @DisplayName("failed relayed search bills nothing")
        void failedSearchBillsNothing() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searxRestTemplate.postForObject(eq("http://websearch:8085/search"), any(), eq(Map.class)))
                    .thenReturn(null);

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }
    }

    /**
     * Bean-gating contract for {@code @ConditionalOnProperty(name = "websearch.enabled",
     * havingValue = "true", matchIfMissing = true)} on the controller (line 37). The relay
     * is only mounted where the local websearch engine runs (cloud); a CE deployment that
     * sets {@code websearch.enabled=false} must NEVER expose the relay endpoint, so the bean
     * must not be created at all. When the property is absent or {@code true} (cloud default)
     * the bean is wired.
     */
    @Nested
    @DisplayName("bean gating on websearch.enabled")
    class BeanGating {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(AuthClient.class, () -> mock(AuthClient.class))
                .withBean(WebSearchModule.class, () -> mock(WebSearchModule.class))
                .withUserConfiguration(CloudWebSearchRelayController.class);

        @Test
        @DisplayName("bean is NOT wired when websearch.enabled=false (CE deployment)")
        void notWiredWhenDisabled() {
            runner.withPropertyValues("websearch.enabled=false")
                    .run(ctx -> assertThat(ctx)
                            .doesNotHaveBean(CloudWebSearchRelayController.class));
        }

        @Test
        @DisplayName("bean is NOT wired when websearch.enabled is a non-true value")
        void notWiredWhenNonTrueValue() {
            runner.withPropertyValues("websearch.enabled=maybe")
                    .run(ctx -> assertThat(ctx)
                            .doesNotHaveBean(CloudWebSearchRelayController.class));
        }

        @Test
        @DisplayName("bean IS wired when websearch.enabled=true (cloud)")
        void wiredWhenEnabled() {
            runner.withPropertyValues("websearch.enabled=true")
                    .run(ctx -> assertThat(ctx)
                            .hasSingleBean(CloudWebSearchRelayController.class));
        }

        @Test
        @DisplayName("bean IS wired when websearch.enabled is absent (matchIfMissing=true)")
        void wiredWhenPropertyMissing() {
            runner.run(ctx -> assertThat(ctx)
                    .hasSingleBean(CloudWebSearchRelayController.class));
        }
    }
}
