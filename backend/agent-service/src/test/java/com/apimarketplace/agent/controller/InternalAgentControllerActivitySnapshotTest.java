package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.AgentWebhookTokenRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.service.execution.AgentActivitySnapshotService;
import com.apimarketplace.agent.service.execution.ConversationStopCascadeService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link InternalAgentController#activitySnapshot(UUID)} - the WS late-subscribe
 * replay endpoint. The controller is a thin delegate to {@link AgentActivitySnapshotService}
 * (the replay logic is shared with the CE monolith WS path and unit-tested in
 * {@code AgentActivitySnapshotServiceTest}); this only pins the endpoint wiring.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAgentController - POST /{id}/activity-snapshot")
class InternalAgentControllerActivitySnapshotTest {

    @Mock private AgentService agentService;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentWebhookTokenRepository webhookTokenRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private SkillService skillService;
    @Mock private SkillFolderService skillFolderService;
    @Mock private AgentObservabilityService observabilityService;
    @Mock private TenantResolver tenantResolver;
    @Mock private ConversationStopCascadeService conversationStopCascadeService;
    @Mock private AgentActivitySnapshotService agentActivitySnapshotService;

    private InternalAgentController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAgentController(
                agentService, agentRepository, agentSkillRepository, webhookTokenRepository,
                skillRepository, skillService, skillFolderService, observabilityService,
                tenantResolver, new RequestParameterExtractor(), conversationStopCascadeService,
                agentActivitySnapshotService);
    }

    @Test
    @DisplayName("delegates to the snapshot service for the path agent id and returns 200")
    void delegatesToSnapshotService() {
        UUID agentId = UUID.randomUUID();

        ResponseEntity<Void> resp = controller.activitySnapshot(agentId);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(agentActivitySnapshotService).publishRunningSnapshot(agentId);
    }
}
