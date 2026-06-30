package com.apimarketplace.publication.service.resource;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.SkillDto;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy.ResourceMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SkillResourceStrategy")
class SkillResourceStrategyTest {

    private static final String OWNER = "publisher-1";
    private static final String ACQUIRER = "acquirer-1";
    private static final String ACQUIRER_ORG = "org-acquirer-1";

    private AgentClient agentClient;
    private SkillResourceStrategy strategy;

    @BeforeEach
    void setUp() {
        agentClient = mock(AgentClient.class);
        strategy = new SkillResourceStrategy(agentClient);
    }

    @Test
    @DisplayName("Reports PublicationType.SKILL and DisplayMode.SKILL")
    void reportsTypeAndDisplayMode() {
        assertThat(strategy.getPublicationType()).isEqualTo(PublicationType.SKILL);
        assertThat(strategy.getDisplayMode()).isEqualTo(DisplayMode.SKILL);
    }

    @Test
    @DisplayName("fetchOwnedResource returns name and description from the skill")
    void fetchOwnedResourceReturnsMetadata() {
        UUID skillId = UUID.randomUUID();
        SkillDto skill = skill(skillId, OWNER, "Cold Email", "Closes a deal", "mail", "Use templates.");
        when(agentClient.getSkill(skillId, OWNER)).thenReturn(skill);

        ResourceMetadata meta = strategy.fetchOwnedResource(skillId.toString(), OWNER);

        assertThat(meta.name()).isEqualTo("Cold Email");
        assertThat(meta.description()).isEqualTo("Closes a deal");
    }

    @Test
    @DisplayName("fetchOwnedResource rejects skills owned by another tenant")
    void fetchOwnedResourceRejectsWrongTenant() {
        UUID skillId = UUID.randomUUID();
        SkillDto foreign = skill(skillId, "someone-else", "X", null, null, null);
        when(agentClient.getSkill(skillId, OWNER)).thenReturn(foreign);

        assertThatThrownBy(() -> strategy.fetchOwnedResource(skillId.toString(), OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    @DisplayName("fetchOwnedResource throws when id is not a valid UUID")
    void fetchOwnedResourceRejectsInvalidId() {
        assertThatThrownBy(() -> strategy.fetchOwnedResource("not-a-uuid", OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid skill id");
    }

    @Test
    @DisplayName("buildSnapshot captures name, description, icon, instructions")
    void buildSnapshotCapturesFields() {
        UUID skillId = UUID.randomUUID();
        SkillDto skill = skill(skillId, OWNER, "Cold Email", "Closes a deal", "mail", "Use templates.");
        when(agentClient.getSkill(skillId, OWNER)).thenReturn(skill);

        Map<String, Object> snapshot = strategy.buildSnapshot(skillId.toString(), OWNER);

        assertThat(snapshot).containsEntry("name", "Cold Email")
                .containsEntry("description", "Closes a deal")
                .containsEntry("icon", "mail")
                .containsEntry("instructions", "Use templates.");
    }

    @Test
    @DisplayName("cloneFromSnapshot forwards snapshot fields + sourcePublicationId to AgentClient")
    void cloneForwardsFieldsAndSourcePublicationId() {
        UUID newId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        when(agentClient.createSkill(any(), eq(ACQUIRER))).thenAnswer(inv -> {
            SkillDto created = new SkillDto();
            created.setId(newId);
            return created;
        });

        Map<String, Object> snapshot = Map.of(
                "name", "Cold Email", "description", "Closes",
                "icon", "mail", "instructions", "Use templates.");

        String result = strategy.cloneFromSnapshot(snapshot, ACQUIRER, publicationId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(agentClient).createSkill(captor.capture(), eq(ACQUIRER));
        Map<String, Object> req = captor.getValue();

        assertThat(req).containsEntry("name", "Cold Email")
                .containsEntry("description", "Closes")
                .containsEntry("icon", "mail")
                .containsEntry("instructions", "Use templates.")
                .containsEntry("sourcePublicationId", publicationId.toString());
        assertThat(result).isEqualTo(newId.toString());
    }

    @Test
    @DisplayName("cloneFromSnapshot forwards organization scope to AgentClient")
    void cloneForwardsOrganizationScope() {
        UUID newId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        when(agentClient.createSkill(any(), eq(ACQUIRER))).thenAnswer(inv -> {
            SkillDto created = new SkillDto();
            created.setId(newId);
            return created;
        });

        strategy.cloneFromSnapshot(Map.of("name", "Org Skill"), ACQUIRER, publicationId, ACQUIRER_ORG);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(agentClient).createSkill(captor.capture(), eq(ACQUIRER));
        assertThat(captor.getValue())
                .containsEntry("sourcePublicationId", publicationId.toString())
                .containsEntry("organizationId", ACQUIRER_ORG);
    }

    @Test
    @DisplayName("cloneFromSnapshot throws when AgentClient returns null or a no-id DTO")
    void cloneThrowsWhenCreateFails() {
        when(agentClient.createSkill(any(), anyString())).thenReturn(null);

        assertThatThrownBy(() ->
                strategy.cloneFromSnapshot(Map.of("name", "X"), ACQUIRER, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to clone skill");
    }

    private SkillDto skill(UUID id, String tenant, String name, String desc, String icon, String instr) {
        SkillDto s = new SkillDto();
        s.setId(id);
        s.setTenantId(tenant);
        s.setName(name);
        s.setDescription(desc);
        s.setIcon(icon);
        s.setInstructions(instr);
        return s;
    }
}
