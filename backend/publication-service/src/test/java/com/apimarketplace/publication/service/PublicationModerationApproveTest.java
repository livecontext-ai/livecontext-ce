package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationModerationService.approvePublication - followers")
class PublicationModerationApproveTest {

    private static final UUID PUB_ID = UUID.randomUUID();

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private WorkflowPublicationService workflowPublicationService;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private CreatorFollowNotifier creatorFollowNotifier;

    private PublicationModerationService service;

    @BeforeEach
    void setUp() {
        service = new PublicationModerationService(publicationRepository, orchestratorClient, agentClient,
                interfaceClient, dataSourceClient, workflowPublicationService, landingInterfaceSnapshotter,
                List.of(), creatorFollowNotifier);
    }

    @Test
    @DisplayName("approving hands the ACTIVE listing to the follower notifier BEFORE saving, so its stamp is persisted")
    void approveNotifiesFollowersBeforeSave() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUB_ID);
        pub.setStatus(PublicationStatus.PENDING_REVIEW);
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            assertThat(((WorkflowPublicationEntity) inv.getArgument(0)).getStatus())
                    .isEqualTo(PublicationStatus.ACTIVE);
            return null;
        }).when(creatorFollowNotifier).onApproved(pub);

        service.approvePublication(PUB_ID, "admin-1");

        InOrder order = inOrder(creatorFollowNotifier, publicationRepository);
        order.verify(creatorFollowNotifier).onApproved(pub);
        order.verify(publicationRepository).save(pub);
    }

    @Test
    @DisplayName("a publication that is not pending review is refused and nobody is notified")
    void notPendingNotifiesNobody() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUB_ID);
        pub.setStatus(PublicationStatus.ACTIVE);
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

        assertThatThrownBy(() -> service.approvePublication(PUB_ID, "admin-1"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(creatorFollowNotifier);
    }

    @Test
    @DisplayName("rejecting a listing never notifies followers")
    void rejectNotifiesNobody() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUB_ID);
        pub.setStatus(PublicationStatus.PENDING_REVIEW);
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rejectPublication(PUB_ID, "admin-1", "nope");

        verifyNoInteractions(creatorFollowNotifier);
    }
}
