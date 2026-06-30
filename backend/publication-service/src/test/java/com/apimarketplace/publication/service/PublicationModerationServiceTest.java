package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.agent.client.dto.SkillDto;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceTypeDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy;
import com.apimarketplace.publication.service.resource.SkillResourceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PublicationModerationService#getComparisonData}.
 *
 * The goal of each test is to prove that the admin review diff doesn't
 * generate *false* drift from structural asymmetries - the snapshot and
 * currentSource must share the same shape so a side-by-side diff only
 * surfaces genuine content changes.
 *
 * Covered invariants:
 *  - SKILL: snapshot and live share identical top-level keys.
 *  - TABLE: landing interface appears on both sides when showcase is set.
 *  - TABLE: on live landing failure, sentinel {"interfaceId":…, "error":…}
 *    preserves the key - no "entire object removed" false drift.
 *  - AGENT: landingInterface is injected into currentSource (previously
 *    missing - the key would drift as "added" vs snapshot).
 *  - AGENT: landing error preserves key with sentinel.
 *  - Unknown publicationId → IllegalArgumentException.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationModerationService.getComparisonData")
class PublicationModerationServiceTest {

    private static final String TENANT_ID = "tenant-owner";
    private static final String ORG_ID = "org-review";

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private WorkflowPublicationService workflowPublicationService;

    private PublicationModerationService service;
    private LandingInterfaceSnapshotter landingInterfaceSnapshotter;

    @BeforeEach
    void setUp() {
        landingInterfaceSnapshotter = new LandingInterfaceSnapshotter(interfaceClient);
        // Register only the SKILL strategy - it's self-contained (no file clone service,
        // no orchestrator dependency) which keeps the tests tight. TABLE/INTERFACE
        // strategies are exercised in their own *StrategyTest classes.
        SkillResourceStrategy skillStrategy = new SkillResourceStrategy(agentClient);
        List<ResourcePublicationStrategy> strategies = List.of(skillStrategy);
        service = new PublicationModerationService(
                publicationRepository,
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                workflowPublicationService,
                landingInterfaceSnapshotter,
                strategies
        );
    }

    // ========================================================================
    // Completeness manifest (audit H10/M10/M12): reconcile DECLARED refs vs CAPTURED snapshot,
    // computed from the snapshot alone so it surfaces losses the drift diff masks.
    // ========================================================================

