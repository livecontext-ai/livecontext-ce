package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceCreateRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the ACQUIRE half of the interface FORMAT contract for agent publications.
 *
 * An interface's HTML is authored for one fixed viewport width, so its shape must travel with its
 * templates. This is the one clone path that does not go through PlanSnapshotSanitizer, so nothing
 * else guards it: drop the format here and acquiring an agent that grants a vertical interface
 * silently yields a full-page 1280x800 copy, with no error anywhere.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentPublicationService - acquire restores the published interface format")
class AgentPublicationServiceInterfaceFormatTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private WorkflowPublicationService workflowPublicationService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private DataSourceFileCloneService fileCloneService;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private AuthClient authClient;

    private AgentPublicationService service;

    private static final UUID PUBLICATION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String PUBLISHER = "publisher-1";
    private static final String BUYER = "buyer-7";
    private static final String BUYER_ORG = "org-buyer";
    private static final String OLD_AGENT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OLD_IFACE_ID = "bbbbbbbb-0000-0000-0000-000000000002";

    @BeforeEach
    void setUp() {
        service = new AgentPublicationService(
                publicationRepository, receiptRepository, agentClient, interfaceClient,
                dataSourceClient, orchestratorClient, breakdownService, snapshotCloneService,
                new ObjectMapper(), workflowPublicationService, entitlementGuard,
                fileCloneService, landingInterfaceSnapshotter, authClient);
    }

    /** Agent snapshot granting one standalone interface, as the publish step writes it. */
    private Map<String, Object> snapshotGrantingInterface(String format) {
        Map<String, Object> ifSnapshot = new HashMap<>();
        ifSnapshot.put("name", "Story Card");
        ifSnapshot.put("description", "A vertical story");
        ifSnapshot.put("htmlTemplate", "<div>story</div>");
        ifSnapshot.put("cssTemplate", ".s {}");
        ifSnapshot.put("jsTemplate", "");
        ifSnapshot.put("interfaceType", "html");
        ifSnapshot.put("format", format);

        Map<String, Object> agent = new HashMap<>();
        agent.put("id", OLD_AGENT_ID);
        agent.put("name", "Story Agent");

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("agent", agent);
        snapshot.put("interfaces", Map.of(OLD_IFACE_ID, ifSnapshot));
        return snapshot;
    }

    private WorkflowPublicationEntity agentPublication(Map<String, Object> agentSnapshot) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setPublisherId(PUBLISHER);
        publication.setPublicationType(PublicationType.AGENT);
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setAgentSnapshot(agentSnapshot);
        return publication;
    }

    /**
     * Captures the create request the acquire builds from the snapshot. The root-agent clone is
     * left failing on purpose: the interface is cloned first, so the request is already captured
     * by then and the test needs no further agent plumbing.
     */
    private InterfaceCreateRequest acquireAndCaptureInterfaceRequest(Map<String, Object> agentSnapshot) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(agentPublication(agentSnapshot)));
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);

        InterfaceDto cloned = new InterfaceDto();
        cloned.setId(UUID.fromString("cccccccc-0000-0000-0000-000000000003"));
        ArgumentCaptor<InterfaceCreateRequest> captor = ArgumentCaptor.forClass(InterfaceCreateRequest.class);
        when(interfaceClient.createInterface(captor.capture(), eq(BUYER))).thenReturn(cloned);

        when(agentClient.cloneFromSnapshot(any())).thenReturn(null);
        lenient().when(orchestratorClient.findAllBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.acquireAgentPublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(RuntimeException.class);

        return captor.getValue();
    }

    @Test
    @DisplayName("A granted interface is re-created with the published format")
    void acquireRestoresTheFormat() {
        InterfaceCreateRequest req = acquireAndCaptureInterfaceRequest(snapshotGrantingInterface("vertical"));

        assertThat(req.getFormat())
                .as("the acquired copy must keep the shape its HTML was authored for")
                .isEqualTo("vertical");
    }

    @Test
    @DisplayName("An interface published with no format stays unset (full-page), never defaulted")
    void acquireKeepsUnsetFormatUnset() {
        // Null is a real value here: it is what keeps the screenshot a full-page capture.
        InterfaceCreateRequest req = acquireAndCaptureInterfaceRequest(snapshotGrantingInterface(null));

        assertThat(req.getFormat()).isNull();
    }

    @Test
    @DisplayName("A non-String format in the snapshot fails the acquire, it is never stringified in")
    void acquireRejectsANonStringFormat() {
        // The format goes through the same content guard as the templates it travels with
        // (asContentText): a numeric value must not be .toString()'d into the shape column.
        // Failing loudly beats storing "1080", which resolves to no shape and would look like
        // the interface simply has none.
        Map<String, Object> snapshot = snapshotGrantingInterface(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> ifSnapshot =
                (Map<String, Object>) ((Map<String, Object>) snapshot.get("interfaces")).get(OLD_IFACE_ID);
        ifSnapshot.put("format", 1080);

        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(agentPublication(snapshot)));
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        lenient().when(orchestratorClient.findAllBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.acquireAgentPublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .hasMessageContaining("must be text");
    }
}
