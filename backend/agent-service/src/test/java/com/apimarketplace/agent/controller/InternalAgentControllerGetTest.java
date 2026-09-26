package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.service.execution.AgentActivitySnapshotService;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.execution.ConversationStopCascadeService;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.agent.repository.AgentWebhookTokenRepository;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * GET /api/internal/agents/{id}/get: what the orchestrator knows about an agent.
 *
 * <p>This is the ONLY way the orchestrator reads an agent, so every per-agent setting that decides
 * something at delivery time has to be copied into the DTO here. The chat destination (V523) was
 * not: saved, shown back in the modal, and never used by a single delivery, because every test on
 * the orchestrator side built its AgentDto by hand. These go through the real mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAgentController - the agent the orchestrator sees")
class InternalAgentControllerGetTest {

    private static final UUID AGENT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID LINK_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Mock private AgentService agentService;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository agentExecutionRepository;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentWebhookTokenRepository webhookTokenRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private SkillService skillService;
    @Mock private SkillFolderService skillFolderService;
    @Mock private AgentObservabilityService observabilityService;
    @Mock private TenantResolver tenantResolver;
    @Mock private RequestParameterExtractor extractor;
    @Mock private ConversationStopCascadeService conversationStopCascadeService;
    @Mock private AgentActivitySnapshotService agentActivitySnapshotService;

    private InternalAgentController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAgentController(
                agentService, agentRepository, agentExecutionRepository, orgAccessService, agentSkillRepository,
                webhookTokenRepository, skillRepository, skillService, skillFolderService,
                observabilityService, tenantResolver, extractor, conversationStopCascadeService,
                agentActivitySnapshotService);
    }

    private AgentDto get(AgentEntity entity) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Organization-ID", "org-1");
        when(tenantResolver.resolve(any())).thenReturn("user-1");
        when(agentService.getAgent(AGENT_ID, "user-1", "org-1", null)).thenReturn(Optional.of(entity));
        return controller.getAgentInternal(AGENT_ID, request).getBody();
    }

    private static AgentEntity agent() {
        AgentEntity entity = new AgentEntity();
        entity.setId(AGENT_ID);
        entity.setName("Finance");
        return entity;
    }

    @Test
    @DisplayName("carries the agent's chosen chat destination, so its requests go there")
    void carriesChatDestination() {
        AgentEntity entity = agent();
        entity.setChatChannelLinkId(LINK_ID);

        AgentDto dto = get(entity);

        assertThat(dto.getChatChannelLinkId()).isEqualTo(LINK_ID);
        assertThat(dto.getName()).isEqualTo("Finance");
    }

    @Test
    @DisplayName("no choice stays no choice (the workspace default), not an empty id")
    void noChoiceIsNull() {
        assertThat(get(agent()).getChatChannelLinkId()).isNull();
    }

    @Test
    @DisplayName("carries whether the agent reaches the person outside the app at all (V524)")
    void carriesChannelSwitch() {
        AgentEntity off = agent();
        off.setChatChannelEnabled(false);

        assertThat(get(off).getChatChannelEnabled()).isFalse();
    }

    @Test
    @DisplayName("an agent never switched is carried as on")
    void defaultSwitchIsOn() {
        assertThat(get(agent()).getChatChannelEnabled()).isTrue();
    }

    @Test
    @DisplayName("carries whether the agent asks permission for sensitive actions")
    void carriesToolAuthorization() {
        AgentEntity entity = agent();
        entity.setRequireToolAuthorization(true);

        assertThat(get(entity).getRequireToolAuthorization()).isTrue();
    }
}
