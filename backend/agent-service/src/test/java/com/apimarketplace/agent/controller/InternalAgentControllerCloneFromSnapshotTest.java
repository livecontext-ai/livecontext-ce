package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentSkillEntity;
import com.apimarketplace.agent.domain.SkillEntity;
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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAgentController clone-from-snapshot contract")
class InternalAgentControllerCloneFromSnapshotTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "22222222-2222-4222-8222-222222222222";

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
    @Mock private HttpServletRequest request;

    private InternalAgentController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAgentController(
            agentService,
            agentRepository,
            agentSkillRepository,
            webhookTokenRepository,
            skillRepository,
            skillService,
            skillFolderService,
            observabilityService,
            tenantResolver,
            new RequestParameterExtractor(),
            conversationStopCascadeService,
            agentActivitySnapshotService
        );
    }

    @Test
    @DisplayName("cloneFromSnapshot stamps organization scope on cloned agent and skills")
    void cloneFromSnapshotStampsOrganizationScopeOnAgentAndSkills() {
        UUID publicationId = UUID.randomUUID();
        UUID clonedAgentId = UUID.randomUUID();
        UUID clonedSkillId = UUID.randomUUID();

        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity entity = invocation.getArgument(0);
            entity.setId(clonedAgentId);
            return entity;
        });
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(invocation -> {
            SkillEntity entity = invocation.getArgument(0);
            entity.setId(clonedSkillId);
            return entity;
        });
        when(agentSkillRepository.save(any(AgentSkillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> cloneRequest = Map.of(
            "tenantId", TENANT_ID,
            "organizationId", ORG_ID,
            "publicationId", publicationId.toString(),
            "name", "Acquired Agent",
            "description", "Acquired in org scope",
            "systemPrompt", "Use the provided tools.",
            "modelProvider", "openai",
            "modelName", "gpt-4o-mini",
            "toolsConfig", Map.of(),
            "skills", List.of(Map.of(
                "name", "Acquired Skill",
                "description", "Skill description",
                "instructions", "Follow instructions"
            ))
        );

        ResponseEntity<Map<String, Object>> response = controller.cloneFromSnapshot(cloneRequest, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("agentId", clonedAgentId.toString());

        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        assertThat(agentCaptor.getValue().getOrganizationId()).isEqualTo(ORG_ID);

        ArgumentCaptor<SkillEntity> skillCaptor = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository).save(skillCaptor.capture());
        assertThat(skillCaptor.getValue().getOrganizationId()).isEqualTo(ORG_ID);
    }

    @Test
    @DisplayName("createAgentInternal rejects a numeric systemPrompt via getText (no silent .toString() corruption) - agentService is never invoked")
    void createAgentInternalRejectsNumericSystemPrompt() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "Probe");
        body.put("description", "Probe agent");
        // Pre-fix getString().toString() stored "97559" into system_prompt; getText must reject it.
        body.put("systemPrompt", 97559);

        assertThatThrownBy(() -> controller.createAgentInternal(body, request))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(agentService);
    }

    @Test
    @DisplayName("updateAgentInternal rejects a numeric systemPrompt via getText (no silent .toString() corruption) - agentService is never invoked")
    void updateAgentInternalRejectsNumericSystemPrompt() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "Probe");
        body.put("description", "Probe agent");
        body.put("systemPrompt", 97559);

        assertThatThrownBy(() -> controller.updateAgentInternal(UUID.randomUUID(), body, request, ORG_ID))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(agentService);
    }

    @Test
    @DisplayName("createAgentInternal rejects a numeric name via getText (the content-field guard is not systemPrompt-only)")
    void createAgentInternalRejectsNumericName() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", 358361); // numeric content must be rejected, not stringified
        body.put("description", "Probe agent");
        body.put("systemPrompt", "You are helpful.");

        assertThatThrownBy(() -> controller.createAgentInternal(body, request))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(agentService);
    }

    @Test
    @DisplayName("cloneFromSnapshot applies loop-guard, compaction-model, and reasoning-effort overrides (M1/M3)")
    void cloneFromSnapshotAppliesLoopCompactionReasoningOverrides() {
        UUID publicationId = UUID.randomUUID();
        UUID clonedAgentId = UUID.randomUUID();
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity entity = invocation.getArgument(0);
            entity.setId(clonedAgentId);
            return entity;
        });

        java.util.Map<String, Object> cloneRequest = new java.util.HashMap<>();
        cloneRequest.put("tenantId", TENANT_ID);
        cloneRequest.put("organizationId", ORG_ID);
        cloneRequest.put("publicationId", publicationId.toString());
        cloneRequest.put("name", "Tuned Agent");
        cloneRequest.put("modelProvider", "openai");
        cloneRequest.put("modelName", "gpt-4o-mini");
        cloneRequest.put("toolsConfig", Map.of());
        cloneRequest.put("maxPerResourcePerTurn", 3);
        cloneRequest.put("loopIdenticalStop", 2);
        cloneRequest.put("loopConsecutiveStop", 4);
        cloneRequest.put("compactionModelProvider", "anthropic");
        cloneRequest.put("compactionModelName", "claude-haiku-4-5");
        cloneRequest.put("reasoningEffort", "high");

        ResponseEntity<Map<String, Object>> response = controller.cloneFromSnapshot(cloneRequest, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        AgentEntity saved = agentCaptor.getValue();
        assertThat(saved.getMaxPerResourcePerTurn()).as("loop per-resource cap applied").isEqualTo(3);
        assertThat(saved.getLoopIdenticalStop()).as("loop identical-stop applied").isEqualTo(2);
        assertThat(saved.getLoopConsecutiveStop()).as("loop consecutive-stop applied").isEqualTo(4);
        assertThat(saved.getCompactionModelProvider()).as("COLD-summariser provider applied").isEqualTo("anthropic");
        assertThat(saved.getCompactionModelName()).as("COLD-summariser model applied").isEqualTo("claude-haiku-4-5");
        assertThat(saved.getReasoningEffort()).as("reasoning-effort applied").isEqualTo("high");
    }

    @Test
    @DisplayName("cloneFromSnapshot preserves the publisher's inactivityTimeout (V372)")
    void cloneFromSnapshotPreservesInactivityTimeout() {
        UUID publicationId = UUID.randomUUID();
        UUID clonedAgentId = UUID.randomUUID();
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity entity = invocation.getArgument(0);
            entity.setId(clonedAgentId);
            return entity;
        });

        java.util.Map<String, Object> cloneRequest = new java.util.HashMap<>();
        cloneRequest.put("tenantId", TENANT_ID);
        cloneRequest.put("organizationId", ORG_ID);
        cloneRequest.put("publicationId", publicationId.toString());
        cloneRequest.put("name", "Watched Agent");
        cloneRequest.put("modelProvider", "openai");
        cloneRequest.put("modelName", "gpt-4o-mini");
        cloneRequest.put("toolsConfig", Map.of());
        cloneRequest.put("executionTimeout", 1800);
        cloneRequest.put("inactivityTimeout", 600);

        controller.cloneFromSnapshot(cloneRequest, request);

        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        AgentEntity saved = agentCaptor.getValue();
        // Regression: the producer puts the value into the clone request, but it is lost
        // unless cloneFromSnapshot reads it back (pre-fix it did not, reverting to the 300s
        // default while the sibling executionTimeout was preserved).
        assertThat(saved.getInactivityTimeout()).as("custom inactivity window preserved on clone").isEqualTo(600);
        assertThat(saved.getExecutionTimeout()).as("sibling executionTimeout still preserved").isEqualTo(1800);
    }

    @Test
    @DisplayName("cloneFromSnapshot preserves the 0 (disabled) inactivity sentinel, not the default")
    void cloneFromSnapshotPreservesDisabledInactivity() {
        UUID clonedAgentId = UUID.randomUUID();
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(invocation -> {
            AgentEntity entity = invocation.getArgument(0);
            entity.setId(clonedAgentId);
            return entity;
        });

        java.util.Map<String, Object> cloneRequest = new java.util.HashMap<>();
        cloneRequest.put("tenantId", TENANT_ID);
        cloneRequest.put("organizationId", ORG_ID);
        cloneRequest.put("publicationId", UUID.randomUUID().toString());
        cloneRequest.put("name", "Unwatched Agent");
        cloneRequest.put("modelProvider", "openai");
        cloneRequest.put("modelName", "gpt-4o-mini");
        cloneRequest.put("toolsConfig", Map.of());
        cloneRequest.put("inactivityTimeout", 0);

        controller.cloneFromSnapshot(cloneRequest, request);

        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        // 0 must survive verbatim: a clone of a watchdog-disabled agent stays disabled.
        assertThat(agentCaptor.getValue().getInactivityTimeout()).isEqualTo(0);
    }
}
