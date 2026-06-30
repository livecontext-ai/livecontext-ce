package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RemoteMarketplaceController")
class RemoteMarketplaceControllerTest {

    @Mock
    private RemoteMarketplaceService remoteMarketplaceService;

    private RemoteMarketplaceController controller;

    private static final UUID PUB_ID = UUID.randomUUID();
    private static final String TENANT_ID = "42";
    private static final String ORG_ID = "22222222-2222-4222-8222-222222222222";

    @BeforeEach
    void setUp() {
        controller = new RemoteMarketplaceController(remoteMarketplaceService);
    }

    @Nested
    @DisplayName("POST /{publicationId}/acquire")
    class AcquireRemotePublication {

        @Test
        @DisplayName("Should return 200 with workflow info on successful acquire")
        void shouldReturn200OnSuccess() {
            Map<String, Object> result = Map.of(
                    "workflowId", UUID.randomUUID().toString(),
                    "publicationId", PUB_ID.toString(),
                    "title", "Test Workflow"
            );
            when(remoteMarketplaceService.acquirePublication(eq(PUB_ID), eq(TENANT_ID), isNull()))
                    .thenReturn(result);

            ResponseEntity<?> response = controller.acquireRemotePublication(PUB_ID, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsKey("workflowId");
            assertThat(body).containsEntry("title", "Test Workflow");
        }

        @Test
        @DisplayName("Should forward organization scope to remote acquire service")
        void shouldForwardOrganizationScope() {
            Map<String, Object> result = Map.of(
                    "workflowId", UUID.randomUUID().toString(),
                    "publicationId", PUB_ID.toString(),
                    "title", "Org Workflow"
            );
            when(remoteMarketplaceService.acquirePublication(eq(PUB_ID), eq(TENANT_ID), eq(ORG_ID)))
                    .thenReturn(result);

            ResponseEntity<?> response = controller.acquireRemotePublication(PUB_ID, TENANT_ID, ORG_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("Should return 403 with CLOUD_ACCOUNT_NOT_LINKED when no link")
        void shouldReturn403WhenNoCloudLink() {
            when(remoteMarketplaceService.acquirePublication(eq(PUB_ID), eq(TENANT_ID), isNull()))
                    .thenThrow(new CloudLinkService.CloudAccountNotLinkedException("No cloud account linked"));

            ResponseEntity<?> response = controller.acquireRemotePublication(PUB_ID, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("code", "CLOUD_ACCOUNT_NOT_LINKED");
        }

        @Test
        @DisplayName("Should return 402 when insufficient credits")
        void shouldReturn402WhenInsufficientCredits() {
            when(remoteMarketplaceService.acquirePublication(eq(PUB_ID), eq(TENANT_ID), isNull()))
                    .thenThrow(new RemoteMarketplaceService.InsufficientCreditsException("Insufficient credits"));

            ResponseEntity<?> response = controller.acquireRemotePublication(PUB_ID, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("code", "INSUFFICIENT_CREDITS");
        }

        @Test
        @DisplayName("Should return 400 when publication already acquired")
        void shouldReturn400WhenAlreadyAcquired() {
            when(remoteMarketplaceService.acquirePublication(eq(PUB_ID), eq(TENANT_ID), isNull()))
                    .thenThrow(new IllegalArgumentException("Publication already acquired"));

            ResponseEntity<?> response = controller.acquireRemotePublication(PUB_ID, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("error", "Publication already acquired");
        }

        @Test
        @DisplayName("Should return 500 on unexpected error")
        void shouldReturn500OnUnexpectedError() {
            when(remoteMarketplaceService.acquirePublication(eq(PUB_ID), eq(TENANT_ID), isNull()))
                    .thenThrow(new RuntimeException("Cloud unreachable"));

            ResponseEntity<?> response = controller.acquireRemotePublication(PUB_ID, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("GET cloud-parity read proxies (linked CE marketplace UI)")
    class ReadProxies {

        @Test
        @DisplayName("GET /marketplace should delegate page, size and category to the service")
        void marketplaceShouldDelegateToService() {
            Map<String, Object> upstream = Map.of("publications", List.of(Map.of("id", "p1")), "count", 1);
            when(remoteMarketplaceService.fetchMarketplacePublications(2, 25, "operations"))
                    .thenReturn(upstream);

            ResponseEntity<?> response = controller.listRemoteMarketplacePublications(2, 25, "operations");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(upstream);
            verify(remoteMarketplaceService).fetchMarketplacePublications(2, 25, "operations");
        }

        @Test
        @DisplayName("GET /marketplace without category should pass null through")
        void marketplaceShouldPassNullCategory() {
            Map<String, Object> upstream = Map.of("publications", List.of(), "count", 0);
            when(remoteMarketplaceService.fetchMarketplacePublications(0, 50, null)).thenReturn(upstream);

            ResponseEntity<?> response = controller.listRemoteMarketplacePublications(0, 50, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(remoteMarketplaceService).fetchMarketplacePublications(0, 50, null);
        }

        @Test
        @DisplayName("GET /search should delegate q and category to the service")
        void searchShouldDelegateToService() {
            Map<String, Object> upstream = Map.of("publications", List.of(Map.of("id", "p2")), "count", 1);
            when(remoteMarketplaceService.searchMarketplacePublications("crm sync", "ai"))
                    .thenReturn(upstream);

            ResponseEntity<?> response = controller.searchRemoteMarketplacePublications("crm sync", "ai");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(upstream);
        }

        @Test
        @DisplayName("GET /highlights/{displayMode} should normalize a lowercase displayMode to the canonical enum name")
        void highlightsShouldNormalizeDisplayMode() {
            Map<String, Object> upstream = Map.of("displayMode", "APPLICATION", "highlights", List.of());
            when(remoteMarketplaceService.fetchHighlights("APPLICATION")).thenReturn(upstream);

            ResponseEntity<?> response = controller.listRemoteHighlights("application");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(upstream);
            verify(remoteMarketplaceService).fetchHighlights("APPLICATION");
        }

        @Test
        @DisplayName("GET /highlights/{displayMode} should reject an unknown displayMode with 400 and never call upstream")
        void highlightsShouldRejectUnknownDisplayMode() {
            ResponseEntity<?> response = controller.listRemoteHighlights("not-a-mode");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("error", "Unknown displayMode: not-a-mode");
            verifyNoInteractions(remoteMarketplaceService);
        }
    }

    @Nested
    @DisplayName("GET cloud-parity PER-PUBLICATION read proxies")
    class PerPublicationProxies {

        @Test
        @DisplayName("GET /by-id/{id} should delegate the publication detail with an empty sub-path")
        void detailShouldDelegateWithEmptySubPath() {
            ResponseEntity<String> upstream = ResponseEntity.ok("{\"id\":\"x\"}");
            when(remoteMarketplaceService.proxyPublicByIdJson(PUB_ID, "", null)).thenReturn(upstream);

            ResponseEntity<String> response = controller.remotePublicationDetail(PUB_ID);

            assertThat(response).isSameAs(upstream);
            verify(remoteMarketplaceService).proxyPublicByIdJson(PUB_ID, "", null);
        }

        @Test
        @DisplayName("GET /by-id/{id}/landing-snapshot should delegate the landing-snapshot sub-path")
        void landingSnapshotShouldDelegate() {
            ResponseEntity<String> upstream = ResponseEntity.ok("{}");
            when(remoteMarketplaceService.proxyPublicByIdJson(PUB_ID, "landing-snapshot", null)).thenReturn(upstream);

            assertThat(controller.remoteLandingSnapshot(PUB_ID)).isSameAs(upstream);
            verify(remoteMarketplaceService).proxyPublicByIdJson(PUB_ID, "landing-snapshot", null);
        }

        @Test
        @DisplayName("GET /by-id/{id}/showcase-render should forward the query params to the service")
        void showcaseRenderShouldForwardParams() {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("page", "0");
            params.add("size", "1");
            ResponseEntity<String> upstream = ResponseEntity.ok("{}");
            when(remoteMarketplaceService.proxyPublicByIdJson(PUB_ID, "showcase-render", params)).thenReturn(upstream);

            assertThat(controller.remoteShowcaseRender(PUB_ID, params)).isSameAs(upstream);
            verify(remoteMarketplaceService).proxyPublicByIdJson(PUB_ID, "showcase-render", params);
        }

        @Test
        @DisplayName("GET /by-id/{id}/agent-snapshot should delegate the agent-snapshot sub-path")
        void agentSnapshotShouldDelegate() {
            when(remoteMarketplaceService.proxyPublicByIdJson(PUB_ID, "agent-snapshot", null))
                    .thenReturn(ResponseEntity.ok("{}"));

            controller.remoteAgentSnapshot(PUB_ID);

            verify(remoteMarketplaceService).proxyPublicByIdJson(PUB_ID, "agent-snapshot", null);
        }

        @Test
        @DisplayName("GET /by-id/{id}/run-state should delegate the run-state sub-path")
        void runStateShouldDelegate() {
            when(remoteMarketplaceService.proxyPublicByIdJson(PUB_ID, "run-state", null))
                    .thenReturn(ResponseEntity.ok("{}"));

            controller.remoteRunState(PUB_ID);

            verify(remoteMarketplaceService).proxyPublicByIdJson(PUB_ID, "run-state", null);
        }

        @Test
        @DisplayName("GET /by-id/{id}/epochs/{epoch}/state should build the epoch sub-path from the typed path variable")
        void epochStateShouldBuildSubPath() {
            when(remoteMarketplaceService.proxyPublicByIdJson(PUB_ID, "epochs/5/state", null))
                    .thenReturn(ResponseEntity.ok("{}"));

            controller.remoteEpochState(PUB_ID, 5L);

            verify(remoteMarketplaceService).proxyPublicByIdJson(PUB_ID, "epochs/5/state", null);
        }

        @Test
        @DisplayName("GET /by-id/{id}/epochs/{epoch}/signals should build the epoch signals sub-path")
        void epochSignalsShouldBuildSubPath() {
            when(remoteMarketplaceService.proxyPublicByIdJson(PUB_ID, "epochs/9/signals", null))
                    .thenReturn(ResponseEntity.ok("[]"));

            controller.remoteEpochSignals(PUB_ID, 9L);

            verify(remoteMarketplaceService).proxyPublicByIdJson(PUB_ID, "epochs/9/signals", null);
        }

        @Test
        @DisplayName("GET /users/{userId}/avatar should delegate the raw userId to the avatar proxy")
        void avatarShouldDelegate() {
            ResponseEntity<byte[]> upstream = ResponseEntity.ok(new byte[]{1, 2, 3});
            when(remoteMarketplaceService.proxyUserAvatar("77")).thenReturn(upstream);

            assertThat(controller.remoteUserAvatar("77")).isSameAs(upstream);
            verify(remoteMarketplaceService).proxyUserAvatar("77");
        }

        @Test
        @DisplayName("GET /users/{userId}/profile should delegate the raw userId to the profile proxy")
        void profileShouldDelegate() {
            ResponseEntity<String> upstream = ResponseEntity.ok("{\"userId\":77,\"handle\":\"cloud_bob\"}");
            when(remoteMarketplaceService.proxyUserProfile("77")).thenReturn(upstream);

            assertThat(controller.remoteUserProfile("77")).isSameAs(upstream);
            verify(remoteMarketplaceService).proxyUserProfile("77");
        }
    }
}