    @Test
    @DisplayName("completeness manifest flags a sub_workflow with no snapshot (diamond loss) and an uncaptured agent")
    void completenessManifestFlagsMissingSubWorkflowAndAgent() {
        Map<String, Object> plan = new java.util.HashMap<>();
        // Two sub_workflow nodes; only C is captured in _snapshot_subworkflows, D was dropped.
        plan.put("cores", List.of(
                Map.of("type", "sub_workflow", "subWorkflow", Map.of("workflowId", "D-uuid")),
                Map.of("type", "sub_workflow", "subWorkflow", Map.of("workflowId", "C-uuid"))));
        plan.put("_snapshot_subworkflows", Map.of(
                "C-uuid", Map.of("plan", Map.of("cores", List.of()))));
        // One agent captured, one not.
        plan.put("agents", List.of(
                Map.of("agentConfigId", "agent-captured", "_snapshot_agent_name", "Cap"),
                Map.of("agentConfigId", "agent-dropped")));

        Map<String, Object> manifest = service.buildPlanCompletenessManifest(plan);

        assertThat(manifest.get("complete")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missing = (List<Map<String, Object>>) manifest.get("missing");
        assertThat(missing).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("subWorkflow");
            assertThat(m.get("ref")).isEqualTo("D-uuid");
        });
        assertThat(missing).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("agent");
            assertThat(m.get("ref")).isEqualTo("agent-dropped");
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> captured = (Map<String, Object>) manifest.get("captured");
        assertThat(captured.get("subWorkflows")).isEqualTo(1);
        assertThat(captured.get("agents")).isEqualTo(1);
    }

    @Test
    @DisplayName("completeness manifest detects a loss nested inside a captured sub-workflow (recursive)")
    void completenessManifestRecursesIntoSubWorkflows() {
        // Parent captures child B; but B's own plan references grandchild G with no snapshot.
        Map<String, Object> childPlan = new java.util.HashMap<>();
        childPlan.put("cores", List.of(
                Map.of("type", "sub_workflow", "subWorkflow", Map.of("workflowId", "G-uuid"))));
        childPlan.put("_snapshot_subworkflows", Map.of()); // G missing

        Map<String, Object> plan = new java.util.HashMap<>();
        plan.put("cores", List.of(
                Map.of("type", "sub_workflow", "subWorkflow", Map.of("workflowId", "B-uuid"))));
        plan.put("_snapshot_subworkflows", Map.of("B-uuid", Map.of("plan", childPlan)));

        Map<String, Object> manifest = service.buildPlanCompletenessManifest(plan);

        assertThat(manifest.get("complete")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missing = (List<Map<String, Object>>) manifest.get("missing");
        assertThat(missing).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("subWorkflow");
            assertThat(m.get("ref")).isEqualTo("G-uuid");
            assertThat(m.get("at")).asString().contains("B-uuid"); // nested path
        });
    }

    @Test
    @DisplayName("completeness manifest is complete when every declared reference was captured")
    void completenessManifestCompleteWhenAllCaptured() {
        Map<String, Object> plan = new java.util.HashMap<>();
        plan.put("cores", List.of(
                Map.of("type", "sub_workflow", "subWorkflow", Map.of("workflowId", "B-uuid"))));
        plan.put("_snapshot_subworkflows", Map.of("B-uuid", Map.of("plan", Map.of("cores", List.of()))));
        plan.put("agents", List.of(Map.of("agentConfigId", "a1", "_snapshot_agent_name", "A1")));

        Map<String, Object> manifest = service.buildPlanCompletenessManifest(plan);

        assertThat(manifest.get("complete")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missing = (List<Map<String, Object>>) manifest.get("missing");
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("completeness manifest flags a sub-workflow referenced via agent toolsConfig.workflows OR a trigger (not just core nodes)")
    void completenessManifestFlagsAgentAndTriggerSubWorkflowRefs() {
        Map<String, Object> plan = new java.util.HashMap<>();
        // No core sub_workflow node - the references come ONLY via an agent and a workflow trigger.
        plan.put("agents", List.of(Map.of(
                "agentConfigId", "a1",
                "_snapshot_agent_name", "Agent",
                "_snapshot_agent_toolsConfig", Map.of("workflows", List.of("agent-ref-wf")))));
        plan.put("triggers", List.of(Map.of("type", "workflow", "id", "trigger-ref-wf")));
        plan.put("_snapshot_subworkflows", Map.of()); // neither captured

        Map<String, Object> manifest = service.buildPlanCompletenessManifest(plan);

        assertThat(manifest.get("complete")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missing = (List<Map<String, Object>>) manifest.get("missing");
        assertThat(missing).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("subWorkflow");
            assertThat(m.get("ref")).isEqualTo("agent-ref-wf");
        });
        assertThat(missing).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("subWorkflow");
            assertThat(m.get("ref")).isEqualTo("trigger-ref-wf");
        });
    }

    // ========================================================================
    // Happy paths: shape parity between snapshot and live source
    // ========================================================================

    @Test
    @DisplayName("SKILL comparison: snapshot and currentSource share the same top-level shape")
    void skillComparisonSharesShape() {
        UUID publicationId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();

        WorkflowPublicationEntity pub = newPub(publicationId, PublicationType.SKILL);
        pub.setResourceId(skillId.toString());
        pub.setPlanSnapshot(Map.of(
                "name", "Snapshotted skill",
                "description", "Old description",
                "icon", "star",
                "instructions", "do X"
        ));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        SkillDto liveSkill = new SkillDto();
        liveSkill.setId(skillId);
        liveSkill.setTenantId(TENANT_ID);
        liveSkill.setName("Live skill");
        liveSkill.setDescription("New description");
        liveSkill.setIcon("star");
        liveSkill.setInstructions("do Y");
        when(agentClient.getSkill(skillId, TENANT_ID)).thenReturn(liveSkill);

        Map<String, Object> result = service.getComparisonData(publicationId);

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) result.get("snapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");

        assertThat(snapshot.keySet()).isEqualTo(currentSource.keySet());
        assertThat(currentSource).containsEntry("name", "Live skill");
        assertThat(snapshot).containsEntry("name", "Snapshotted skill");
    }

    @Test
    @DisplayName("SKILL with showcase interface: landing appears on both sides under the same key")
    void skillWithShowcaseLandingParity() {
        UUID publicationId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        UUID landingId = UUID.randomUUID();

        WorkflowPublicationEntity pub = newPub(publicationId, PublicationType.SKILL);
        pub.setResourceId(skillId.toString());
        pub.setShowcaseInterfaceId(landingId);
        pub.setPlanSnapshot(Map.of(
                "name", "sk", "description", "d", "icon", "i", "instructions", "x",
                "landingInterface", Map.of("interfaceId", landingId.toString(), "name", "Old landing")
        ));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        SkillDto liveSkill = new SkillDto();
        liveSkill.setId(skillId);
        liveSkill.setTenantId(TENANT_ID);
        liveSkill.setName("sk");
        when(agentClient.getSkill(skillId, TENANT_ID)).thenReturn(liveSkill);

        InterfaceDto landing = new InterfaceDto();
        landing.setId(landingId);
        landing.setTenantId(TENANT_ID);
        landing.setName("New landing");
        landing.setHtmlTemplate("<h1/>");
        when(interfaceClient.getInterface(landingId, TENANT_ID)).thenReturn(landing);

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) result.get("snapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");

        assertThat(snapshot).containsKey("landingInterface");
        assertThat(currentSource).containsKey("landingInterface");
        @SuppressWarnings("unchecked")
        Map<String, Object> liveLanding = (Map<String, Object>) currentSource.get("landingInterface");
        assertThat(liveLanding).containsEntry("name", "New landing");
    }

    @Test
    @DisplayName("SKILL org comparison: live landing is fetched through publication organization scope")
    void skillOrgComparisonFetchesLandingThroughOrganizationScope() {
        UUID publicationId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        UUID landingId = UUID.randomUUID();

        WorkflowPublicationEntity pub = newOrgPub(publicationId, PublicationType.SKILL);
        pub.setResourceId(skillId.toString());
        pub.setShowcaseInterfaceId(landingId);
        pub.setPlanSnapshot(Map.of(
                "name", "sk", "description", "d", "icon", "i", "instructions", "x",
                "landingInterface", Map.of("interfaceId", landingId.toString(), "name", "Old landing")
        ));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        SkillDto liveSkill = new SkillDto();
        liveSkill.setId(skillId);
        liveSkill.setTenantId(TENANT_ID);
        liveSkill.setOrganizationId(ORG_ID);
        liveSkill.setName("sk");
        when(agentClient.getSkill(skillId, TENANT_ID)).thenReturn(liveSkill);

        InterfaceDto landing = new InterfaceDto();
        landing.setId(landingId);
        landing.setTenantId(TENANT_ID);
        landing.setOrganizationId(ORG_ID);
        landing.setName("Org landing");
        when(interfaceClient.getInterface(landingId, TENANT_ID, ORG_ID)).thenReturn(landing);

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");
        @SuppressWarnings("unchecked")
        Map<String, Object> liveLanding = (Map<String, Object>) currentSource.get("landingInterface");

        assertThat(liveLanding).containsEntry("name", "Org landing");
    }

    // ========================================================================
    // Landing error-marker symmetry
    // ========================================================================

    @Test
    @DisplayName("SKILL: live landing deletion surfaces as {interfaceId, error} - key stays, value is a sentinel")
    void skillLiveLandingErrorPreservesKey() {
        UUID publicationId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        UUID landingId = UUID.randomUUID();

        WorkflowPublicationEntity pub = newPub(publicationId, PublicationType.SKILL);
        pub.setResourceId(skillId.toString());
        pub.setShowcaseInterfaceId(landingId);
        pub.setPlanSnapshot(Map.of(
                "name", "sk", "description", "d", "icon", "i", "instructions", "x",
                "landingInterface", Map.of("interfaceId", landingId.toString(), "name", "Old")
        ));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        SkillDto liveSkill = new SkillDto();
        liveSkill.setId(skillId);
        liveSkill.setTenantId(TENANT_ID);
        liveSkill.setName("sk");
        when(agentClient.getSkill(skillId, TENANT_ID)).thenReturn(liveSkill);

        // Landing was deleted → snapshotter throws. Moderation must catch and
        // return sentinel so the key stays on the live side.
        when(interfaceClient.getInterface(landingId, TENANT_ID)).thenReturn(null);

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");

        assertThat(currentSource).containsKey("landingInterface");
        @SuppressWarnings("unchecked")
        Map<String, Object> landing = (Map<String, Object>) currentSource.get("landingInterface");
        assertThat(landing).containsEntry("interfaceId", landingId.toString());
        assertThat(landing).containsKey("error");
    }

    // ========================================================================
    // AGENT: landing must appear in currentSource (regression guard)
    // ========================================================================

    @Test
    @DisplayName("AGENT: landingInterface is mirrored in currentSource when showcase is set")
    void agentCurrentSourceIncludesLandingInterface() {
        UUID publicationId = UUID.randomUUID();
        UUID agentConfigId = UUID.randomUUID();
        UUID landingId = UUID.randomUUID();

        WorkflowPublicationEntity pub = newPub(publicationId, PublicationType.AGENT);
        pub.setAgentConfigId(agentConfigId);
        pub.setShowcaseInterfaceId(landingId);
        pub.setAgentSnapshot(Map.of(
                "agent", Map.of("id", agentConfigId.toString(), "name", "Bot"),
                "landingInterface", Map.of("interfaceId", landingId.toString(), "name", "Hero")
        ));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        AgentDto agent = new AgentDto();
        agent.setId(agentConfigId);
        agent.setTenantId(TENANT_ID);
        agent.setName("Bot");
        when(agentClient.getAgent(agentConfigId, TENANT_ID, null)).thenReturn(agent);
        lenient().when(agentClient.getSkillsForAgent(eq(agentConfigId), any(), any())).thenReturn(List.of());

        InterfaceDto landing = new InterfaceDto();
        landing.setId(landingId);
        landing.setTenantId(TENANT_ID);
        landing.setName("Hero v2");
        when(interfaceClient.getInterface(landingId, TENANT_ID)).thenReturn(landing);

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");

        assertThat(currentSource).containsKey("landingInterface");
        @SuppressWarnings("unchecked")
        Map<String, Object> liveLanding = (Map<String, Object>) currentSource.get("landingInterface");
        assertThat(liveLanding).containsEntry("name", "Hero v2");
    }

    @Test
    @DisplayName("AGENT org comparison: live landing is fetched through publication organization scope")
    void agentOrgComparisonFetchesLandingThroughOrganizationScope() {
        UUID publicationId = UUID.randomUUID();
        UUID agentConfigId = UUID.randomUUID();
        UUID landingId = UUID.randomUUID();

        WorkflowPublicationEntity pub = newOrgPub(publicationId, PublicationType.AGENT);
        pub.setAgentConfigId(agentConfigId);
        pub.setShowcaseInterfaceId(landingId);
        pub.setAgentSnapshot(Map.of(
                "agent", Map.of("id", agentConfigId.toString(), "name", "Bot"),
                "landingInterface", Map.of("interfaceId", landingId.toString(), "name", "Hero")
        ));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        AgentDto agent = new AgentDto();
        agent.setId(agentConfigId);
        agent.setTenantId(TENANT_ID);
        agent.setOrganizationId(ORG_ID);
        agent.setName("Bot");
        when(agentClient.getAgent(agentConfigId, TENANT_ID, ORG_ID)).thenReturn(agent);
        lenient().when(agentClient.getSkillsForAgent(eq(agentConfigId), any(), any())).thenReturn(List.of());

        InterfaceDto landing = new InterfaceDto();
        landing.setId(landingId);
        landing.setTenantId(TENANT_ID);
        landing.setOrganizationId(ORG_ID);
        landing.setName("Org hero v2");
        when(interfaceClient.getInterface(landingId, TENANT_ID, ORG_ID)).thenReturn(landing);

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");
        @SuppressWarnings("unchecked")
        Map<String, Object> liveLanding = (Map<String, Object>) currentSource.get("landingInterface");

        assertThat(liveLanding).containsEntry("name", "Org hero v2");
    }

    @Test
    @DisplayName("AGENT: live landing failure preserves key via sentinel (mirrors TABLE/SKILL behavior)")
    void agentLiveLandingErrorPreservesKey() {
        UUID publicationId = UUID.randomUUID();
        UUID agentConfigId = UUID.randomUUID();
        UUID landingId = UUID.randomUUID();

        WorkflowPublicationEntity pub = newPub(publicationId, PublicationType.AGENT);
        pub.setAgentConfigId(agentConfigId);
        pub.setShowcaseInterfaceId(landingId);
        pub.setAgentSnapshot(Map.of("agent", Map.of("id", agentConfigId.toString())));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        AgentDto agent = new AgentDto();
        agent.setId(agentConfigId);
        agent.setTenantId(TENANT_ID);
        agent.setName("Bot");
        when(agentClient.getAgent(agentConfigId, TENANT_ID, null)).thenReturn(agent);
        lenient().when(agentClient.getSkillsForAgent(eq(agentConfigId), any(), any())).thenReturn(List.of());
        when(interfaceClient.getInterface(landingId, TENANT_ID)).thenReturn(null);

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");

        assertThat(currentSource).containsKey("landingInterface");
        @SuppressWarnings("unchecked")
        Map<String, Object> landing = (Map<String, Object>) currentSource.get("landingInterface");
        assertThat(landing).containsEntry("interfaceId", landingId.toString());
        assertThat(landing).containsKey("error");
    }

    // ========================================================================
    // Referenced-resource iteration: live side uses snapshot keys (dedup preserved)
    // ========================================================================

    @Test
    @DisplayName("AGENT: fetchReferencedInterfaces iterates snapshot keys - dedup from snapshot-build carries over")
    void agentReferencedInterfacesIterateSnapshotKeys() {
        UUID publicationId = UUID.randomUUID();
        UUID agentConfigId = UUID.randomUUID();
        UUID standaloneIfaceId = UUID.randomUUID();
        UUID embeddedIfaceId = UUID.randomUUID();

        // Snapshot only lists `standaloneIfaceId` under `interfaces` - the embedded one
        // was deduped into a workflow plan at publish time and MUST NOT reappear on
        // the live side (that would be false drift).
        WorkflowPublicationEntity pub = newPub(publicationId, PublicationType.AGENT);
        pub.setAgentConfigId(agentConfigId);
        pub.setAgentSnapshot(Map.of(
                "agent", Map.of("id", agentConfigId.toString()),
                "interfaces", Map.of(
                        standaloneIfaceId.toString(), Map.of("name", "Old standalone"))
        ));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        AgentDto agent = new AgentDto();
        agent.setId(agentConfigId);
        agent.setTenantId(TENANT_ID);
        when(agentClient.getAgent(agentConfigId, TENANT_ID, null)).thenReturn(agent);
        lenient().when(agentClient.getSkillsForAgent(eq(agentConfigId), any(), any())).thenReturn(List.of());

        InterfaceDto liveStandalone = new InterfaceDto();
        liveStandalone.setId(standaloneIfaceId);
        liveStandalone.setTenantId(TENANT_ID);
        liveStandalone.setName("Live standalone");
        when(interfaceClient.getInterface(standaloneIfaceId, TENANT_ID)).thenReturn(liveStandalone);
        // embeddedIfaceId must NEVER be fetched - no stub for it so any call would
        // return null and surface the bug.

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");
        @SuppressWarnings("unchecked")
        Map<String, Object> liveInterfaces = (Map<String, Object>) currentSource.get("interfaces");

        assertThat(liveInterfaces).containsOnlyKeys(standaloneIfaceId.toString());
        assertThat(liveInterfaces).doesNotContainKey(embeddedIfaceId.toString());
    }

    // ========================================================================
    // Org-scope propagation into the LIVE source fetch
    //
    // Regression: org-owned publications surfaced every referenced resource as
    // "not found" in the admin diff because the comparison rebuilt the live
    // source without the publication's OWNING org scope - the reviewing admin is
    // almost never in the publisher's workspace, so the strict-scope lookups
    // failed. These tests pin that the owning org is forwarded; they fail on the
    // pre-fix code (which called the org-less client overloads).
    // ========================================================================

    @Test
    @DisplayName("WORKFLOW org comparison: live workflow + enrichment run through the publication org scope")
    void workflowOrgComparisonForwardsOrganizationScope() {
        UUID publicationId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        WorkflowPublicationEntity pub = newOrgPub(publicationId, PublicationType.WORKFLOW);
        pub.setWorkflowId(workflowId);
        pub.setPlanSnapshot(Map.of("cores", List.of()));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        Map<String, Object> livePlan = new java.util.HashMap<>();
        livePlan.put("cores", List.of());
        when(orchestratorClient.getWorkflowForPublication(workflowId, TENANT_ID, ORG_ID))
                .thenReturn(Map.of("plan", livePlan));

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");

        // org-scoped fetch succeeded → real plan present, NOT a "not found" sentinel
        assertThat(currentSource).containsKey("plan");
        assertThat(currentSource).doesNotContainKey("error");
        // enrichment must run in the SAME org scope (4-arg overload), else nested
        // interfaces/datasources/agents would silently fail their strict-scope lookups
        verify(workflowPublicationService)
                .enrichWorkflowPlan(eq(livePlan), eq(TENANT_ID), eq(ORG_ID), eq(workflowId));
    }

    @Test
    @DisplayName("AGENT org comparison: referenced interfaces + datasources run through the publication org scope")
    void agentOrgComparisonForwardsOrgToReferencedResources() {
        UUID publicationId = UUID.randomUUID();
        UUID agentConfigId = UUID.randomUUID();
        UUID ifaceId = UUID.randomUUID();
        long dsId = 777L;

        WorkflowPublicationEntity pub = newOrgPub(publicationId, PublicationType.AGENT);
        pub.setAgentConfigId(agentConfigId);
        pub.setAgentSnapshot(Map.of(
                "agent", Map.of("id", agentConfigId.toString()),
                "interfaces", Map.of(ifaceId.toString(), Map.of("name", "Old iface")),
                "datasources", Map.of(String.valueOf(dsId), Map.of("name", "Old table"))
        ));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        AgentDto agent = new AgentDto();
        agent.setId(agentConfigId);
        agent.setTenantId(TENANT_ID);
        agent.setOrganizationId(ORG_ID);
        when(agentClient.getAgent(agentConfigId, TENANT_ID, ORG_ID)).thenReturn(agent);
        lenient().when(agentClient.getSkillsForAgent(eq(agentConfigId), any(), any())).thenReturn(List.of());

        InterfaceDto liveIface = new InterfaceDto();
        liveIface.setId(ifaceId);
        liveIface.setTenantId(TENANT_ID);
        liveIface.setOrganizationId(ORG_ID);
        liveIface.setName("Org iface live");
        when(interfaceClient.getInterface(ifaceId, TENANT_ID, ORG_ID)).thenReturn(liveIface);

        DataSourceDto liveDs = orgDataSource(dsId, "Org table live");
        when(dataSourceClient.findByIdAndTenantId(dsId, TENANT_ID, ORG_ID)).thenReturn(liveDs);
        lenient().when(dataSourceClient.getAllItems(dsId, TENANT_ID, ORG_ID)).thenReturn(List.of());

        Map<String, Object> result = service.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");
        @SuppressWarnings("unchecked")
        Map<String, Object> liveInterfaces = (Map<String, Object>) currentSource.get("interfaces");
        @SuppressWarnings("unchecked")
        Map<String, Object> liveDatasources = (Map<String, Object>) currentSource.get("datasources");
        @SuppressWarnings("unchecked")
        Map<String, Object> ifaceEntry = (Map<String, Object>) liveInterfaces.get(ifaceId.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> dsEntry = (Map<String, Object>) liveDatasources.get(String.valueOf(dsId));

        // org-scoped fetch resolved → live name present, NOT a "not found" sentinel
        assertThat(ifaceEntry).containsEntry("name", "Org iface live").doesNotContainKey("error");
        assertThat(dsEntry).containsEntry("name", "Org table live").doesNotContainKey("error");
    }

    @Test
    @DisplayName("TABLE org comparison: live snapshot is rebuilt through the publication org scope")
    void tableOrgComparisonForwardsOrgToStrategy() {
        UUID publicationId = UUID.randomUUID();
        String resourceId = "42";

        ResourcePublicationStrategy tableStrategy = mock(ResourcePublicationStrategy.class);
        when(tableStrategy.getPublicationType()).thenReturn(PublicationType.TABLE);
        when(tableStrategy.buildSnapshot(resourceId, TENANT_ID, ORG_ID))
                .thenReturn(Map.of("name", "Org table live"));

        PublicationModerationService svc = new PublicationModerationService(
                publicationRepository, orchestratorClient, agentClient, interfaceClient,
                dataSourceClient, workflowPublicationService, landingInterfaceSnapshotter,
                List.of(tableStrategy));

        WorkflowPublicationEntity pub = newOrgPub(publicationId, PublicationType.TABLE);
        pub.setResourceId(resourceId);
        pub.setPlanSnapshot(Map.of("name", "Old table"));
        when(publicationRepository.findById(publicationId)).thenReturn(Optional.of(pub));

        Map<String, Object> result = svc.getComparisonData(publicationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentSource = (Map<String, Object>) result.get("currentSource");

        assertThat(currentSource).containsEntry("name", "Org table live").doesNotContainKey("error");
        verify(tableStrategy).buildSnapshot(resourceId, TENANT_ID, ORG_ID);
    }

    // ========================================================================
    // Error path: unknown publicationId
    // ========================================================================

    @Test
    @DisplayName("Unknown publicationId throws IllegalArgumentException (no swallow)")
    void unknownPublicationIdThrows() {
        UUID missingId = UUID.randomUUID();
        when(publicationRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getComparisonData(missingId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Publication not found");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private WorkflowPublicationEntity newPub(UUID id, PublicationType type) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(id);
        pub.setPublicationType(type);
        pub.setPublisherId(TENANT_ID);
        pub.setTitle("title");
        pub.setDescription("desc");
        pub.setStatus(WorkflowPublicationEntity.PublicationStatus.PENDING_REVIEW);
        pub.setVisibility(PublicationVisibility.PUBLIC);
        pub.setCreditsPerUse(0);
        return pub;
    }

    private WorkflowPublicationEntity newOrgPub(UUID id, PublicationType type) {
        WorkflowPublicationEntity pub = newPub(id, type);
        pub.assignOwnerFromContext(TENANT_ID, ORG_ID);
        return pub;
    }

    private DataSourceDto orgDataSource(long id, String name) {
        return new DataSourceDto(
                id, TENANT_ID, name, "desc",
                DataSourceTypeDto.INLINE, Map.of(),
                null, null, null, null,
                List.of(),
                null,
                null, null, null, null);
    }
}
