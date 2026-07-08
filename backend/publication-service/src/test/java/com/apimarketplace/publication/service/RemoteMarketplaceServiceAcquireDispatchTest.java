package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RemoteMarketplaceService#acquirePublication} dispatch on a cloud-linked
 * CE: the cloud fetch (free → /snapshot directly; PAID → /acquire-with-auth on
 * the LINKED CLOUD ACCOUNT) is shared and type-agnostic, then the CLONE is
 * dispatched by the cloud's publicationType to the right owning service. A cloud
 * 402 (insufficient funds on the cloud account) surfaces as
 * InsufficientCreditsException and writes NO local receipt / clones nothing -
 * for agents and resources exactly like workflows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RemoteMarketplaceService - acquire dispatch by type + cloud payment")
class RemoteMarketplaceServiceAcquireDispatchTest {

    private static final String CLOUD_API_URL = "https://cloud.example/api";
    private static final UUID PUB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String TENANT = "42";
    private static final String ORG = "org-1";

    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private CloudLinkService cloudLinkService;
    @Mock private AuthClient authClient;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private RestTemplate restTemplate;

    private RemoteMarketplaceService service;

    @BeforeEach
    void setUp() {
        // entitlementGuard = null: mirrors the local acquire path's optional quota guard
        // (absent -> the editable twin is created without a WORKFLOW-quota gate). The
        // quota-denied branch gets its own service instance in the Twin nested class.
        service = new RemoteMarketplaceService(
                CLOUD_API_URL, snapshotCloneService, receiptRepository,
                cloudLinkService, new ObjectMapper(), authClient,
                agentPublicationService, resourcePublicationService, orchestratorClient, null, restTemplate);
        // Default: no existing local clone (fresh acquire) and no prior receipt. The clone
        // guard (existsBySourcePublication) returns false by default; pin the receipt lenient.
        lenient().when(receiptRepository.existsByOrganizationIdAndPublicationId(ORG, PUB)).thenReturn(false);
        // Every WORKFLOW acquire now also mints the decoupled editable twin (#2a parity
        // with the local path); default it to success so the dispatch tests stay focused.
        lenient().when(snapshotCloneService.duplicateToEditableWorkflow(
                        any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                .thenReturn(new HashMap<>(Map.of("workflowId", "twin-1")));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubFreeSnapshot(Map<String, Object> body) {
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn((Map) body);
    }

    private HttpClientErrorException paymentRequired() {
        return HttpClientErrorException.create(
                HttpStatus.PAYMENT_REQUIRED, "Payment Required", new HttpHeaders(), new byte[0], null);
    }

    @Nested
    @DisplayName("free publications (no cloud charge)")
    class Free {

        @Test
        @DisplayName("AGENT → delegates the agent snapshot + creditsPaid=0 to the agent service; no workflow/resource clone")
        void agentDispatch() {
            stubFreeSnapshot(Map.of(
                    "publicationType", "AGENT",
                    "agentSnapshot", Map.of("agents", Map.of()),
                    "title", "Cloud Agent",
                    "creditsPerUse", 0));
            when(agentPublicationService.acquireAgentFromCloudSnapshot(any(), eq(TENANT), eq(PUB), eq(ORG), eq(0)))
                    .thenReturn(new HashMap<>(Map.of("agentId", "a1")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result)
                    .containsEntry("agentId", "a1")
                    .containsEntry("publicationId", PUB.toString())
                    .containsEntry("title", "Cloud Agent");
            verify(agentPublicationService).acquireAgentFromCloudSnapshot(any(), eq(TENANT), eq(PUB), eq(ORG), eq(0));
            verifyNoInteractions(snapshotCloneService);
            verify(resourcePublicationService, never()).acquireResourceFromCloudSnapshot(any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("TABLE → delegates the planSnapshot to the resource service with the parsed type")
        void resourceDispatch() {
            stubFreeSnapshot(Map.of(
                    "publicationType", "TABLE",
                    "planSnapshot", Map.of("name", "My Table"),
                    "title", "Cloud Table",
                    "creditsPerUse", 0));
            when(resourcePublicationService.acquireResourceFromCloudSnapshot(eq(PublicationType.TABLE), any(), eq(TENANT), eq(PUB), eq(ORG), eq(0)))
                    .thenReturn(new HashMap<>(Map.of("resourceId", "r1", "type", "TABLE")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("resourceId", "r1").containsEntry("publicationId", PUB.toString());
            verify(resourcePublicationService).acquireResourceFromCloudSnapshot(eq(PublicationType.TABLE), any(), eq(TENANT), eq(PUB), eq(ORG), eq(0));
            verifyNoInteractions(snapshotCloneService, agentPublicationService);
        }

        @Test
        @DisplayName("WORKFLOW → clones via SnapshotCloneService and writes a remote receipt")
        void workflowDispatch() {
            stubFreeSnapshot(Map.of(
                    "publicationType", "WORKFLOW",
                    "planSnapshot", Map.of("name", "My Flow"),
                    "title", "Cloud Flow",
                    "creditsPerUse", 0));
            when(snapshotCloneService.cloneFromSnapshot(any(), eq(TENANT), eq(PUB), eq("Cloud Flow"), any(), any(), eq(ORG)))
                    .thenReturn(new HashMap<>(Map.of("workflowId", "w1")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "w1").containsEntry("publicationId", PUB.toString());
            verify(receiptRepository).save(any(PublicationReceiptEntity.class));
            verifyNoInteractions(agentPublicationService, resourcePublicationService);
        }

        @Test
        @DisplayName("WORKFLOW receipt is tagged remoteAcquisition=true with the publication, tenant, org scope, and creditsPaid")
        void workflowReceiptTaggedRemoteAcquisition() {
            // creditsPaid travels from the cloud snapshot onto the receipt; the remote flag
            // is what distinguishes a CE-from-cloud acquisition from a local one.
            stubFreeSnapshot(Map.of(
                    "publicationType", "WORKFLOW",
                    "planSnapshot", Map.of("name", "My Flow"),
                    "title", "Cloud Flow",
                    "creditsPaid", 5,
                    "creditsPerUse", 0));
            when(snapshotCloneService.cloneFromSnapshot(any(), eq(TENANT), eq(PUB), eq("Cloud Flow"), any(), any(), eq(ORG)))
                    .thenReturn(new HashMap<>(Map.of("workflowId", "w1")));

            service.acquirePublication(PUB, TENANT, ORG);

            ArgumentCaptor<PublicationReceiptEntity> captor = ArgumentCaptor.forClass(PublicationReceiptEntity.class);
            verify(receiptRepository).save(captor.capture());
            PublicationReceiptEntity saved = captor.getValue();
            assertThat(saved.isRemoteAcquisition())
                    .as("CE-from-cloud acquisition must be tagged remoteAcquisition=true")
                    .isTrue();
            assertThat(saved.getPublicationId()).isEqualTo(PUB);
            assertThat(saved.getTenantId()).isEqualTo(TENANT);
            assertThat(saved.getOrganizationId()).isEqualTo(ORG);
            assertThat(saved.getCreditsPaid()).isEqualTo(5);
        }

        @Test
        @DisplayName("missing publicationType defaults to WORKFLOW (back-compat with an older cloud)")
        void defaultsToWorkflow() {
            stubFreeSnapshot(Map.of("planSnapshot", Map.of("name", "Legacy"), "title", "Legacy"));
            when(snapshotCloneService.cloneFromSnapshot(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new HashMap<>(Map.of("workflowId", "w9")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "w9");
            verifyNoInteractions(agentPublicationService, resourcePublicationService);
        }
    }

    @Nested
    @DisplayName("paid publications charge the linked cloud account")
    class Paid {

        @Test
        @DisplayName("AGENT paid happy path → cloud acquire-with-auth charges, creditsPaid is forwarded to the clone")
        void agentPaidChargesCloud() {
            // /snapshot returns 402 → CE uses the linked cloud token on /acquire-with-auth.
            when(restTemplate.getForObject(anyString(), eq(Map.class))).thenThrow(paymentRequired());
            when(cloudLinkService.getCloudAccessToken(anyLong())).thenReturn("cloud-token");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            "publicationType", "AGENT",
                            "agentSnapshot", Map.of("agents", Map.of()),
                            "title", "Paid Agent",
                            "creditsPaid", 17)));
            when(agentPublicationService.acquireAgentFromCloudSnapshot(any(), eq(TENANT), eq(PUB), eq(ORG), eq(17)))
                    .thenReturn(new HashMap<>(Map.of("agentId", "a2")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("agentId", "a2");
            // The cloud actually charged 17 → that exact amount reaches the clone/receipt.
            verify(agentPublicationService).acquireAgentFromCloudSnapshot(any(), eq(TENANT), eq(PUB), eq(ORG), eq(17));
            verify(cloudLinkService).getCloudAccessToken(anyLong());
        }

        @Test
        @DisplayName("INTERFACE (resource) paid happy path → cloud charges, creditsPaid forwarded to the resource clone")
        void resourcePaidChargesCloud() {
            when(restTemplate.getForObject(anyString(), eq(Map.class))).thenThrow(paymentRequired());
            when(cloudLinkService.getCloudAccessToken(anyLong())).thenReturn("cloud-token");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            "publicationType", "INTERFACE",
                            "planSnapshot", Map.of("htmlTemplate", "<p/>"),
                            "title", "Paid Interface",
                            "creditsPaid", 9)));
            when(resourcePublicationService.acquireResourceFromCloudSnapshot(eq(PublicationType.INTERFACE), any(), eq(TENANT), eq(PUB), eq(ORG), eq(9)))
                    .thenReturn(new HashMap<>(Map.of("resourceId", "r9", "type", "INTERFACE")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("resourceId", "r9");
            // The cloud charged 9 → that exact amount reaches the resource clone/receipt.
            verify(resourcePublicationService).acquireResourceFromCloudSnapshot(eq(PublicationType.INTERFACE), any(), eq(TENANT), eq(PUB), eq(ORG), eq(9));
            verify(cloudLinkService).getCloudAccessToken(anyLong());
        }

        @Test
        @DisplayName("AGENT insufficient cloud credits → InsufficientCreditsException, nothing cloned, no receipt")
        void agentInsufficientCredits() {
            when(restTemplate.getForObject(anyString(), eq(Map.class))).thenThrow(paymentRequired());
            when(cloudLinkService.getCloudAccessToken(anyLong())).thenReturn("cloud-token");
            // acquire-with-auth itself returns 402 → insufficient credits on the cloud account.
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(paymentRequired());

            assertThatThrownBy(() -> service.acquirePublication(PUB, TENANT, ORG))
                    .isInstanceOf(RemoteMarketplaceService.InsufficientCreditsException.class);

            verifyNoInteractions(agentPublicationService, resourcePublicationService, snapshotCloneService);
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("200 /snapshot body carrying error + acquireEndpoint pointer routes to the paid acquire-with-auth flow (no 402 status needed)")
        void inBodyErrorWithAcquirePointerRoutesToPaidFlow() {
            // Older/alternate cloud signalling: GET returns HTTP 200 but a body with
            // {error, acquireEndpoint} instead of a 402 status. fetchFromCloud must still
            // detect this as a paid publication and charge via acquire-with-auth.
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("error", "Payment required");
            errorBody.put("acquireEndpoint", "/api/ce-marketplace/" + PUB + "/acquire-with-auth");
            stubFreeSnapshot(errorBody);
            when(cloudLinkService.getCloudAccessToken(anyLong())).thenReturn("cloud-token");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            "publicationType", "WORKFLOW",
                            "planSnapshot", Map.of("name", "Paid Flow"),
                            "title", "Paid Flow",
                            "creditsPaid", 4)));
            when(snapshotCloneService.cloneFromSnapshot(any(), eq(TENANT), eq(PUB), eq("Paid Flow"), any(), any(), eq(ORG)))
                    .thenReturn(new HashMap<>(Map.of("workflowId", "wPaid")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "wPaid");
            // The in-body error pointer (not a 402 status) still triggered the cloud charge.
            verify(cloudLinkService).getCloudAccessToken(anyLong());
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("200 /snapshot body with a bare error (no acquire pointer / creditsPerUse) fails fast and never charges the cloud")
        void inBodyErrorWithoutAcquirePointerDoesNotChargeCloud() {
            // A generic cloud error (not a payment signal) must surface as a failure
            // WITHOUT routing into the paid acquire-with-auth flow.
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("error", "Publication unavailable");
            stubFreeSnapshot(errorBody);

            assertThatThrownBy(() -> service.acquirePublication(PUB, TENANT, ORG))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Publication unavailable");

            verifyNoInteractions(cloudLinkService);
            verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
            verifyNoInteractions(agentPublicationService, resourcePublicationService, snapshotCloneService);
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("TABLE insufficient cloud credits → InsufficientCreditsException (resource paid path also charges cloud)")
        void resourceInsufficientCredits() {
            when(restTemplate.getForObject(anyString(), eq(Map.class))).thenThrow(paymentRequired());
            when(cloudLinkService.getCloudAccessToken(anyLong())).thenReturn("cloud-token");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(paymentRequired());

            assertThatThrownBy(() -> service.acquirePublication(PUB, TENANT, ORG))
                    .isInstanceOf(RemoteMarketplaceService.InsufficientCreditsException.class);

            verifyNoInteractions(agentPublicationService, resourcePublicationService, snapshotCloneService);
            verify(receiptRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("already INSTALLED (local clone exists) → rejected 'already acquired' before any cloud fetch")
    void alreadyInstalled() {
        // The guard now keys on the local CLONE (matching the local acquire path), not the
        // receipt: a still-installed app is rejected; a deleted one becomes a reinstall.
        when(orchestratorClient.existsBySourcePublication(PUB, TENANT, ORG)).thenReturn(true);

        assertThatThrownBy(() -> service.acquirePublication(PUB, TENANT, ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already acquired");

        verifyNoInteractions(restTemplate, agentPublicationService, resourcePublicationService, snapshotCloneService);
    }

    @Nested
    @DisplayName("reinstall (receipt held, local clone deleted)")
    class Reinstall {

        @Test
        @DisplayName("FREE workflow → re-clones from the snapshot WITHOUT writing a new receipt or re-charging")
        void reinstallFreeWorkflowReclonesNoNewReceipt() {
            // clone gone (default false) + receipt held => reinstall.
            when(receiptRepository.existsByOrganizationIdAndPublicationId(ORG, PUB)).thenReturn(true);
            stubFreeSnapshot(Map.of(
                    "publicationType", "WORKFLOW",
                    "planSnapshot", Map.of("name", "My Flow"),
                    "title", "Cloud Flow",
                    "creditsPerUse", 0));
            when(snapshotCloneService.cloneFromSnapshot(any(), eq(TENANT), eq(PUB), eq("Cloud Flow"), any(), any(), eq(ORG)))
                    .thenReturn(new HashMap<>(Map.of("workflowId", "w-reinstalled")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "w-reinstalled").containsEntry("publicationId", PUB.toString());
            // Reinstall reuses the existing entitlement: no duplicate receipt, no cloud charge.
            verify(receiptRepository, never()).save(any());
            verifyNoInteractions(cloudLinkService);
        }

        @Test
        @DisplayName("PAID workflow → rejected with a clear non-charging error (never calls acquire-with-auth)")
        void reinstallPaidWorkflowRejectedNoCharge() {
            when(receiptRepository.existsByOrganizationIdAndPublicationId(ORG, PUB)).thenReturn(true);
            // /snapshot returns 402 for a paid pub; the snapshot-only reinstall fetch must NOT
            // fall through to acquire-with-auth (which would charge the cloud account again).
            when(restTemplate.getForObject(anyString(), eq(Map.class))).thenThrow(paymentRequired());

            assertThatThrownBy(() -> service.acquirePublication(PUB, TENANT, ORG))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("paid publication is not yet supported");

            verifyNoInteractions(cloudLinkService, snapshotCloneService, agentPublicationService, resourcePublicationService);
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-WORKFLOW (agent/resource) → reinstall not yet supported, rejected without cloning")
        void reinstallNonWorkflowRejected() {
            when(receiptRepository.existsByOrganizationIdAndPublicationId(ORG, PUB)).thenReturn(true);
            stubFreeSnapshot(Map.of(
                    "publicationType", "TABLE",
                    "planSnapshot", Map.of("name", "My Table"),
                    "title", "Cloud Table",
                    "creditsPerUse", 0));

            assertThatThrownBy(() -> service.acquirePublication(PUB, TENANT, ORG))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not yet supported");

            verifyNoInteractions(resourcePublicationService, agentPublicationService, snapshotCloneService);
            verify(receiptRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("editable WORKFLOW twin (#2a parity with the local acquire path)")
    class EditableTwin {

        private static final String TWIN_ROW_1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        private static final String TWIN_ROW_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

        private void stubWorkflowSnapshot() {
            stubFreeSnapshot(Map.of(
                    "publicationType", "WORKFLOW",
                    "planSnapshot", Map.of("name", "My Flow"),
                    "title", "Cloud Flow",
                    "description", "A flow",
                    "creditsPerUse", 0));
            when(snapshotCloneService.cloneFromSnapshot(any(), eq(TENANT), eq(PUB), eq("Cloud Flow"), any(), any(), eq(ORG)))
                    .thenReturn(new HashMap<>(Map.of("workflowId", "w1")));
        }

        @Test
        @DisplayName("WORKFLOW acquire ALSO mints the decoupled editable twin (file namespace = publication id, lineage = application clone id)")
        void workflowAcquireCreatesTwin() {
            stubWorkflowSnapshot();

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "w1");
            // fileNamespaceId = the cloud publication id (the snapshot's file refs live
            // under _publications/{pub}/); lineage = the application clone's workflow id.
            ArgumentCaptor<Map<String, Object>> twinPlan = ArgumentCaptor.captor();
            verify(snapshotCloneService).duplicateToEditableWorkflow(
                    twinPlan.capture(), eq(TENANT), eq(ORG), eq("Cloud Flow"), eq("A flow"), any(), eq(PUB), eq("w1"));
            // The twin clones the SAME extracted planSnapshot the application clone used -
            // not the whole cloud response envelope.
            ArgumentCaptor<Map<String, Object>> appPlan = ArgumentCaptor.captor();
            verify(snapshotCloneService).cloneFromSnapshot(
                    appPlan.capture(), eq(TENANT), eq(PUB), eq("Cloud Flow"), any(), any(), eq(ORG));
            assertThat(twinPlan.getValue()).isSameAs(appPlan.getValue());
            assertThat(twinPlan.getValue()).containsEntry("name", "My Flow");
        }

        @Test
        @DisplayName("guard present + quota OK → the twin mints and the quota supplier counts WORKFLOWS by org")
        void guardPresentQuotaOkMintsTwin() {
            EntitlementGuard guard = mock(EntitlementGuard.class);
            // Let check() actually consult its supplier so the counter wiring is exercised.
            doAnswer(inv -> {
                ((LongSupplier) inv.getArgument(2)).getAsLong();
                return null;
            }).when(guard).check(eq(TENANT), eq(ResourceType.WORKFLOW), any());
            when(orchestratorClient.countWorkflowsByOrg(ORG)).thenReturn(3L);
            RemoteMarketplaceService guarded = new RemoteMarketplaceService(
                    CLOUD_API_URL, snapshotCloneService, receiptRepository,
                    cloudLinkService, new ObjectMapper(), authClient,
                    agentPublicationService, resourcePublicationService, orchestratorClient, guard, restTemplate);
            stubWorkflowSnapshot();

            Map<String, Object> result = guarded.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "w1");
            verify(orchestratorClient).countWorkflowsByOrg(ORG);
            verify(snapshotCloneService).duplicateToEditableWorkflow(
                    any(), eq(TENANT), eq(ORG), eq("Cloud Flow"), eq("A flow"), any(), eq(PUB), eq("w1"));
        }

        @Test
        @DisplayName("AGENT / TABLE acquires never mint a twin (application-backed workflows only)")
        void nonWorkflowTypesNeverMintTwin() {
            stubFreeSnapshot(Map.of(
                    "publicationType", "TABLE",
                    "planSnapshot", Map.of("name", "My Table"),
                    "title", "Cloud Table",
                    "creditsPerUse", 0));
            when(resourcePublicationService.acquireResourceFromCloudSnapshot(eq(PublicationType.TABLE), any(), eq(TENANT), eq(PUB), eq(ORG), eq(0)))
                    .thenReturn(new HashMap<>(Map.of("resourceId", "r1", "type", "TABLE")));

            service.acquirePublication(PUB, TENANT, ORG);

            verify(snapshotCloneService, never()).duplicateToEditableWorkflow(
                    any(), anyString(), any(), any(), any(), any(), any(UUID.class), any());
        }

        @Test
        @DisplayName("twin clone failure (AcquireCloneFailedException) → acquire still succeeds; ONLY the twin's own rows are compensated")
        void twinFailureCompensatedAcquireUnaffected() {
            stubWorkflowSnapshot();
            when(snapshotCloneService.duplicateToEditableWorkflow(
                            any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                    .thenThrow(new AcquireCloneFailedException(Set.of(TWIN_ROW_1, TWIN_ROW_2), new RuntimeException("clone died")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            // The acquire is untouched: result + receipt as if the twin never existed.
            assertThat(result).containsEntry("workflowId", "w1");
            verify(receiptRepository).save(any(PublicationReceiptEntity.class));
            // Compensation deletes EXACTLY the rows the failed twin created.
            verify(orchestratorClient).deleteDecoupledDuplicateWorkflow(UUID.fromString(TWIN_ROW_1), TENANT, ORG);
            verify(orchestratorClient).deleteDecoupledDuplicateWorkflow(UUID.fromString(TWIN_ROW_2), TENANT, ORG);
        }

        @Test
        @DisplayName("twin generic error → acquire still succeeds, nothing to compensate")
        void twinGenericErrorSwallowed() {
            stubWorkflowSnapshot();
            when(snapshotCloneService.duplicateToEditableWorkflow(
                            any(), anyString(), any(), any(), any(), any(), any(UUID.class), any()))
                    .thenThrow(new IllegalStateException("orchestrator down"));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "w1");
            verify(orchestratorClient, never()).deleteDecoupledDuplicateWorkflow(any(UUID.class), anyString(), any());
        }

        @Test
        @DisplayName("WORKFLOW quota reached → the twin is skipped, the application acquire is unaffected")
        void quotaDeniedSkipsTwinKeepsAcquire() {
            EntitlementGuard guard = mock(EntitlementGuard.class);
            RemoteMarketplaceService guarded = new RemoteMarketplaceService(
                    CLOUD_API_URL, snapshotCloneService, receiptRepository,
                    cloudLinkService, new ObjectMapper(), authClient,
                    agentPublicationService, resourcePublicationService, orchestratorClient, guard, restTemplate);
            doThrow(new RuntimeException("WORKFLOW quota exceeded"))
                    .when(guard).check(eq(TENANT), eq(ResourceType.WORKFLOW), any());
            stubWorkflowSnapshot();

            Map<String, Object> result = guarded.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "w1");
            verify(receiptRepository).save(any(PublicationReceiptEntity.class));
            verify(snapshotCloneService, never()).duplicateToEditableWorkflow(
                    any(), anyString(), any(), any(), any(), any(), any(UUID.class), any());
        }

        @Test
        @DisplayName("reinstall (receipt held, clone deleted) mints a fresh twin too - parity with the local path")
        void reinstallMintsTwinToo() {
            when(receiptRepository.existsByOrganizationIdAndPublicationId(ORG, PUB)).thenReturn(true);
            stubWorkflowSnapshot();

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, ORG);

            assertThat(result).containsEntry("workflowId", "w1");
            verify(snapshotCloneService).duplicateToEditableWorkflow(
                    any(), eq(TENANT), eq(ORG), eq("Cloud Flow"), eq("A flow"), any(), eq(PUB), eq("w1"));
            // Reinstall still writes no duplicate receipt.
            verify(receiptRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("V261 organizationId resolution when the request omitted X-Organization-ID")
    class OrgResolution {

        private static final String RESOLVED_ORG = "default-org-7";

        @Test
        @DisplayName("null organizationId → falls back to the user's default org from authClient, which then scopes the clone + receipt")
        void nullOrgResolvesFromAuthClientDefaultOrg() {
            // No org on the request → resolveAcquirerOrg must consult the user's local default-personal org.
            when(authClient.getDefaultOrganizationIdForUser(TENANT)).thenReturn(RESOLVED_ORG);
            when(receiptRepository.existsByOrganizationIdAndPublicationId(RESOLVED_ORG, PUB)).thenReturn(false);
            stubFreeSnapshot(Map.of(
                    "publicationType", "WORKFLOW",
                    "planSnapshot", Map.of("name", "My Flow"),
                    "title", "Cloud Flow",
                    "creditsPerUse", 0));
            when(snapshotCloneService.cloneFromSnapshot(any(), eq(TENANT), eq(PUB), eq("Cloud Flow"), any(), any(), eq(RESOLVED_ORG)))
                    .thenReturn(new HashMap<>(Map.of("workflowId", "w1")));

            Map<String, Object> result = service.acquirePublication(PUB, TENANT, null);

            assertThat(result).containsEntry("workflowId", "w1");
            // The fallback was consulted exactly because the caller passed null.
            verify(authClient).getDefaultOrganizationIdForUser(TENANT);
            // The RESOLVED org (not null) is what scopes the dedup check + the clone.
            verify(receiptRepository).existsByOrganizationIdAndPublicationId(RESOLVED_ORG, PUB);
            verify(snapshotCloneService).cloneFromSnapshot(any(), eq(TENANT), eq(PUB), eq("Cloud Flow"), any(), any(), eq(RESOLVED_ORG));
        }

        @Test
        @DisplayName("null organizationId + user has no default org → IllegalArgumentException before any cloud fetch, nothing cloned")
        void nullOrgWithNoDefaultOrgThrowsBeforeCloud() {
            // Caller omitted the org AND the user has no default-personal org → degenerate state.
            when(authClient.getDefaultOrganizationIdForUser(TENANT)).thenReturn(null);

            assertThatThrownBy(() -> service.acquirePublication(PUB, TENANT, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no default organization");

            verify(authClient).getDefaultOrganizationIdForUser(TENANT);
            // The throw is fail-fast: no cloud fetch, no dedup lookup, no clone, no receipt.
            verifyNoInteractions(restTemplate, agentPublicationService, resourcePublicationService, snapshotCloneService);
            verify(receiptRepository, never()).existsByOrganizationIdAndPublicationId(anyString(), any(UUID.class));
            verify(receiptRepository, never()).save(any());
        }
    }
}
