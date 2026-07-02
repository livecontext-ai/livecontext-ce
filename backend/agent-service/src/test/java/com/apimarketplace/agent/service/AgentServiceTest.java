package com.apimarketplace.agent.service;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.dto.AgentAvatarResponse;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentService - unit tests")
class AgentServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentDefaultsConfig defaults;

    @Mock
    private OrgAccessGuard orgAccessService;

    @Mock
    private com.apimarketplace.agent.repository.AgentMetricsAggregationRepository metricsAggregationRepository;

    @Mock
    private com.apimarketplace.agent.webhook.AgentWebhookTokenService webhookTokenService;

    @Mock
    private org.springframework.web.client.RestTemplate restTemplate;

    @Mock
    private com.apimarketplace.publication.client.PublicationClient publicationClient;

    @InjectMocks
    private AgentService agentService;

    @BeforeEach
    void injectOptionalDependencies() {
        // @Autowired(required = false) fields aren't injected by @InjectMocks constructor path
        org.springframework.test.util.ReflectionTestUtils.setField(agentService, "webhookTokenService", webhookTokenService);
        org.springframework.test.util.ReflectionTestUtils.setField(agentService, "restTemplate", restTemplate);
        org.springframework.test.util.ReflectionTestUtils.setField(agentService, "triggerServiceUrl", "http://localhost:8091");
        org.springframework.test.util.ReflectionTestUtils.setField(agentService, "publicationClient", publicationClient);
        org.mockito.Mockito.lenient().when(orgAccessService.canWrite(any(), any(), any(), any(), any()))
            .thenReturn(true);
        // Default: no shared publications. The paged-list path always batches the page's badges, so a
        // null return would NPE; individual paged tests override this with specific statuses.
        org.mockito.Mockito.lenient()
            .when(publicationClient.findResourcePublicationStatuses(any(), any(), any()))
            .thenReturn(java.util.Map.of());
    }

    private static final String TENANT_ID = "user|tenant-abc";
    private static final UUID AGENT_ID   = UUID.randomUUID();

    private void stubDefaults() {
        org.mockito.Mockito.lenient().when(defaults.getTemperature()).thenReturn(0.7);
        org.mockito.Mockito.lenient().when(defaults.getMaxTokens()).thenReturn(4096);
        org.mockito.Mockito.lenient().when(defaults.getMaxIterations()).thenReturn(25);
        org.mockito.Mockito.lenient().when(defaults.getExecutionTimeout()).thenReturn(600);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private AgentEntity agentWith(UUID id, String tenantId, String name) {
        AgentEntity e = new AgentEntity();
        e.setId(id);
        e.setTenantId(tenantId);
        e.setName(name);
        e.setTemperature(BigDecimal.valueOf(0.5));
        e.setMaxTokens(2048);
        e.setMaxIterations(10);
        e.setExecutionTimeout(300);
        return e;
    }

    /** Stubs repository.save to echo the entity passed to it. */
    private void stubSaveReturnsArgument() {
        when(agentRepository.save(any(AgentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    /** Builds a toolsConfig with a single sub-agent UUID. */
    private Map<String, Object> toolsConfigWithAgents(String... agentIds) {
        List<String> ids = new ArrayList<>(List.of(agentIds));
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("agents", ids);
        return cfg;
    }

    // =====================================================================
    // listAgentsPaged - server-paged sort / visibility / inlined publication status
    // =====================================================================

    @Nested
    @DisplayName("listAgentsPaged - server-paged sort / visibility / inlined publication status")
    class PagedList {

        private static final String PAGED_TENANT = "tenant-1";

        private AgentEntity pagedAgent(String name, Instant updatedAt) {
            AgentEntity e = new AgentEntity();
            e.setId(UUID.randomUUID());
            e.setTenantId(PAGED_TENANT);
            e.setName(name);
            e.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            e.setUpdatedAt(updatedAt);
            return e;
        }

        @Test
        @DisplayName("sort=name orders the page case-insensitively A->Z")
        void sortsByName() {
            AgentEntity zed = pagedAgent("Zed", Instant.parse("2026-06-18T00:00:00Z"));
            AgentEntity ann = pagedAgent("ann", Instant.parse("2026-06-10T00:00:00Z"));
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(PAGED_TENANT)).thenReturn(List.of(zed, ann));

            AgentService.AgentPage p = agentService.listAgentsPaged(
                    PAGED_TENANT, null, null, null, 0, 25, "name", null);

            assertThat(p.items()).containsExactly(ann, zed);
        }

        @Test
        @DisplayName("default sort is lastModified (updatedAt) most-recent first")
        void sortsByLastModifiedByDefault() {
            AgentEntity older = pagedAgent("A", Instant.parse("2026-06-10T00:00:00Z"));
            AgentEntity newer = pagedAgent("B", Instant.parse("2026-06-18T00:00:00Z"));
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(PAGED_TENANT)).thenReturn(List.of(older, newer));

            AgentService.AgentPage p = agentService.listAgentsPaged(
                    PAGED_TENANT, null, null, null, 0, 25, null, null);

            assertThat(p.items()).containsExactly(newer, older);
        }

        @Test
        @DisplayName("visibility=public keeps only shared agents (one batched AGENT-keyed status call)")
        void visibilityPublicKeepsOnlyShared() {
            AgentEntity shared = pagedAgent("Shared", Instant.parse("2026-06-01T00:00:00Z"));
            AgentEntity priv = pagedAgent("Private", Instant.parse("2026-06-01T00:00:00Z"));
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(PAGED_TENANT)).thenReturn(List.of(shared, priv));
            when(publicationClient.findResourcePublicationStatuses(eq("AGENT"), any(), eq(PAGED_TENANT)))
                    .thenReturn(Map.of(shared.getId().toString(),
                            new com.apimarketplace.publication.client.PublicationClient.ResourcePublicationStatusRef("ACTIVE", null)));

            AgentService.AgentPage p = agentService.listAgentsPaged(
                    PAGED_TENANT, null, null, null, 0, 25, null, "public");

            assertThat(p.items()).containsExactly(shared);
            assertThat(p.totalCount()).isEqualTo(1);
            // Exactly ONE batched status call: the page badges reuse the visibility-filter statuses
            // instead of re-fetching - the central efficiency guarantee of this change.
            verify(publicationClient, times(1)).findResourcePublicationStatuses(eq("AGENT"), any(), eq(PAGED_TENANT));
        }

        @Test
        @DisplayName("visibility=private keeps only the non-shared agents")
        void visibilityPrivateKeepsOnlyRest() {
            AgentEntity shared = pagedAgent("Shared", Instant.parse("2026-06-01T00:00:00Z"));
            AgentEntity priv = pagedAgent("Private", Instant.parse("2026-06-01T00:00:00Z"));
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(PAGED_TENANT)).thenReturn(List.of(shared, priv));
            when(publicationClient.findResourcePublicationStatuses(eq("AGENT"), any(), eq(PAGED_TENANT)))
                    .thenReturn(Map.of(shared.getId().toString(),
                            new com.apimarketplace.publication.client.PublicationClient.ResourcePublicationStatusRef("ACTIVE", null)));

            AgentService.AgentPage p = agentService.listAgentsPaged(
                    PAGED_TENANT, null, null, null, 0, 25, null, "private");

            assertThat(p.items()).containsExactly(priv);
        }

        @Test
        @DisplayName("inlines the page's publication badge under publicationStatuses")
        void inlinesPublicationStatuses() {
            AgentEntity shared = pagedAgent("Shared", Instant.parse("2026-06-01T00:00:00Z"));
            AgentEntity priv = pagedAgent("Private", Instant.parse("2026-06-01T00:00:00Z"));
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(PAGED_TENANT)).thenReturn(List.of(shared, priv));
            when(publicationClient.findResourcePublicationStatuses(eq("AGENT"), any(), eq(PAGED_TENANT)))
                    .thenReturn(Map.of(shared.getId().toString(),
                            new com.apimarketplace.publication.client.PublicationClient.ResourcePublicationStatusRef("ACTIVE", null)));

            AgentService.AgentPage p = agentService.listAgentsPaged(
                    PAGED_TENANT, null, null, null, 0, 25, null, null);

            assertThat(p.publicationStatuses()).containsOnlyKeys(shared.getId().toString());
            assertThat(p.publicationStatuses().get(shared.getId().toString())).containsEntry("status", "ACTIVE");
        }

        @Test
        @DisplayName("paginates the sorted set and reports the full total")
        void paginates() {
            List<AgentEntity> five = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                five.add(pagedAgent("A" + i, Instant.parse("2026-06-0" + (i + 1) + "T00:00:00Z")));
            }
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(PAGED_TENANT)).thenReturn(five);

            AgentService.AgentPage p = agentService.listAgentsPaged(
                    PAGED_TENANT, null, null, null, 1, 2, null, null);

            assertThat(p.totalCount()).isEqualTo(5);
            assertThat(p.page()).isEqualTo(1);
            assertThat(p.size()).isEqualTo(2);
            assertThat(p.items()).hasSize(2);
        }

        @Test
        @DisplayName("a null publication client (test/back-compat wiring) skips the filter and emits no badges")
        void nullPublicationClientDegradesGracefully() {
            // publicationClient is @Autowired(required=false), so it can be absent. Null out the
            // field-injected mock to exercise the best-effort degrade path on the real instance.
            org.springframework.test.util.ReflectionTestUtils.setField(agentService, "publicationClient", null);
            AgentEntity a = pagedAgent("A", Instant.parse("2026-06-01T00:00:00Z"));
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(PAGED_TENANT)).thenReturn(List.of(a));

            AgentService.AgentPage p = agentService.listAgentsPaged(
                    PAGED_TENANT, null, null, null, 0, 25, null, "public");

            // Visibility filter is skipped (no client), the item still shows, and there are no badges.
            assertThat(p.items()).containsExactly(a);
            assertThat(p.publicationStatuses()).isEmpty();
        }
    }

    // =====================================================================
    // createAgent
    // =====================================================================

    @Nested
    @DisplayName("createAgent")
    class CreateAgentTests {

        @BeforeEach
        void setup() {
            stubDefaults();
        }

        @Test
        @DisplayName("Applies defaults when temperature/maxTokens/maxIterations/timeout are null")
        void appliesDefaultsWhenNullsProvided() {
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null
            );

            assertThat(result.getTemperature())
                .isEqualByComparingTo(BigDecimal.valueOf(0.7));
            assertThat(result.getMaxTokens()).isEqualTo(4096);
            assertThat(result.getMaxIterations()).isEqualTo(25);
            assertThat(result.getExecutionTimeout()).isEqualTo(600);
        }

        @Test
        @DisplayName("Create with null toolsConfig backfills the 5 internal keys with [] (regression)")
        void createNormalizesNullToolsConfigToExplicitEmptyLists() {
            // Pre-fix: createAgent called `new AgentEntity(..., toolsConfig, ...)`
            // with the raw caller-supplied map. A null toolsConfig persisted as NULL
            // → AgentConfigProvider parsed nothing → runtime treated as "no
            // restriction" for every internal resource. Post-fix: createAgent routes
            // through normalizeToolsConfig → null becomes a fully-explicit map with
            // workflows/tables/interfaces/agents/applications all set to [].
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, null,
                null, // toolsConfig null
                null, null, null, null,
                null, null, null, null, null, null);

            assertThat(result.getToolsConfig()).isNotNull();
            assertThat(result.getToolsConfig().get("mode")).isEqualTo("all");
            assertThat(result.getToolsConfig().get("workflows")).isEqualTo(List.of());
            assertThat(result.getToolsConfig().get("tables")).isEqualTo(List.of());
            assertThat(result.getToolsConfig().get("interfaces")).isEqualTo(List.of());
            assertThat(result.getToolsConfig().get("agents")).isEqualTo(List.of());
            assertThat(result.getToolsConfig().get("applications")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("normalizeToolsConfig preserves mode=off - the no-tools mode survives backfill and is never defaulted to 'all'")
        void normalizePreservesModeOff() {
            // mode=off (the reasoning-only / zero-tool agent) must round-trip through normalization
            // unchanged: present mode is preserved, and the backfilled per-family grants are
            // irrelevant because AgentModuleResolver short-circuits on mode=off. If normalization
            // ever dropped/replaced it, an "off" agent would silently regain tools.
            Map<String, Object> tc = new HashMap<>();
            tc.put("mode", "off");
            tc.put("tablesGrant", "all"); // a present grant must NOT flip mode away from off

            Map<String, Object> normalized = AgentService.normalizeToolsConfig(tc);

            assertThat(normalized.get("mode"))
                .as("mode=off must survive normalization (preserved, never defaulted to 'all')")
                .isEqualTo("off");
        }

        @Test
        @DisplayName("Keeps explicit temperature/maxTokens/maxIterations/timeout when provided")
        void keepsExplicitValuesOverDefaults() {
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                BigDecimal.valueOf(1.2), 8192, 50, 120,
                null, null, null, null, null,
                null, null, null, null, null, null
            );

            assertThat(result.getTemperature()).isEqualByComparingTo(BigDecimal.valueOf(1.2));
            assertThat(result.getMaxTokens()).isEqualTo(8192);
            assertThat(result.getMaxIterations()).isEqualTo(50);
            assertThat(result.getExecutionTimeout()).isEqualTo(120);
        }

        @Test
        @DisplayName("Sets a random preset avatarUrl when avatarUrl is null")
        void setsRandomAvatarPresetWhenNull() {
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null
            );

            assertThat(result.getAvatarUrl())
                .isNotNull()
                .startsWith("preset:");
        }

        @Test
        @DisplayName("Creates the dedicated conversation in the requested org workspace")
        void createsDedicatedConversationInRequestedOrgWorkspace() {
            com.apimarketplace.conversation.client.ConversationClient conversationClient =
                    org.mockito.Mockito.mock(com.apimarketplace.conversation.client.ConversationClient.class);
            org.springframework.test.util.ReflectionTestUtils.setField(agentService, "conversationServiceClient", conversationClient);

            UUID dedicatedConversationId = UUID.randomUUID();
            when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> {
                AgentEntity entity = inv.getArgument(0);
                if (entity.getId() == null) {
                    entity.setId(AGENT_ID);
                }
                return entity;
            });
            when(conversationClient.findOrCreateAgentConversation(
                    eq(AGENT_ID.toString()), eq(TENANT_ID), eq("Scoped Agent"), eq("org-123")))
                    .thenReturn(dedicatedConversationId.toString());

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "Scoped Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, "org-123",
                null, null
            );

            verify(conversationClient).findOrCreateAgentConversation(
                    AGENT_ID.toString(), TENANT_ID, "Scoped Agent", "org-123");
            assertThat(result.getConversationId()).isEqualTo(dedicatedConversationId);
        }

        @Test
        @DisplayName("Keeps provided avatarUrl and does not override with preset")
        void keepsProvidedAvatarUrl() {
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                "https://example.com/avatar.png", null, null, null, null, null
            );

            assertThat(result.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
        }

        @Test
        @DisplayName("Sets organizationId when provided")
        void setsOrganizationIdWhenProvided() {
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, "org-123", null, null
            );

            assertThat(result.getOrganizationId()).isEqualTo("org-123");
        }

        @Test
        @DisplayName("Does not set organizationId when null")
        void doesNotSetOrganizationIdWhenNull() {
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null
            );

            assertThat(result.getOrganizationId()).isNull();
        }

        @Test
        @DisplayName("Calls agentRepository.save")
        void callsRepositorySave() {
            stubSaveReturnsArgument();

            agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null
            );

            verify(agentRepository, times(1)).save(any(AgentEntity.class));
        }

        // --- Bounds validation (BUG-1 / BUG-2 regression guards) -----------
        // createAgent must reject max_iterations outside [1, 1000] and
        // execution_timeout outside [10, 7200]. Before the original fix these
        // values were silently accepted, producing agents that time out before
        // the first token or loop far beyond the documented limit. Upper bounds
        // were raised (50→1000, 3600→7200) for high-capacity agentic runs.

        @Test
        @DisplayName("Rejects max_iterations=1001 (above upper bound)")
        void rejectsMaxIterationsAboveBound() {
            assertThatThrownBy(() -> agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, 1001, null,
                null, null, null, null, null,
                null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_iterations")
                .hasMessageContaining("between 1 and 1000");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Rejects max_iterations=-5 (below lower bound)")
        void rejectsMaxIterationsBelowBound() {
            assertThatThrownBy(() -> agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, -5, null,
                null, null, null, null, null,
                null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_iterations")
                .hasMessageContaining("between 1 and 1000");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Rejects execution_timeout=5 (below lower bound)")
        void rejectsExecutionTimeoutBelowBound() {
            assertThatThrownBy(() -> agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, 5,
                null, null, null, null, null,
                null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution_timeout")
                .hasMessageContaining("between 10 and 7200");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Rejects execution_timeout=8000 (above upper bound)")
        void rejectsExecutionTimeoutAboveBound() {
            assertThatThrownBy(() -> agentService.createAgent(
                TENANT_ID, "My Agent", null, null, null, null,
                null, null, null, 8000,
                null, null, null, null, null,
                null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution_timeout")
                .hasMessageContaining("between 10 and 7200");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Accepts max_iterations=1 and max_iterations=1000 (inclusive bounds)")
        void acceptsMaxIterationsAtBounds() {
            stubSaveReturnsArgument();

            AgentEntity lower = agentService.createAgent(
                TENANT_ID, "Agent Lower", null, null, null, null,
                null, null, 1, null,
                null, null, null, null, null,
                null, null, null, null, null, null);
            AgentEntity upper = agentService.createAgent(
                TENANT_ID, "Agent Upper", null, null, null, null,
                null, null, 1000, null,
                null, null, null, null, null,
                null, null, null, null, null, null);

            assertThat(lower.getMaxIterations()).isEqualTo(1);
            assertThat(upper.getMaxIterations()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Accepts execution_timeout=10 and execution_timeout=7200 (inclusive bounds)")
        void acceptsExecutionTimeoutAtBounds() {
            stubSaveReturnsArgument();

            AgentEntity lower = agentService.createAgent(
                TENANT_ID, "Agent TLower", null, null, null, null,
                null, null, null, 10,
                null, null, null, null, null,
                null, null, null, null, null, null);
            AgentEntity upper = agentService.createAgent(
                TENANT_ID, "Agent TUpper", null, null, null, null,
                null, null, null, 7200,
                null, null, null, null, null,
                null, null, null, null, null, null);

            assertThat(lower.getExecutionTimeout()).isEqualTo(10);
            assertThat(upper.getExecutionTimeout()).isEqualTo(7200);
        }
    }

    // =====================================================================
    // getAgent
    // =====================================================================

    @Nested
    @DisplayName("getAgent")
    class GetAgentTests {

        @Test
        @DisplayName("Returns agent when found and tenant matches")
        void returnsAgentWhenTenantMatches() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            Optional<AgentEntity> result = agentService.getAgent(AGENT_ID, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(AGENT_ID);
        }

        @Test
        @DisplayName("Returns empty when tenant does not match")
        void returnsEmptyWhenTenantMismatch() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            Optional<AgentEntity> result = agentService.getAgent(AGENT_ID, "other-tenant");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Returns empty when agent not found in repository")
        void returnsEmptyWhenNotFound() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            Optional<AgentEntity> result = agentService.getAgent(AGENT_ID, TENANT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Restricted org member cannot read agent details by direct ID")
        void restrictedOrgMemberCannotReadAgentDetailsByDirectId() {
            AgentEntity entity = agentWith(AGENT_ID, "owner-tenant", "Org Agent");
            entity.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(orgAccessService.canAccess("org-42", TENANT_ID, "agent", AGENT_ID.toString(), "MEMBER"))
                    .thenReturn(false);

            Optional<AgentEntity> result = agentService.getAgent(AGENT_ID, TENANT_ID, "org-42", "MEMBER");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("2-arg getAgent self-heals BOTH orgId AND orgRole from RequestContextHolder - regression: pre-fix passed orgRole=null → canAccess deny-list rejected non-OWNER members (prod 2026-05-21 'Access restricted')")
        void getAgent2ArgSelfHealsOrgIdAndOrgRoleFromRequestContext() {
            AgentEntity entity = agentWith(AGENT_ID, "owner-tenant", "Team Agent");
            entity.setOrganizationId("org-77");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(orgAccessService.canAccess("org-77", TENANT_ID, "agent", AGENT_ID.toString(), "MEMBER"))
                    .thenReturn(true);

            // Bind a fake HTTP request to RequestContextHolder so
            // TenantResolver.currentRequestOrganizationId() and
            // currentRequestOrganizationRole() resolve from servlet headers.
            org.springframework.mock.web.MockHttpServletRequest req =
                    new org.springframework.mock.web.MockHttpServletRequest();
            req.addHeader("X-Organization-ID", "org-77");
            req.addHeader("X-Organization-Role", "MEMBER");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(req));
            try {
                Optional<AgentEntity> result = agentService.getAgent(AGENT_ID, TENANT_ID);

                assertThat(result).isPresent();
                // Critical: canAccess called with orgRole="MEMBER" (NOT null).
                // A regression in the self-heal (e.g. dropping the
                // currentRequestOrganizationRole() read) would deny via the
                // null-role deny-list path → result empty → test fails.
                verify(orgAccessService).canAccess("org-77", TENANT_ID, "agent",
                        AGENT_ID.toString(), "MEMBER");
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    // =====================================================================
    // listAgents
    // =====================================================================

    @Nested
    @DisplayName("listAgents")
    class ListAgentsTests {

        @Test
        @DisplayName("Without orgId: calls findByTenantIdOrderByCreatedAtDesc")
        void listWithoutOrgId() {
            List<AgentEntity> expected = List.of(agentWith(AGENT_ID, TENANT_ID, "A"));
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                .thenReturn(expected);

            List<AgentEntity> result = agentService.listAgents(TENANT_ID, null, null);

            assertThat(result).isSameAs(expected);
            verify(agentRepository).findByTenantIdOrderByCreatedAtDesc(TENANT_ID);
            verify(agentRepository, never()).findByOrganizationOrOwner(anyString(), anyString());
        }

        @Test
        @DisplayName("Without orgId (blank): calls findByTenantIdOrderByCreatedAtDesc")
        void listWithBlankOrgId() {
            List<AgentEntity> expected = List.of(agentWith(AGENT_ID, TENANT_ID, "A"));
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                .thenReturn(expected);

            List<AgentEntity> result = agentService.listAgents(TENANT_ID, "  ", null);

            assertThat(result).isSameAs(expected);
            verify(agentRepository).findByTenantIdOrderByCreatedAtDesc(TENANT_ID);
        }

        @Test
        @DisplayName("With orgId: calls findByOrganizationOrOwner and orgAccessService.filterAccessible")
        void listWithOrgId() {
            String orgId = "org-xyz";
            List<AgentEntity> all = List.of(agentWith(AGENT_ID, TENANT_ID, "A"));
            List<AgentEntity> filtered = List.of(agentWith(AGENT_ID, TENANT_ID, "A"));

            when(agentRepository.findByOrganizationOrOwner(orgId, TENANT_ID)).thenReturn(all);
            when(orgAccessService.filterAccessible(
                eq(all), eq(orgId), eq(TENANT_ID), eq("agent"), eq("MEMBER"), any()))
                .thenReturn(filtered);

            List<AgentEntity> result = agentService.listAgents(TENANT_ID, orgId, "MEMBER");

            assertThat(result).isSameAs(filtered);
            verify(agentRepository).findByOrganizationOrOwner(orgId, TENANT_ID);
            verify(orgAccessService).filterAccessible(
                eq(all), eq(orgId), eq(TENANT_ID), eq("agent"), eq("MEMBER"), any());
        }

        @Test
        @DisplayName("URL-decodes %7C in tenantId before querying")
        void urlDecodesTenantId() {
            String encodedTenantId = "user%7Ctenant-abc";
            String decodedTenantId = "user|tenant-abc";

            when(agentRepository.findByTenantIdOrderByCreatedAtDesc(decodedTenantId))
                .thenReturn(List.of());

            agentService.listAgents(encodedTenantId, null, null);

            verify(agentRepository).findByTenantIdOrderByCreatedAtDesc(decodedTenantId);
        }

        @Test
        @DisplayName("URL-decodes %7C in tenantId when using orgId path")
        void urlDecodesTenantIdWithOrgId() {
            String encodedTenantId = "user%7Ctenant-abc";
            String decodedTenantId = "user|tenant-abc";
            String orgId = "org-xyz";
            List<AgentEntity> all = List.of();

            when(agentRepository.findByOrganizationOrOwner(orgId, decodedTenantId))
                .thenReturn(all);
            when(orgAccessService.<AgentEntity>filterAccessible(any(), anyString(), eq(decodedTenantId), anyString(), any(), any()))
                .thenReturn(all);

            agentService.listAgents(encodedTenantId, orgId, "MEMBER");

            verify(agentRepository).findByOrganizationOrOwner(orgId, decodedTenantId);
        }
    }

    // =====================================================================
    // listAgentAvatars
    // =====================================================================

    @Nested
    @DisplayName("listAgentAvatars")
    class ListAgentAvatarsTests {

        @Test
        @DisplayName("Without orgId: hits the lightweight tenant projection - bypasses full-entity loader")
        void avatarsWithoutOrgId() {
            List<AgentAvatarResponse> expected = List.of(
                new AgentAvatarResponse(AGENT_ID, "https://cdn/a.png"));
            when(agentRepository.findAvatarsByTenantId(TENANT_ID)).thenReturn(expected);

            List<AgentAvatarResponse> result =
                agentService.listAgentAvatars(TENANT_ID, null, null);

            assertThat(result).isSameAs(expected);
            verify(agentRepository).findAvatarsByTenantId(TENANT_ID);
            verify(agentRepository, never()).findByTenantIdOrderByCreatedAtDesc(anyString());
            verify(agentRepository, never()).findAvatarsByOrganizationOrOwner(anyString(), anyString());
        }

        @Test
        @DisplayName("Blank orgId: same as null - no org branch")
        void avatarsWithBlankOrgId() {
            when(agentRepository.findAvatarsByTenantId(TENANT_ID)).thenReturn(List.of());

            agentService.listAgentAvatars(TENANT_ID, "  ", null);

            verify(agentRepository).findAvatarsByTenantId(TENANT_ID);
            verify(agentRepository, never()).findAvatarsByOrganizationOrOwner(anyString(), anyString());
        }

        @Test
        @DisplayName("With orgId: hits org projection and applies orgAccessService restriction filter")
        void avatarsWithOrgId() {
            String orgId = "org-xyz";
            List<AgentAvatarResponse> all = List.of(
                new AgentAvatarResponse(AGENT_ID, "https://cdn/a.png"));
            List<AgentAvatarResponse> filtered = List.of();

            when(agentRepository.findAvatarsByOrganizationOrOwner(orgId, TENANT_ID)).thenReturn(all);
            when(orgAccessService.filterAccessible(
                eq(all), eq(orgId), eq(TENANT_ID), eq("agent"), eq("MEMBER"), any()))
                .thenReturn(filtered);

            List<AgentAvatarResponse> result =
                agentService.listAgentAvatars(TENANT_ID, orgId, "MEMBER");

            assertThat(result).isSameAs(filtered);
            verify(agentRepository).findAvatarsByOrganizationOrOwner(orgId, TENANT_ID);
            verify(orgAccessService).filterAccessible(
                eq(all), eq(orgId), eq(TENANT_ID), eq("agent"), eq("MEMBER"), any());
        }

        @Test
        @DisplayName("URL-decodes %7C in tenantId before querying - matches listAgents contract")
        void avatarsUrlDecodeTenantId() {
            String encodedTenantId = "user%7Ctenant-abc";
            String decodedTenantId = "user|tenant-abc";
            when(agentRepository.findAvatarsByTenantId(decodedTenantId)).thenReturn(List.of());

            agentService.listAgentAvatars(encodedTenantId, null, null);

            verify(agentRepository).findAvatarsByTenantId(decodedTenantId);
        }
    }

    // =====================================================================
    // updateAgent
    // =====================================================================

    @Nested
    @DisplayName("updateAgent")
    class UpdateAgentTests {

        @Test
        @DisplayName("Throws IllegalArgumentException when agent not found")
        void throwsWhenAgentNotFound() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> agentService.updateAgent(
                AGENT_ID, TENANT_ID, "New Name", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found");
        }

        @Test
        @DisplayName("Throws IllegalArgumentException when tenant mismatches")
        void throwsOnTenantMismatch() {
            AgentEntity entity = agentWith(AGENT_ID, "other-tenant", "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> agentService.updateAgent(
                AGENT_ID, TENANT_ID, "New Name", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant mismatch");
        }

        @Test
        @DisplayName("PR-2.f regression - restricted MEMBER on org-scoped agent gets OrgAccessDeniedException, no save")
        void restrictedMemberCannotUpdateOrgScopedAgent() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Org Agent");
            entity.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            // Strict-isolation (2026-05-18) requires the caller to be acting
            // in the agent's workspace before the canWrite deny-list fires.
            // Pre-2026-05-18 the lax owner-OR-org predicate let the canWrite
            // path run regardless of caller's active workspace; strict aligns
            // the test with the production code path (caller in org-42, then
            // restricted from this specific agent).
            when(orgAccessService.canWrite("org-42", TENANT_ID, "agent",
                    AGENT_ID.toString(), null)).thenReturn(false);

            assertThatThrownBy(() -> agentService.updateAgent(
                    AGENT_ID, TENANT_ID, "Mutated Name",
                    null, null, null, null,
                    BigDecimal.valueOf(0.7), null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, "org-42"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent");

            // The mutation must not reach the repo if canWrite denied.
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Strict-isolation 2026-05-18 - caller in OrgA cannot update their own personal agent (org=NULL)")
        void callerInOrgCannotUpdatePersonalAgent() {
            // Regression for the bug closed by the 2026-05-18 strict-isolation
            // alignment: pre-fix, ownerMatch=true (tenantId matched) bypassed
            // the scope check even though caller was in OrgA workspace. The
            // agent is in personal scope (organizationId=null); attempting to
            // mutate from an org workspace must now fail with the personal
            // tenant-mismatch IllegalArgumentException.
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Personal Agent");
            entity.setOrganizationId(null);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> agentService.updateAgent(
                    AGENT_ID, TENANT_ID, "Mutated Name",
                    null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, "org-A"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenant mismatch");

            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Strict-isolation 2026-05-18 - caller in personal cannot update an org-tagged agent they own")
        void callerInPersonalCannotUpdateOrgAgent() {
            // Symmetric regression: caller has switched back to personal
            // workspace, but the agent is still tagged with their org. The
            // strict predicate rejects via OrgAccessDeniedException because
            // agent.organizationId is non-null.
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Org Agent");
            entity.setOrganizationId("org-A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> agentService.updateAgent(
                    AGENT_ID, TENANT_ID, "Mutated Name",
                    null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent");

            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Updates only non-null optional fields; always updates name")
        void updatesOnlyNonNullFields() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Old Name");
            entity.setDescription("Old Desc");
            entity.setModelProvider("openai");
            entity.setModelName("gpt-4");

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            // toolsConfig is null → circular-check is skipped (no findByTenantIdForUpdate call)
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "New Name",
                null,          // description null - not updated
                "New Prompt",  // systemPrompt updated
                null,          // modelProvider null - not updated
                null,          // modelName null - not updated
                BigDecimal.valueOf(1.0), null, null, null,
                null, null, null, null, null, null, null, null,
                null, null
            );

            assertThat(result.getName()).isEqualTo("New Name");
            assertThat(result.getDescription()).isEqualTo("Old Desc");
            assertThat(result.getSystemPrompt()).isEqualTo("New Prompt");
            assertThat(result.getModelProvider()).isEqualTo("openai");
            assertThat(result.getModelName()).isEqualTo("gpt-4");
            assertThat(result.getTemperature()).isEqualByComparingTo(BigDecimal.valueOf(1.0));
            verify(agentRepository).save(entity);
        }

        @Test
        @DisplayName("Calls validateNoCircularAgentReferences via updateAgent")
        void callsCircularCheck() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.findByTenantIdForUpdate(TENANT_ID)).thenReturn(List.of(entity));
            stubSaveReturnsArgument();

            Map<String, Object> toolsConfig = toolsConfigWithAgents(UUID.randomUUID().toString());

            // Should not throw - circular check passes for a sub-agent not in the graph
            agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                toolsConfig, null, null, null, null, null, null, null,
                null, null
            );

            verify(agentRepository).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Nullifying conversationId is allowed (update sets it unconditionally)")
        void allowsClearingConversationId() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setConversationId(UUID.randomUUID());

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            // toolsConfig is null → circular-check is skipped, no findByTenantIdForUpdate call
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                null, null, null,
                null,    // conversationId = null → should clear it
                null, null, null, null,
                null, null
            );

            assertThat(result.getConversationId()).isNull();
        }
    }

    @Nested
    @DisplayName("updateAgent - field preservation (null means keep existing)")
    class UpdateFieldPreservation {

        @Test
        @DisplayName("toolsConfig null → existing toolsConfig preserved")
        void toolsConfigNullPreservesExisting() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            Map<String, Object> existingConfig = new HashMap<>();
            existingConfig.put("mode", "all");
            existingConfig.put("tables", List.of(1, 2, 3));
            existingConfig.put("workflows", List.of("wf-1"));
            entity.setToolsConfig(existingConfig);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                null, // toolsConfig null → keep existing
                null, null, null, null, null, null, null, null, null);

            assertThat(result.getToolsConfig()).isEqualTo(existingConfig);
            assertThat(result.getToolsConfig().get("tables")).isEqualTo(List.of(1, 2, 3));
            assertThat(result.getToolsConfig().get("workflows")).isEqualTo(List.of("wf-1"));
        }

        @Test
        @DisplayName("Non-null toolsConfig MERGES into existing - keys not in patch are preserved (regression)")
        void restMergePreservesUnrelatedResourceKeys() {
            // Pre-fix: AgentService.updateAgent did `existing.setToolsConfig(toolsConfig)`
            // - a full REPLACE. The frontend modal could PUT `{mode: "none",
            // workflows: ["wf-new"]}` and silently wipe `tables`, `interfaces`, `agents`,
            // `applications`. Combined with the (now-fixed) absent-key-means-all reader,
            // the agent ended up with unrestricted access to every other category.
            //
            // Post-fix: AgentService.updateAgent merges the patch into existing AND
            // normalizes (any of the 5 internal keys still missing → []). REST PUT and
            // the LLM `agent(action='update')` tool path now share identical semantics.
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            Map<String, Object> existingConfig = new HashMap<>();
            existingConfig.put("mode", "all");
            existingConfig.put("tables", List.of(1, 2, 3));
            existingConfig.put("interfaces", List.of("if-1"));
            existingConfig.put("agents", List.of("ag-1"));
            existingConfig.put("applications", List.of("app-1"));
            entity.setToolsConfig(existingConfig);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            Map<String, Object> patch = new HashMap<>();
            patch.put("mode", "none");
            patch.put("workflows", List.of("wf-new"));

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                patch,
                null, null, null, null, null, null, null, null, null);

            // Patch keys overlay
            assertThat(result.getToolsConfig().get("mode")).isEqualTo("none");
            assertThat(result.getToolsConfig().get("workflows")).isEqualTo(List.of("wf-new"));
            // Pre-existing keys NOT in patch are preserved
            assertThat(result.getToolsConfig().get("tables")).isEqualTo(List.of(1, 2, 3));
            assertThat(result.getToolsConfig().get("interfaces")).isEqualTo(List.of("if-1"));
            assertThat(result.getToolsConfig().get("agents")).isEqualTo(List.of("ag-1"));
            assertThat(result.getToolsConfig().get("applications")).isEqualTo(List.of("app-1"));
        }

        @Test
        @DisplayName("Update on legacy agent missing internal keys backfills them with [] (regression)")
        void updateNormalizesAbsentInternalKeysToEmpty() {
            // Legacy agent persisted before V163 with absent `tables`/`interfaces`/...
            // Pre-fix: those keys stayed absent → runtime treated as "no restriction"
            // → silent over-permission. Post-fix: any update - even one that doesn't
            // touch those keys - backfills them with [] via normalizeToolsConfig.
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            Map<String, Object> legacyConfig = new HashMap<>();
            legacyConfig.put("mode", "all");
            legacyConfig.put("workflows", List.of("wf-1"));
            // No tables / interfaces / agents / applications - legacy shape
            entity.setToolsConfig(legacyConfig);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            // Patch only touches `mode`
            Map<String, Object> patch = new HashMap<>();
            patch.put("mode", "custom");
            patch.put("tools", List.of("github:create_issue"));

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                patch,
                null, null, null, null, null, null, null, null, null);

            assertThat(result.getToolsConfig().get("workflows")).isEqualTo(List.of("wf-1"));
            assertThat(result.getToolsConfig().get("tables")).isEqualTo(List.of());
            assertThat(result.getToolsConfig().get("interfaces")).isEqualTo(List.of());
            assertThat(result.getToolsConfig().get("agents")).isEqualTo(List.of());
            assertThat(result.getToolsConfig().get("applications")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("description null → existing description preserved")
        void descriptionPreserved() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setDescription("Important description");

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "New Name", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

            assertThat(result.getDescription()).isEqualTo("Important description");
        }

        @Test
        @DisplayName("systemPrompt null → existing systemPrompt preserved")
        void systemPromptPreserved() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setSystemPrompt("You are a helpful assistant");

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

            assertThat(result.getSystemPrompt()).isEqualTo("You are a helpful assistant");
        }

        @Test
        @DisplayName("modelProvider/modelName null → existing model preserved")
        void modelPreserved() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setModelProvider("anthropic");
            entity.setModelName("claude-sonnet-4-6");

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

            assertThat(result.getModelProvider()).isEqualTo("anthropic");
            assertThat(result.getModelName()).isEqualTo("claude-sonnet-4-6");
        }

        @Test
        @DisplayName("temperature/maxTokens/maxIterations null → existing values preserved")
        void numericFieldsPreserved() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setTemperature(BigDecimal.valueOf(0.9));
            entity.setMaxTokens(8192);
            entity.setMaxIterations(30);
            entity.setExecutionTimeout(900);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

            assertThat(result.getTemperature()).isEqualByComparingTo(BigDecimal.valueOf(0.9));
            assertThat(result.getMaxTokens()).isEqualTo(8192);
            assertThat(result.getMaxIterations()).isEqualTo(30);
            assertThat(result.getExecutionTimeout()).isEqualTo(900);
        }

        @Test
        @DisplayName("isPublic/isActive null → existing booleans preserved")
        void booleansPreserved() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setIsPublic(true);
            entity.setIsActive(false);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

            assertThat(result.getIsPublic()).isTrue();
            assertThat(result.getIsActive()).isFalse();
        }
    }

    // =====================================================================
    // cloneAgent
    // =====================================================================

    @Nested
    @DisplayName("cloneAgent")
    class CloneAgentTests {

        @Test
        @DisplayName("Creates copy with name suffixed by ' (Copy)'")
        void addscopySuffix() {
            AgentEntity source = agentWith(AGENT_ID, TENANT_ID, "My Agent");
            source.setDescription("Some description");
            source.setSystemPrompt("System prompt");
            source.setConversationId(UUID.randomUUID());

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(source));
            stubSaveReturnsArgument();

            AgentEntity clone = agentService.cloneAgent(AGENT_ID, TENANT_ID);

            assertThat(clone.getName()).isEqualTo("My Agent (Copy)");
        }

        @Test
        @DisplayName("Clone has conversationId set to null")
        void cloneHasNullConversationId() {
            AgentEntity source = agentWith(AGENT_ID, TENANT_ID, "My Agent");
            source.setConversationId(UUID.randomUUID());

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(source));
            stubSaveReturnsArgument();

            AgentEntity clone = agentService.cloneAgent(AGENT_ID, TENANT_ID);

            assertThat(clone.getConversationId()).isNull();
        }

        @Test
        @DisplayName("Clone copies description, systemPrompt, modelProvider, modelName, temperature, avatarUrl")
        void copiesCoreFields() {
            AgentEntity source = agentWith(AGENT_ID, TENANT_ID, "My Agent");
            source.setDescription("A description");
            source.setSystemPrompt("A system prompt");
            source.setModelProvider("anthropic");
            source.setModelName("claude-3-sonnet");
            source.setAvatarUrl("preset:blue");

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(source));
            stubSaveReturnsArgument();

            AgentEntity clone = agentService.cloneAgent(AGENT_ID, TENANT_ID);

            assertThat(clone.getDescription()).isEqualTo("A description");
            assertThat(clone.getSystemPrompt()).isEqualTo("A system prompt");
            assertThat(clone.getModelProvider()).isEqualTo("anthropic");
            assertThat(clone.getModelName()).isEqualTo("claude-3-sonnet");
            assertThat(clone.getAvatarUrl()).isEqualTo("preset:blue");
        }

        @Test
        @DisplayName("Clone preserves the timeout columns (executionTimeout + V372 inactivityTimeout)")
        void clonePreservesTimeoutColumns() {
            AgentEntity source = agentWith(AGENT_ID, TENANT_ID, "Watched Agent");
            source.setExecutionTimeout(1800);
            source.setInactivityTimeout(600);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(source));
            stubSaveReturnsArgument();

            AgentEntity clone = agentService.cloneAgent(AGENT_ID, TENANT_ID);

            assertThat(clone.getExecutionTimeout()).isEqualTo(1800);
            // V372 - cloneAgent must copy the inactivity column; without it a duplicated agent
            // silently reverts to the 5-min default watchdog while executionTimeout is preserved.
            assertThat(clone.getInactivityTimeout()).isEqualTo(600);
        }

        @Test
        @DisplayName("Clone preserves a 0 (disabled) inactivity window verbatim, not the default")
        void clonePreservesDisabledInactivity() {
            AgentEntity source = agentWith(AGENT_ID, TENANT_ID, "Unwatched Agent");
            source.setInactivityTimeout(0);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(source));
            stubSaveReturnsArgument();

            AgentEntity clone = agentService.cloneAgent(AGENT_ID, TENANT_ID);

            assertThat(clone.getInactivityTimeout()).isEqualTo(0);
        }

        @Test
        @DisplayName("Throws when source agent not found")
        void throwsWhenSourceNotFound() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> agentService.cloneAgent(AGENT_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found");
        }

        @Test
        @DisplayName("Throws when tenant mismatches source")
        void throwsOnTenantMismatch() {
            AgentEntity source = agentWith(AGENT_ID, "other-tenant", "My Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(source));

            assertThatThrownBy(() -> agentService.cloneAgent(AGENT_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant mismatch");
        }

        @Test
        @DisplayName("Calls agentRepository.save for the new clone")
        void callsRepositorySave() {
            AgentEntity source = agentWith(AGENT_ID, TENANT_ID, "My Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(source));
            stubSaveReturnsArgument();

            agentService.cloneAgent(AGENT_ID, TENANT_ID);

            verify(agentRepository).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Cloning a legacy agent backfills absent internal-resource keys with [] (regression)")
        void cloneAgentNormalizesAbsentResourceKeysToEmpty() {
            // Legacy source agent with `tools_config` missing 4 of the 5 internal keys.
            // Pre-fix: clone deep-copied the raw map → leak preserved at scale (every
            // clone is a new vulnerable agent). Post-fix: cloneAgent routes through
            // normalizeToolsConfig → the 5 keys exist on the clone with [] for any that
            // were absent on the source.
            AgentEntity source = agentWith(AGENT_ID, TENANT_ID, "My Agent");
            Map<String, Object> legacyConfig = new HashMap<>();
            legacyConfig.put("mode", "all");
            legacyConfig.put("workflows", List.of("wf-1"));
            // No tables / interfaces / agents / applications
            source.setToolsConfig(legacyConfig);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(source));
            stubSaveReturnsArgument();

            AgentEntity clone = agentService.cloneAgent(AGENT_ID, TENANT_ID);

            assertThat(clone.getToolsConfig().get("workflows")).isEqualTo(List.of("wf-1"));
            assertThat(clone.getToolsConfig().get("tables")).isEqualTo(List.of());
            assertThat(clone.getToolsConfig().get("interfaces")).isEqualTo(List.of());
            assertThat(clone.getToolsConfig().get("agents")).isEqualTo(List.of());
            assertThat(clone.getToolsConfig().get("applications")).isEqualTo(List.of());
        }
    }

    // =====================================================================
    // deleteAgent
    // =====================================================================

    @Nested
    @DisplayName("deleteAgent")
    class DeleteAgentTests {

        @Test
        @DisplayName("Throws when agent not found")
        void throwsWhenNotFound() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> agentService.deleteAgent(AGENT_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found");
        }

        @Test
        @DisplayName("Throws when tenant mismatches")
        void throwsOnTenantMismatch() {
            AgentEntity entity = agentWith(AGENT_ID, "other-tenant", "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> agentService.deleteAgent(AGENT_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant mismatch");
        }

        @Test
        @DisplayName("Calls agentRepository.delete and cleans up metrics when owner and agent exist")
        void deletesAgentWhenValid() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            agentService.deleteAgent(AGENT_ID, TENANT_ID);

            verify(metricsAggregationRepository).deleteExecutionsByAgent(TENANT_ID, AGENT_ID);
            verify(metricsAggregationRepository).deleteToolCallStatsByAgent(TENANT_ID, AGENT_ID);
            verify(metricsAggregationRepository).deleteSubAgentCallStats(TENANT_ID, AGENT_ID);
            verify(agentRepository).delete(entity);
        }

        @Test
        @DisplayName("Deletes webhook tokens on agent deletion")
        void deletesWebhookOnAgentDeletion() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            agentService.deleteAgent(AGENT_ID, TENANT_ID);

            verify(webhookTokenService).deleteWebhook(AGENT_ID);
        }

        @Test
        @DisplayName("Agent deletion succeeds even when webhook cleanup fails")
        void deletionSucceedsWhenWebhookCleanupFails() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
                .when(webhookTokenService).deleteWebhook(AGENT_ID);

            agentService.deleteAgent(AGENT_ID, TENANT_ID);

            // Agent should still be deleted even when webhook cleanup fails
            verify(agentRepository).delete(entity);
        }

        @Test
        @DisplayName("Deletes schedule in trigger-service on agent deletion")
        void deletesScheduleOnAgentDeletion() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            agentService.deleteAgent(AGENT_ID, TENANT_ID);

            ArgumentCaptor<org.springframework.http.HttpEntity> requestEntityCaptor =
                ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
            verify(restTemplate).exchange(
                org.mockito.ArgumentMatchers.eq("http://localhost:8091/api/internal/trigger/schedules/by-agent/" + AGENT_ID),
                org.mockito.ArgumentMatchers.eq(org.springframework.http.HttpMethod.DELETE),
                requestEntityCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Void.class));
            assertThat(requestEntityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("Agent deletion succeeds even when schedule cleanup fails")
        void deletionSucceedsWhenScheduleCleanupFails() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.contains("/schedules/by-agent/"),
                org.mockito.ArgumentMatchers.eq(org.springframework.http.HttpMethod.DELETE),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Void.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

            agentService.deleteAgent(AGENT_ID, TENANT_ID);

            // Agent should still be deleted even when schedule cleanup fails
            verify(agentRepository).delete(entity);
        }

        @Test
        @DisplayName("Cleanup order: conversations → metrics → webhook → schedule → entity")
        void deletionCleanupOrder() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            agentService.deleteAgent(AGENT_ID, TENANT_ID);

            var inOrder = org.mockito.Mockito.inOrder(
                metricsAggregationRepository, webhookTokenService, restTemplate, agentRepository);
            inOrder.verify(metricsAggregationRepository).deleteExecutionsByAgent(TENANT_ID, AGENT_ID);
            inOrder.verify(webhookTokenService).deleteWebhook(AGENT_ID);
            inOrder.verify(restTemplate).exchange(
                org.mockito.ArgumentMatchers.contains("/schedules/by-agent/"),
                org.mockito.ArgumentMatchers.eq(org.springframework.http.HttpMethod.DELETE),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Void.class));
            inOrder.verify(agentRepository).delete(entity);
        }

        @Test
        @DisplayName("Cascade delete forwards the agent's org to the conversation client (org-blind regression)")
        void forwardsAgentOrgToConversationCascade() {
            com.apimarketplace.conversation.client.ConversationClient conversationClient =
                    org.mockito.Mockito.mock(com.apimarketplace.conversation.client.ConversationClient.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    agentService, "conversationServiceClient", conversationClient);

            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Team Agent");
            entity.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            // Caller acts inside the agent's own org workspace (passes the strict-scope gate).
            agentService.deleteAgent(AGENT_ID, TENANT_ID, "ADMIN", "org-42");

            // Pre-fix the cascade called the 2-arg overload (orgId defaulted to null),
            // which made the service-side strict-scope gate skip the org-tagged
            // conversation → orphaned, still-active row after the agent was gone.
            // The fix forwards the agent's own org so the cascade matches it.
            verify(conversationClient)
                    .deleteConversationsByAgentId(AGENT_ID.toString(), TENANT_ID, "org-42");
            verify(conversationClient, never())
                    .deleteConversationsByAgentId(anyString(), anyString());
        }

        @Test
        @DisplayName("Cascade delete forwards null org for a personal agent (back-compat)")
        void forwardsNullOrgForPersonalAgentCascade() {
            com.apimarketplace.conversation.client.ConversationClient conversationClient =
                    org.mockito.Mockito.mock(com.apimarketplace.conversation.client.ConversationClient.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    agentService, "conversationServiceClient", conversationClient);

            // organizationId left null → personal-scope agent. Forwarding null is
            // behaviourally identical to the old 2-arg call for personal rows
            // (ScopeGuard's personal branch requires org IS NULL), but explicit.
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Personal Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            agentService.deleteAgent(AGENT_ID, TENANT_ID);

            verify(conversationClient)
                    .deleteConversationsByAgentId(AGENT_ID.toString(), TENANT_ID, (String) null);
        }
    }

    // =====================================================================
    // assignToProject / removeFromProject / unassignAllFromProject
    // =====================================================================

    @Nested
    @DisplayName("Project assignment methods")
    class ProjectAssignmentTests {

        private final UUID PROJECT_ID = UUID.randomUUID();

        @Test
        @DisplayName("assignToProject returns true and sets projectId when tenant matches")
        void assignToProjectSetsProjectId() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            boolean result = agentService.assignToProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isTrue();
            assertThat(entity.getProjectId()).isEqualTo(PROJECT_ID);
            verify(agentRepository).save(entity);
        }

        @Test
        @DisplayName("assignToProject returns false when agent not found")
        void assignToProjectReturnsFalseWhenNotFound() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            boolean result = agentService.assignToProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isFalse();
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("assignToProject returns false when tenant mismatches")
        void assignToProjectReturnsFalseWhenTenantMismatch() {
            AgentEntity entity = agentWith(AGENT_ID, "other-tenant", "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            boolean result = agentService.assignToProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isFalse();
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("assignToProject rejects same-tenant agent from another workspace")
        void assignToProjectRejectsSameTenantAgentFromAnotherWorkspace() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setOrganizationId("org-personal");
            org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
            request.addHeader("X-Organization-ID", "org-acme");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(request));
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            try {
                boolean result = agentService.assignToProject(AGENT_ID, PROJECT_ID, TENANT_ID);

                assertThat(result).isFalse();
                verify(agentRepository, never()).save(any());
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        @DisplayName("assignToProject allows teammate agent in current workspace")
        void assignToProjectAllowsTeammateAgentInCurrentWorkspace() {
            AgentEntity entity = agentWith(AGENT_ID, "other-tenant", "Agent A");
            entity.setOrganizationId("org-acme");
            org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
            request.addHeader("X-Organization-ID", "org-acme");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(request));
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            try {
                boolean result = agentService.assignToProject(AGENT_ID, PROJECT_ID, TENANT_ID);

                assertThat(result).isTrue();
                assertThat(entity.getProjectId()).isEqualTo(PROJECT_ID);
                verify(agentRepository).save(entity);
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        @DisplayName("removeFromProject returns true and clears projectId when tenant and project match")
        void removeFromProjectClearsProjectId() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setProjectId(PROJECT_ID);

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            boolean result = agentService.removeFromProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isTrue();
            assertThat(entity.getProjectId()).isNull();
            verify(agentRepository).save(entity);
        }

        @Test
        @DisplayName("removeFromProject returns false when project does not match")
        void removeFromProjectReturnsFalseWhenProjectMismatch() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            entity.setProjectId(UUID.randomUUID()); // different project

            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            boolean result = agentService.removeFromProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isFalse();
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("removeFromProject returns false when agent not found")
        void removeFromProjectReturnsFalseWhenNotFound() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            boolean result = agentService.removeFromProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("unassignAllFromProject clears projectId on all matching agents")
        void unassignAllFromProjectClearsAll() {
            AgentEntity agent1 = agentWith(UUID.randomUUID(), TENANT_ID, "Agent 1");
            agent1.setProjectId(PROJECT_ID);
            AgentEntity agent2 = agentWith(UUID.randomUUID(), TENANT_ID, "Agent 2");
            agent2.setProjectId(PROJECT_ID);

            when(agentRepository.findByProjectIdAndTenantId(PROJECT_ID, TENANT_ID))
                .thenReturn(List.of(agent1, agent2));
            stubSaveReturnsArgument();

            agentService.unassignAllFromProject(PROJECT_ID, TENANT_ID);

            assertThat(agent1.getProjectId()).isNull();
            assertThat(agent2.getProjectId()).isNull();
            verify(agentRepository, times(2)).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("unassignAllFromProject is a no-op when no agents are in the project")
        void unassignAllFromProjectNoOp() {
            when(agentRepository.findByProjectIdAndTenantId(PROJECT_ID, TENANT_ID))
                .thenReturn(List.of());

            agentService.unassignAllFromProject(PROJECT_ID, TENANT_ID);

            verify(agentRepository, never()).save(any());
        }
    }

    // =====================================================================
    // validateNoCircularAgentReferences (tested indirectly via updateAgent)
    // =====================================================================

    @Nested
    @DisplayName("validateNoCircularAgentReferences (via updateAgent)")
    class CircularReferenceTests {

        @Test
        @DisplayName("Throws when agent references itself as a sub-agent (self-reference)")
        void throwsOnSelfReference() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            // Self-reference is detected before the graph-building query, so
            // findByTenantIdForUpdate must NOT be stubbed here.

            Map<String, Object> selfRef = toolsConfigWithAgents(AGENT_ID.toString());

            assertThatThrownBy(() -> agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                selfRef, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot reference itself");
        }

        @Test
        @DisplayName("Throws when a circular reference A→B→A is detected")
        void throwsOnTransitiveCircularReference() {
            UUID agentAId = AGENT_ID;
            UUID agentBId = UUID.randomUUID();

            // Agent B's current toolsConfig already references Agent A
            AgentEntity agentA = agentWith(agentAId, TENANT_ID, "Agent A");
            AgentEntity agentB = agentWith(agentBId, TENANT_ID, "Agent B");
            agentB.setToolsConfig(toolsConfigWithAgents(agentAId.toString()));

            when(agentRepository.findById(agentAId)).thenReturn(Optional.of(agentA));
            when(agentRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(agentA, agentB));

            // Now we try to make Agent A reference Agent B → A→B→A cycle
            Map<String, Object> toolsConfig = toolsConfigWithAgents(agentBId.toString());

            assertThatThrownBy(() -> agentService.updateAgent(
                agentAId, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                toolsConfig, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular agent reference detected");
        }

        @Test
        @DisplayName("No exception when sub-agent chain has no cycle")
        void passesWhenNoCycle() {
            UUID agentAId = AGENT_ID;
            UUID agentBId = UUID.randomUUID();
            UUID agentCId = UUID.randomUUID();

            // Agent B references Agent C (no cycle)
            AgentEntity agentA = agentWith(agentAId, TENANT_ID, "Agent A");
            AgentEntity agentB = agentWith(agentBId, TENANT_ID, "Agent B");
            agentB.setToolsConfig(toolsConfigWithAgents(agentCId.toString()));
            AgentEntity agentC = agentWith(agentCId, TENANT_ID, "Agent C");

            when(agentRepository.findById(agentAId)).thenReturn(Optional.of(agentA));
            when(agentRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(agentA, agentB, agentC));
            stubSaveReturnsArgument();

            // Agent A references Agent B - chain A→B→C, no cycle
            Map<String, Object> toolsConfig = toolsConfigWithAgents(agentBId.toString());

            agentService.updateAgent(
                agentAId, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                toolsConfig, null, null, null, null, null, null, null,
                null, null
            );

            verify(agentRepository).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("No cycle check when toolsConfig is null")
        void skipsCheckWhenToolsConfigNull() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            // Do NOT stub findByTenantIdForUpdate - it must not be called
            stubSaveReturnsArgument();

            // toolsConfig = null - circular-check returns immediately
            agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null
            );

            // Graph-building query must not run when toolsConfig is null
            verify(agentRepository, never()).findByTenantIdForUpdate(anyString());
            verify(agentRepository).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("No cycle check when agents list in toolsConfig is empty")
        void skipsCheckWhenAgentsListEmpty() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("agents", new ArrayList<>());

            agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                toolsConfig, null, null, null, null, null, null, null,
                null, null
            );

            verify(agentRepository, never()).findByTenantIdForUpdate(anyString());
            verify(agentRepository).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Throws on transitive 3-node cycle A→B→C→A")
        void throwsOnThreeNodeCycle() {
            UUID aId = AGENT_ID;
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();

            AgentEntity a = agentWith(aId, TENANT_ID, "Agent A");
            AgentEntity b = agentWith(bId, TENANT_ID, "Agent B");
            b.setToolsConfig(toolsConfigWithAgents(cId.toString()));
            AgentEntity c = agentWith(cId, TENANT_ID, "Agent C");
            c.setToolsConfig(toolsConfigWithAgents(aId.toString())); // C→A already exists

            when(agentRepository.findById(aId)).thenReturn(Optional.of(a));
            when(agentRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(a, b, c));

            // A→B would create A→B→C→A cycle
            Map<String, Object> toolsConfig = toolsConfigWithAgents(bId.toString());

            assertThatThrownBy(() -> agentService.updateAgent(
                aId, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                toolsConfig, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular agent reference detected");
        }

        @Test
        @DisplayName("Allows diamond shape A→B, A→C, B→D, C→D (no cycle)")
        void allowsDiamondShape() {
            UUID aId = AGENT_ID;
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();
            UUID dId = UUID.randomUUID();

            AgentEntity a = agentWith(aId, TENANT_ID, "Agent A");
            AgentEntity b = agentWith(bId, TENANT_ID, "Agent B");
            b.setToolsConfig(toolsConfigWithAgents(dId.toString()));
            AgentEntity c = agentWith(cId, TENANT_ID, "Agent C");
            c.setToolsConfig(toolsConfigWithAgents(dId.toString()));
            AgentEntity d = agentWith(dId, TENANT_ID, "Agent D");

            when(agentRepository.findById(aId)).thenReturn(Optional.of(a));
            when(agentRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(a, b, c, d));
            stubSaveReturnsArgument();

            // A→B and A→C - diamond converging on D, no cycle
            Map<String, Object> toolsConfig = toolsConfigWithAgents(bId.toString(), cId.toString());

            agentService.updateAgent(
                aId, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                toolsConfig, null, null, null, null, null, null, null, null, null);

            verify(agentRepository).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Throws on deep chain that loops back: A→B→C→D→E→A")
        void throwsOnDeepCycle() {
            UUID aId = AGENT_ID;
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();
            UUID dId = UUID.randomUUID();
            UUID eId = UUID.randomUUID();

            AgentEntity a = agentWith(aId, TENANT_ID, "A");
            AgentEntity b = agentWith(bId, TENANT_ID, "B");
            b.setToolsConfig(toolsConfigWithAgents(cId.toString()));
            AgentEntity c = agentWith(cId, TENANT_ID, "C");
            c.setToolsConfig(toolsConfigWithAgents(dId.toString()));
            AgentEntity d = agentWith(dId, TENANT_ID, "D");
            d.setToolsConfig(toolsConfigWithAgents(eId.toString()));
            AgentEntity e = agentWith(eId, TENANT_ID, "E");
            e.setToolsConfig(toolsConfigWithAgents(aId.toString())); // E→A

            when(agentRepository.findById(aId)).thenReturn(Optional.of(a));
            when(agentRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(a, b, c, d, e));

            // A→B creates A→B→C→D→E→A
            assertThatThrownBy(() -> agentService.updateAgent(
                aId, TENANT_ID, "A", null, null, null, null,
                null, null, null, null,
                toolsConfigWithAgents(bId.toString()), null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular agent reference detected");
        }

        @Test
        @DisplayName("Allows multiple sub-agents with no cycle")
        void allowsMultipleSubAgentsNoCycle() {
            UUID aId = AGENT_ID;
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();
            UUID dId = UUID.randomUUID();

            AgentEntity a = agentWith(aId, TENANT_ID, "A");
            AgentEntity b = agentWith(bId, TENANT_ID, "B");
            AgentEntity c = agentWith(cId, TENANT_ID, "C");
            AgentEntity d = agentWith(dId, TENANT_ID, "D");

            when(agentRepository.findById(aId)).thenReturn(Optional.of(a));
            when(agentRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(a, b, c, d));
            stubSaveReturnsArgument();

            // A references B, C, D - all leaf nodes, no cycle
            Map<String, Object> toolsConfig = toolsConfigWithAgents(
                    bId.toString(), cId.toString(), dId.toString());

            agentService.updateAgent(
                aId, TENANT_ID, "A", null, null, null, null,
                null, null, null, null,
                toolsConfig, null, null, null, null, null, null, null, null, null);

            verify(agentRepository).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Uses pessimistic-lock query (findByTenantIdForUpdate) for circular reference check")
        void usesPessimisticLockQueryForCircularCheck() {
            UUID agentAId = AGENT_ID;
            UUID agentBId = UUID.randomUUID();

            AgentEntity agentA = agentWith(agentAId, TENANT_ID, "Agent A");
            AgentEntity agentB = agentWith(agentBId, TENANT_ID, "Agent B");

            when(agentRepository.findById(agentAId)).thenReturn(Optional.of(agentA));
            when(agentRepository.findByTenantIdForUpdate(TENANT_ID))
                .thenReturn(List.of(agentA, agentB));
            stubSaveReturnsArgument();

            // Agent A references Agent B - no cycle, but triggers the graph-building query
            Map<String, Object> toolsConfig = toolsConfigWithAgents(agentBId.toString());

            agentService.updateAgent(
                agentAId, TENANT_ID, "Agent A", null, null, null, null,
                null, null, null, null,
                toolsConfig, null, null, null, null, null, null, null,
                null, null
            );

            // Must use the pessimistic-lock variant, NOT the old findByTenantIdOrderByCreatedAtDesc
            verify(agentRepository).findByTenantIdForUpdate(TENANT_ID);
            verify(agentRepository, never()).findByTenantIdOrderByCreatedAtDesc(anyString());
            verify(agentRepository).save(any(AgentEntity.class));
        }
    }

    // =====================================================================
    // resetBudgetIfNeeded
    // =====================================================================

    @Nested
    @DisplayName("resetBudgetIfNeeded")
    class ResetBudgetIfNeededTests {

        @Test
        @DisplayName("Returns false when agent not found")
        void returnsFalseWhenAgentNotFound() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isFalse();
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Returns false when creditBudget is null (unlimited)")
        void returnsFalseWhenNoBudget() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(null);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isFalse();
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Returns false when mode is cumulative (never auto-reset)")
        void returnsFalseWhenCumulative() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(100));
            entity.setBudgetResetMode("cumulative");
            entity.setCreditsConsumed(BigDecimal.valueOf(50));
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isFalse();
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Returns false when mode is null (treated as cumulative)")
        void returnsFalseWhenModeNull() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(100));
            entity.setBudgetResetMode(null);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isFalse();
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Monthly mode: resets via targeted CAS UPDATE when month differs from last reset")
        void monthlyResetsWhenMonthDiffers() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(100));
            entity.setBudgetResetMode("monthly");
            entity.setCreditsConsumed(BigDecimal.valueOf(80));
            Instant lastReset = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(2).toInstant();
            entity.setBudgetLastReset(lastReset);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.resetConsumedIfUnreservedAndUnchanged(
                    eq(AGENT_ID), any(Instant.class), eq(lastReset)))
                .thenReturn(1);

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isTrue();
            verify(agentRepository).resetConsumedIfUnreservedAndUnchanged(
                eq(AGENT_ID), any(Instant.class), eq(lastReset));
            verify(agentRepository, never()).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Monthly mode: returns false when CAS update loses race or reservation held")
        void monthlyReturnsFalseWhenCasUpdateFails() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(100));
            entity.setBudgetResetMode("monthly");
            entity.setCreditsConsumed(BigDecimal.valueOf(80));
            Instant lastReset = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(2).toInstant();
            entity.setBudgetLastReset(lastReset);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.resetConsumedIfUnreservedAndUnchanged(
                    eq(AGENT_ID), any(Instant.class), eq(lastReset)))
                .thenReturn(0);

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isFalse();
            verify(agentRepository, never()).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Monthly mode: does NOT reset when same month")
        void monthlyDoesNotResetSameMonth() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(100));
            entity.setBudgetResetMode("monthly");
            entity.setCreditsConsumed(BigDecimal.valueOf(80));
            // Last reset = today (same month)
            entity.setBudgetLastReset(Instant.now());
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isFalse();
            assertThat(entity.getCreditsConsumed()).isEqualByComparingTo(BigDecimal.valueOf(80));
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Weekly mode: resets via targeted CAS UPDATE when week differs from last reset")
        void weeklyResetsWhenWeekDiffers() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(50));
            entity.setBudgetResetMode("weekly");
            entity.setCreditsConsumed(BigDecimal.valueOf(45));
            Instant lastReset = ZonedDateTime.now(ZoneOffset.UTC).minusWeeks(2).toInstant();
            entity.setBudgetLastReset(lastReset);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.resetConsumedIfUnreservedAndUnchanged(
                    eq(AGENT_ID), any(Instant.class), eq(lastReset)))
                .thenReturn(1);

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isTrue();
            verify(agentRepository).resetConsumedIfUnreservedAndUnchanged(
                eq(AGENT_ID), any(Instant.class), eq(lastReset));
            verify(agentRepository, never()).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Weekly mode: does NOT reset when same week")
        void weeklyDoesNotResetSameWeek() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(50));
            entity.setBudgetResetMode("weekly");
            entity.setCreditsConsumed(BigDecimal.valueOf(45));
            // Last reset = today (same week)
            entity.setBudgetLastReset(Instant.now());
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isFalse();
            assertThat(entity.getCreditsConsumed()).isEqualByComparingTo(BigDecimal.valueOf(45));
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Falls back to createdAt when budgetLastReset is null; routes to first-reset variant")
        void fallsBackToCreatedAtWhenLastResetNull() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(100));
            entity.setBudgetResetMode("monthly");
            entity.setCreditsConsumed(BigDecimal.valueOf(80));
            entity.setBudgetLastReset(null);
            // createdAt = 2 months ago → should trigger reset
            entity.setCreatedAt(ZonedDateTime.now(ZoneOffset.UTC).minusMonths(2).toInstant());
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            // expectedLastReset == null → caller dispatches to the dedicated first-reset
            // variant, which avoids the Postgres 42P18 error from an untyped null binding.
            when(agentRepository.resetConsumedIfFirstReset(
                    eq(AGENT_ID), any(Instant.class)))
                .thenReturn(1);

            boolean result = agentService.resetBudgetIfNeeded(AGENT_ID);

            assertThat(result).isTrue();
            verify(agentRepository).resetConsumedIfFirstReset(
                eq(AGENT_ID), any(Instant.class));
            verify(agentRepository, never()).resetConsumedIfUnreservedAndUnchanged(
                any(), any(), any());
            verify(agentRepository, never()).save(any(AgentEntity.class));
        }
    }

    // =====================================================================
    // resetCredits (manual)
    // =====================================================================

    @Nested
    @DisplayName("resetCredits")
    class ResetCreditsTests {

        @Test
        @DisplayName("Resets credits via targeted UPDATE when no reservation is held")
        void resetsCreditsSuccessfully() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditsConsumed(BigDecimal.valueOf(42.5));
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.zeroCreditsConsumedById(eq(AGENT_ID), any(Instant.class)))
                .thenReturn(1);

            boolean applied = agentService.resetCredits(AGENT_ID, TENANT_ID);

            assertThat(applied).isTrue();
            verify(agentRepository).zeroCreditsConsumedById(eq(AGENT_ID), any(Instant.class));
            verify(agentRepository, never()).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Returns false when targeted UPDATE reports credits_reserved > 0 race")
        void resetsCreditsRefusedWhenReservationInFlight() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditsConsumed(BigDecimal.valueOf(42.5));
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.zeroCreditsConsumedById(eq(AGENT_ID), any(Instant.class)))
                .thenReturn(0);

            boolean applied = agentService.resetCredits(AGENT_ID, TENANT_ID);

            assertThat(applied).isFalse();
            verify(agentRepository).zeroCreditsConsumedById(eq(AGENT_ID), any(Instant.class));
            verify(agentRepository, never()).save(any(AgentEntity.class));
        }

        @Test
        @DisplayName("Throws when agent not found")
        void throwsWhenAgentNotFound() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> agentService.resetCredits(AGENT_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found");
        }

        @Test
        @DisplayName("Throws when tenant mismatches")
        void throwsOnTenantMismatch() {
            AgentEntity entity = agentWith(AGENT_ID, "other-tenant", "Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> agentService.resetCredits(AGENT_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant mismatch");
        }
    }

    // =====================================================================
    // createAgent with credit budget params
    // =====================================================================

    @Nested
    @DisplayName("createAgent - credit budget")
    class CreateAgentCreditBudgetTests {

        @BeforeEach
        void setup() {
            stubDefaults();
        }

        @Test
        @DisplayName("Sets creditBudget and budgetResetMode when provided")
        void setsCreditBudgetFields() {
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "Budget Agent", null, "prompt", null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                BigDecimal.valueOf(100), "monthly"
            );

            assertThat(result.getCreditBudget()).isEqualByComparingTo(BigDecimal.valueOf(100));
            assertThat(result.getBudgetResetMode()).isEqualTo("monthly");
        }

        @Test
        @DisplayName("Leaves creditBudget null when not provided (unlimited)")
        void leavesNullWhenNotProvided() {
            stubSaveReturnsArgument();

            AgentEntity result = agentService.createAgent(
                TENANT_ID, "No Budget Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null
            );

            assertThat(result.getCreditBudget()).isNull();
        }
    }

    // =====================================================================
    // updateAgent with credit budget params
    // =====================================================================

    @Nested
    @DisplayName("updateAgent - credit budget")
    class UpdateAgentCreditBudgetTests {

        @Test
        @DisplayName("Updates creditBudget on existing agent")
        void updatesCreditBudget() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(50));
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                BigDecimal.valueOf(200), "weekly"
            );

            assertThat(result.getCreditBudget()).isEqualByComparingTo(BigDecimal.valueOf(200));
            assertThat(result.getBudgetResetMode()).isEqualTo("weekly");
        }

        @Test
        @DisplayName("Can clear creditBudget by passing null (unlimited)")
        void canClearBudget() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(100));
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null
            );

            assertThat(result.getCreditBudget()).isNull();
        }

        @Test
        @DisplayName("Preserves creditBudget when REST patch omits the public budget field")
        void preservesBudgetWhenRestPatchOmitsCreditBudget() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setCreditBudget(BigDecimal.valueOf(100));
            entity.setBudgetResetMode("monthly");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", "Updated metadata only", null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, false
            );

            assertThat(result.getCreditBudget()).isEqualByComparingTo(BigDecimal.valueOf(100));
            assertThat(result.getBudgetResetMode()).isEqualTo("monthly");
        }
    }

    @Nested
    @DisplayName("updateAgent - guard overrides (V100 unified cap)")
    class UpdateAgentGuardOverridesTests {

        @Test
        @DisplayName("Persists all 3 guard columns on the entity (unified per-resource cap + 2 loop keys)")
        void persistsAllGuardColumns() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            Map<String, Integer> guards = new HashMap<>();
            guards.put("maxPerResourcePerTurn", 7);
            guards.put("loopIdenticalStop", 12);
            guards.put("loopConsecutiveStop", 30);

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                guards
            );

            assertThat(result.getMaxPerResourcePerTurn()).isEqualTo(7);
            assertThat(result.getLoopIdenticalStop()).isEqualTo(12);
            assertThat(result.getLoopConsecutiveStop()).isEqualTo(30);
        }

        @Test
        @DisplayName("Leaves guard columns untouched when all guard params are null")
        void leavesGuardsUntouchedWhenNull() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setMaxPerResourcePerTurn(9);
            entity.setLoopIdenticalStop(5);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null
            );

            assertThat(result.getMaxPerResourcePerTurn()).isEqualTo(9);
            assertThat(result.getLoopIdenticalStop()).isEqualTo(5);
        }

        @Test
        @DisplayName("Update normalizes reasoningEffort to canonical wire form (High → high)")
        void updateNormalizesReasoningEffortToWire() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                null,   // guardOverrides
                null,   // callerOrgId
                "High"  // reasoningEffort
            );

            assertThat(result.getReasoningEffort()).isEqualTo("high");
        }

        @Test
        @DisplayName("Update with blank reasoningEffort clears the stored value (inherit)")
        void updateBlankReasoningEffortClears() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setReasoningEffort("high");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null,
                ""  // reasoningEffort blank ⇒ clear
            );

            assertThat(result.getReasoningEffort()).isNull();
        }

        @Test
        @DisplayName("Update with null reasoningEffort leaves the stored value unchanged")
        void updateNullReasoningEffortLeavesUnchanged() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setReasoningEffort("medium");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null,
                null  // reasoningEffort null ⇒ unchanged
            );

            assertThat(result.getReasoningEffort()).isEqualTo("medium");
        }

        @Test
        @DisplayName("Update rejects an unknown reasoningEffort level (fail-loud, no silent fallback)")
        void updateRejectsUnknownReasoningEffort() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null,
                "bogus"
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("reasoningEffort");
        }

        @Test
        @DisplayName("Rejects maxPerResourcePerTurn <= 0 (mirrors DB CHECK constraint)")
        void rejectsNonPositiveMaxPerResource() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            Map<String, Integer> guards = new HashMap<>();
            guards.put("maxPerResourcePerTurn", 0);

            assertThatThrownBy(() -> agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                guards
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("maxPerResourcePerTurn must be > 0");
        }

        @Test
        @DisplayName("Rejects loopIdenticalStop < 2 (mirrors DB CHECK constraint)")
        void rejectsLoopIdenticalStopBelowTwo() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            Map<String, Integer> guards = new HashMap<>();
            guards.put("loopIdenticalStop", 1);

            assertThatThrownBy(() -> agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                guards
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("loopIdenticalStop must be >= 2");
        }

        @Test
        @DisplayName("Rejects loopConsecutiveStop < 4 (mirrors DB CHECK constraint)")
        void rejectsLoopConsecutiveStopBelowFour() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            Map<String, Integer> guards = new HashMap<>();
            guards.put("loopConsecutiveStop", 3);

            assertThatThrownBy(() -> agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                guards
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("loopConsecutiveStop must be >= 4");
        }

        @Test
        @DisplayName("Rejects retired per-resource keys (maxAgentsPerTurn / maxSkillsPerTurn / subAgentMaxPerTurn)")
        void rejectsRetiredKeys() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            Map<String, Integer> guards = new HashMap<>();
            guards.put("maxAgentsPerTurn", 7);

            assertThatThrownBy(() -> agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                guards
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("maxAgentsPerTurn");
        }

        @Test
        @DisplayName("21-arg overload delegates with null guards (no guard-override map)")
        void oldOverloadDelegatesWithNullGuards() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setMaxPerResourcePerTurn(42);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null
            );

            assertThat(result.getMaxPerResourcePerTurn()).isEqualTo(42);
        }

        @Test
        @DisplayName("Explicit null in Map resets column to NULL (revert to YAML default)")
        void explicitNullResetsColumnToNull() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Agent");
            entity.setMaxPerResourcePerTurn(9);
            entity.setLoopIdenticalStop(50);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            Map<String, Integer> guards = new HashMap<>();
            guards.put("maxPerResourcePerTurn", null);

            AgentEntity result = agentService.updateAgent(
                AGENT_ID, TENANT_ID, "Agent", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                guards
            );

            assertThat(result.getMaxPerResourcePerTurn()).isNull();
            assertThat(result.getLoopIdenticalStop()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("normalizeToolsConfig - per-family GRANT sentinel")
    class NormalizeToolsConfigGrantTests {

        @Test
        @DisplayName("Absent grant derives 'none' from an empty list (no-op for existing rows)")
        void absentGrantDerivesNoneFromEmptyList() {
            // A row with empty internal-resource lists and no grants. Each family's
            // grant is derived to "none" (deny) - the authoritative resolution for an
            // empty/absent family; the grant, not the list, is what drives access.
            Map<String, Object> input = new HashMap<>();
            input.put("mode", "all");
            input.put("workflows", List.of());
            input.put("tables", List.of());
            input.put("interfaces", List.of());
            input.put("agents", List.of());
            input.put("applications", List.of());

            Map<String, Object> out = AgentService.normalizeToolsConfig(input);

            assertThat(out.get("workflowsGrant")).isEqualTo("none");
            assertThat(out.get("tablesGrant")).isEqualTo("none");
            assertThat(out.get("interfacesGrant")).isEqualTo("none");
            assertThat(out.get("agentsGrant")).isEqualTo("none");
            assertThat(out.get("applicationsGrant")).isEqualTo("none");
            // Lists are preserved as-is.
            assertThat(out.get("workflows")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("Absent grant derives 'custom' from a non-empty list")
        void absentGrantDerivesCustomFromNonEmptyList() {
            Map<String, Object> input = new HashMap<>();
            input.put("workflows", List.of("wf-1"));
            input.put("tables", List.of("t-1", "t-2"));

            Map<String, Object> out = AgentService.normalizeToolsConfig(input);

            assertThat(out.get("workflowsGrant")).isEqualTo("custom");
            assertThat(out.get("tablesGrant")).isEqualTo("custom");
            // Families with no list at all get backfilled [] → derived "none".
            assertThat(out.get("interfacesGrant")).isEqualTo("none");
            assertThat(out.get("agentsGrant")).isEqualTo("none");
            assertThat(out.get("applicationsGrant")).isEqualTo("none");
        }

        @Test
        @DisplayName("Explicit 'all' is preserved and NOT flattened by the [] list backfill")
        void explicitAllPreservedNotFlattened() {
            // A BUILDER agent durably granted "all" workflows: the list may be empty
            // (placeholder) but the grant must survive normalize verbatim.
            Map<String, Object> input = new HashMap<>();
            input.put("mode", "all");
            input.put("workflowsGrant", "all");
            // workflows key absent on purpose → backfilled to [] as a placeholder.

            Map<String, Object> out = AgentService.normalizeToolsConfig(input);

            assertThat(out.get("workflowsGrant")).isEqualTo("all");
            assertThat(out.get("workflows")).isEqualTo(List.of()); // placeholder, grant drives
            // Other families derive "none" from their backfilled empty lists.
            assertThat(out.get("tablesGrant")).isEqualTo("none");
        }

        @Test
        @DisplayName("An UNRECOGNISED grant (e.g. 'bogus') is sanitised at the write chokepoint - derived from the list, never persisted verbatim")
        void invalidGrantIsSanitised() {
            // A junk sentinel must never be stored: a reader that isn't deny-by-default would
            // fail OPEN on it. Treat it like an absent grant → derive from the list
            // (non-empty ⇒ custom, empty ⇒ none) so only none/all/custom ever persist.
            Map<String, Object> input = new HashMap<>();
            input.put("mode", "all");
            input.put("workflowsGrant", "bogus");
            input.put("workflows", List.of("wf-1"));    // non-empty → derive custom
            input.put("interfacesGrant", "garbage");
            input.put("interfaces", List.of());          // empty → derive none
            input.put("tablesGrant", "");                // empty STRING is not a valid grant
            input.put("tables", List.of());              // empty list → derive none
            input.put("agentsGrant", " all ");           // padded - NOT equal to "all" (no trim)
            input.put("agents", List.of("a-1"));         // non-empty → derive custom

            Map<String, Object> out = AgentService.normalizeToolsConfig(input);

            assertThat(out.get("workflowsGrant")).isEqualTo("custom");   // 'bogus' sanitised → custom
            assertThat(out.get("workflows")).isEqualTo(List.of("wf-1")); // custom keeps its list
            assertThat(out.get("interfacesGrant")).isEqualTo("none");    // 'garbage' sanitised → none
            assertThat(out.get("tablesGrant")).isEqualTo("none");        // "" sanitised → none (empty list)
            assertThat(out.get("agentsGrant")).isEqualTo("custom");      // " all " (padded) → invalid → derive custom
            assertThat(out.get("agents")).isEqualTo(List.of("a-1"));     // (fail-CLOSED: padded valid grant is NOT honored as 'all')
        }

        @Test
        @DisplayName("Explicit grants of every shape are preserved (none/all/custom)")
        void explicitGrantsPreserved() {
            Map<String, Object> input = new HashMap<>();
            input.put("workflowsGrant", "all");
            input.put("tablesGrant", "none");
            input.put("interfacesGrant", "custom");
            input.put("interfaces", List.of("i-1"));
            input.put("agentsGrant", "all");
            input.put("applicationsGrant", "custom");
            input.put("applications", List.of("app-1"));

            Map<String, Object> out = AgentService.normalizeToolsConfig(input);

            assertThat(out.get("workflowsGrant")).isEqualTo("all");
            assertThat(out.get("tablesGrant")).isEqualTo("none");
            assertThat(out.get("interfacesGrant")).isEqualTo("custom");
            assertThat(out.get("agentsGrant")).isEqualTo("all");
            assertThat(out.get("applicationsGrant")).isEqualTo("custom");
        }

        @Test
        @DisplayName("Null toolsConfig seeds 5 families at grant='none' (fresh empty agent)")
        void nullSeedsAllNone() {
            Map<String, Object> out = AgentService.normalizeToolsConfig(null);

            assertThat(out.get("workflowsGrant")).isEqualTo("none");
            assertThat(out.get("tablesGrant")).isEqualTo("none");
            assertThat(out.get("interfacesGrant")).isEqualTo("none");
            assertThat(out.get("agentsGrant")).isEqualTo("none");
            assertThat(out.get("applicationsGrant")).isEqualTo("none");
        }

        @Test
        @DisplayName("Idempotent - running normalize twice yields the same grant map")
        void idempotent() {
            Map<String, Object> input = new HashMap<>();
            input.put("workflows", List.of("wf-1"));
            input.put("tablesGrant", "all");

            Map<String, Object> once = AgentService.normalizeToolsConfig(input);
            Map<String, Object> twice = AgentService.normalizeToolsConfig(once);

            assertThat(twice.get("workflowsGrant")).isEqualTo("custom");
            assertThat(twice.get("tablesGrant")).isEqualTo("all");
            assertThat(twice).containsAllEntriesOf(Map.of(
                "workflowsGrant", "custom",
                "tablesGrant", "all",
                "interfacesGrant", "none",
                "agentsGrant", "none",
                "applicationsGrant", "none"));
        }

        @Test
        @DisplayName("grant='none' with a non-empty incoming list resets the list to [] (no stale list behind 'none')")
        void noneGrantResetsStaleListToEmpty() {
            // An inconsistent row: grant flipped to 'none' but the old id list lingers.
            // The list is only the "custom" payload, so a 'none' family must carry [].
            Map<String, Object> input = new HashMap<>();
            input.put("workflowsGrant", "none");
            input.put("workflows", List.of("wf-1", "wf-2"));

            Map<String, Object> out = AgentService.normalizeToolsConfig(input);

            assertThat(out.get("workflowsGrant")).isEqualTo("none");
            assertThat(out.get("workflows")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("grant='all' with a non-empty incoming list resets the list to [] (no stale list behind 'all')")
        void allGrantResetsStaleListToEmpty() {
            // A BUILDER agent granted 'all' but with a leftover id list - 'all' is
            // unrestricted, so the list is meaningless and must be cleared to [].
            Map<String, Object> input = new HashMap<>();
            input.put("tablesGrant", "all");
            input.put("tables", List.of("t-1", "t-2", "t-3"));

            Map<String, Object> out = AgentService.normalizeToolsConfig(input);

            assertThat(out.get("tablesGrant")).isEqualTo("all");
            assertThat(out.get("tables")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("grant='custom' preserves the incoming list verbatim (list IS the custom payload)")
        void customGrantPreservesList() {
            Map<String, Object> input = new HashMap<>();
            input.put("interfacesGrant", "custom");
            input.put("interfaces", List.of("i-1", "i-2"));

            Map<String, Object> out = AgentService.normalizeToolsConfig(input);

            assertThat(out.get("interfacesGrant")).isEqualTo("custom");
            assertThat(out.get("interfaces")).isEqualTo(List.of("i-1", "i-2"));
        }

        @Test
        @DisplayName("List reset is idempotent - re-normalizing a none/all family keeps the list []")
        void listResetIsIdempotent() {
            Map<String, Object> input = new HashMap<>();
            input.put("agentsGrant", "none");
            input.put("agents", List.of("a-1"));
            input.put("applicationsGrant", "all");
            input.put("applications", List.of("app-1"));

            Map<String, Object> once = AgentService.normalizeToolsConfig(input);
            Map<String, Object> twice = AgentService.normalizeToolsConfig(once);

            assertThat(twice.get("agentsGrant")).isEqualTo("none");
            assertThat(twice.get("agents")).isEqualTo(List.of());
            assertThat(twice.get("applicationsGrant")).isEqualTo("all");
            assertThat(twice.get("applications")).isEqualTo(List.of());
        }
    }

    // =====================================================================
    // setBacklogEnabled (V340) - opt-in flag, same scope gate as updateAgent
    // =====================================================================
    @Nested
    @DisplayName("setBacklogEnabled (V340)")
    class SetBacklogEnabled {

        @Test
        @DisplayName("persists the opt-in flag on a personal-scope agent (default was false)")
        void persistsFlagPersonalScope() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            entity.setOrganizationId(null);
            assertThat(entity.isBacklogEnabled()).isFalse();
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.setBacklogEnabled(AGENT_ID, TENANT_ID, null, true);

            assertThat(result.isBacklogEnabled()).isTrue();
            verify(agentRepository).save(entity);
        }

        @Test
        @DisplayName("can turn the flag back off")
        void clearsFlag() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            entity.setOrganizationId(null);
            entity.setBacklogEnabled(true);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            stubSaveReturnsArgument();

            AgentEntity result = agentService.setBacklogEnabled(AGENT_ID, TENANT_ID, null, false);

            assertThat(result.isBacklogEnabled()).isFalse();
            verify(agentRepository).save(entity);
        }

        @Test
        @DisplayName("restricted MEMBER on an org-scoped agent is denied - no save")
        void deniedForRestrictedMember() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Org Worker");
            entity.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(orgAccessService.canWrite("org-42", TENANT_ID, "agent", AGENT_ID.toString(), null))
                    .thenReturn(false);

            assertThatThrownBy(() -> agentService.setBacklogEnabled(AGENT_ID, TENANT_ID, "org-42", true))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("cross-workspace caller (personal scope, org-tagged agent) is denied via strict scope - no save")
        void deniedCrossWorkspace() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Org Worker");
            entity.setOrganizationId("org-A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> agentService.setBacklogEnabled(AGENT_ID, TENANT_ID, null, true))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class);
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("missing agent → IllegalArgumentException, no save")
        void rejectsMissingAgent() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> agentService.setBacklogEnabled(AGENT_ID, TENANT_ID, null, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
            verify(agentRepository, never()).save(any());
        }
    }

    // =====================================================================
    // assertCanWriteAgent - satellite-config write gate (webhook/schedule/widget)
    // Mirrors the updateAgent / setBacklogEnabled scope + canWrite gate.
    // =====================================================================
    @Nested
    @DisplayName("assertCanWriteAgent (webhook/schedule/widget write gate)")
    class AssertCanWriteAgent {

        @Test
        @DisplayName("restricted MEMBER (canWrite=false) on an org-scoped agent is DENIED - OrgAccessDeniedException")
        void deniedForRestrictedMember() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Org Worker");
            entity.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            // canWrite=false => a MEMBER with a per-resource READ/DENY row on this agent.
            when(orgAccessService.canWrite("org-42", TENANT_ID, "agent", AGENT_ID.toString(), null))
                    .thenReturn(false);

            assertThatThrownBy(() -> agentService.assertCanWriteAgent(AGENT_ID, TENANT_ID, "org-42"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent");
        }

        @Test
        @DisplayName("MEMBER with write access (canWrite=true) on an org-scoped agent SUCCEEDS - no exception")
        void allowedWhenCanWriteTrue() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Org Worker");
            entity.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(orgAccessService.canWrite("org-42", TENANT_ID, "agent", AGENT_ID.toString(), null))
                    .thenReturn(true);

            // No throw == authorized to mutate webhook/schedule/widget.
            agentService.assertCanWriteAgent(AGENT_ID, TENANT_ID, "org-42");

            verify(orgAccessService).canWrite("org-42", TENANT_ID, "agent", AGENT_ID.toString(), null);
        }

        @Test
        @DisplayName("personal-scope agent (organizationId=null) has no deny-list - SUCCEEDS without a canWrite call")
        void allowedForPersonalScopeAgent() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Personal Worker");
            entity.setOrganizationId(null);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            agentService.assertCanWriteAgent(AGENT_ID, TENANT_ID, null);

            // Personal-scope short-circuits before the deny-list lookup.
            verify(orgAccessService, never()).canWrite(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("cross-workspace caller (personal scope, org-tagged agent) is DENIED via strict scope - never reaches canWrite")
        void deniedCrossWorkspace() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Org Worker");
            entity.setOrganizationId("org-A");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> agentService.assertCanWriteAgent(AGENT_ID, TENANT_ID, null))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent");
            // Strict positive-scope check fires BEFORE the deny-list lookup.
            verify(orgAccessService, never()).canWrite(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("missing agent → IllegalArgumentException")
        void rejectsMissingAgent() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> agentService.assertCanWriteAgent(AGENT_ID, TENANT_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
    }

    // =====================================================================
    // VIEWER (read-only org role) write gate - regression for AGENT-RBAC-1.
    // Pre-fix: the agent write paths gated ONLY on isInScope + canWrite. canWrite
    // bypasses OWNER/ADMIN and treats VIEWER identically to MEMBER, so with no
    // per-resource deny row (the default - modelled here by the lenient
    // canWrite=true stub) a VIEWER of the agent's org could create/update/delete/
    // clone/reset/configure the agent. Post-fix: an explicit VIEWER gate
    // (mirroring WorkflowCrudController) denies it. Each test keeps canWrite=true
    // so the VIEWER gate is provably the thing that denies (the test would PASS
    // pre-fix without the gate, i.e. the mutation would succeed - and now fails).
    // =====================================================================
    @Nested
    @DisplayName("VIEWER write gate (AGENT-RBAC-1)")
    class ViewerWriteGate {

        /** Binds X-Organization-ID/Role so TenantResolver.currentRequestOrganizationRole()
         *  resolves (the role source for create/update/setBacklog/assertCanWrite). */
        private void withRequestRole(String orgId, String role, Runnable body) {
            org.springframework.mock.web.MockHttpServletRequest req =
                    new org.springframework.mock.web.MockHttpServletRequest();
            if (orgId != null) req.addHeader("X-Organization-ID", orgId);
            if (role != null) req.addHeader("X-Organization-Role", role);
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(req));
            try {
                body.run();
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        private AgentEntity orgScopedAgent() {
            AgentEntity e = agentWith(AGENT_ID, TENANT_ID, "Org Agent");
            e.setOrganizationId("org-42");
            return e;
        }

        @Test
        @DisplayName("VIEWER cannot CREATE an org-scoped agent - OrgAccessDeniedException, no save")
        void viewerCannotCreate() {
            withRequestRole("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.createAgent(
                        TENANT_ID, "Viewer Agent", null, null, null, null,
                        null, null, null, null,
                        null, null, null, null, null,
                        null, null, null, "org-42",
                        null, null))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("VIEWER cannot UPDATE an org-scoped agent - OrgAccessDeniedException, no save (canWrite=true)")
        void viewerCannotUpdate() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            withRequestRole("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.updateAgent(
                        AGENT_ID, TENANT_ID, "Mutated Name",
                        null, null, null, null,
                        null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, null, "org-42"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("VIEWER cannot DELETE an org-scoped agent - OrgAccessDeniedException, no delete (canWrite=true)")
        void viewerCannotDelete() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            assertThatThrownBy(() -> agentService.deleteAgent(AGENT_ID, TENANT_ID, "VIEWER", "org-42"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent");
            verify(agentRepository, never()).delete(any());
        }

        @Test
        @DisplayName("VIEWER cannot CLONE an org-scoped agent - OrgAccessDeniedException, no save (canWrite=true)")
        void viewerCannotClone() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            assertThatThrownBy(() -> agentService.cloneAgent(AGENT_ID, TENANT_ID, "VIEWER", "org-42"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("VIEWER cannot RESET an org-scoped agent's budget - OrgAccessDeniedException, no zero-out (canWrite=true)")
        void viewerCannotResetCredits() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            assertThatThrownBy(() -> agentService.resetCredits(AGENT_ID, TENANT_ID, "VIEWER", "org-42"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent");
            verify(agentRepository, never()).zeroCreditsConsumedById(any(), any());
        }

        @Test
        @DisplayName("VIEWER cannot set backlogEnabled on an org-scoped agent - OrgAccessDeniedException, no save (canWrite=true)")
        void viewerCannotSetBacklog() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            withRequestRole("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.setBacklogEnabled(AGENT_ID, TENANT_ID, "org-42", true))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("VIEWER cannot mutate satellite config (assertCanWriteAgent) on an org-scoped agent - OrgAccessDeniedException (canWrite=true)")
        void viewerCannotMutateSatelliteConfig() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            withRequestRole("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.assertCanWriteAgent(AGENT_ID, TENANT_ID, "org-42"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
        }

        @Test
        @DisplayName("MEMBER (not VIEWER) CAN set backlogEnabled on the same org-scoped agent - gate is VIEWER-specific, save happens")
        void memberStillAllowed() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            stubSaveReturnsArgument();
            withRequestRole("org-42", "MEMBER", () -> {
                AgentEntity result = agentService.setBacklogEnabled(AGENT_ID, TENANT_ID, "org-42", true);
                assertThat(result.isBacklogEnabled()).isTrue();
            });
            verify(agentRepository).save(any());
        }

        @Test
        @DisplayName("VIEWER on a PERSONAL-scope agent (organizationId=null) is unaffected - gate fails open, no deny-list call")
        void viewerUnaffectedOnPersonalScopeAgent() {
            AgentEntity personal = agentWith(AGENT_ID, TENANT_ID, "Personal Agent");
            personal.setOrganizationId(null);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(personal));
            // Even with the request role bound to VIEWER, a personal-scope agent has
            // no org and the gate (like canWrite) short-circuits before any role check.
            withRequestRole(null, "VIEWER", () ->
                agentService.assertCanWriteAgent(AGENT_ID, TENANT_ID, null));
            verify(orgAccessService, never()).canWrite(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("VIEWER cannot ASSIGN the org agent to a project - OrgAccessDeniedException, no save (canWrite=true)")
        void viewerCannotAssignToProject() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            withRequestRole("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.assignToProject(AGENT_ID, UUID.randomUUID(), TENANT_ID))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("VIEWER cannot REMOVE the org agent from a project - OrgAccessDeniedException, no save (canWrite=true)")
        void viewerCannotRemoveFromProject() {
            UUID projectId = UUID.randomUUID();
            AgentEntity entity = orgScopedAgent();
            entity.setProjectId(projectId);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            withRequestRole("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.removeFromProject(AGENT_ID, projectId, TENANT_ID))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("VIEWER role bound via the ASYNC org-scope ThreadLocal (runWithOrgScope) is still denied - the gate honors the worker-thread role the async tool path now binds (regression for the async fail-open)")
        void viewerDeniedWhenRoleBoundViaAsyncThreadLocal() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(orgScopedAgent()));
            // No servlet request bound - the role resolves from the ASYNC_ORG_ROLE
            // ThreadLocal set by runWithOrgScope(orgId, orgRole, ...), which is exactly
            // what ToolsRegistrationService.executeToolAsync now binds on the worker.
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.updateAgent(
                        AGENT_ID, TENANT_ID, "Mutated Name",
                        null, null, null, null,
                        null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, null, "org-42"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
            verify(agentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("setCompactionOverrides (V350) - per-agent compaction enable + cadence")
    class CompactionOverrides {

        @Test
        @DisplayName("rejects a cadence below 1 before any DB access")
        void rejectsCadenceBelowOne() {
            assertThatThrownBy(() -> agentService.setCompactionOverrides(
                    AGENT_ID, TENANT_ID, null, false, null, true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 1");
            verify(agentRepository, never()).findById(any());
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("persists both fields on a personal agent")
        void persistsBothFields() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentEntity result = agentService.setCompactionOverrides(
                    AGENT_ID, TENANT_ID, null, true, true, true, 8);

            assertThat(result.getCompactionEnabled()).isTrue();
            assertThat(result.getCompactionAfterTurns()).isEqualTo(8);
            verify(agentRepository).save(entity);
        }

        @Test
        @DisplayName("patch semantics: an absent field (present flag false) is left untouched")
        void patchSemanticsLeavesAbsentFieldUntouched() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            entity.setCompactionAfterTurns(9); // pre-existing cadence
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            // Only the enable flag is present; cadence flag is false → cadence untouched.
            AgentEntity result = agentService.setCompactionOverrides(
                    AGENT_ID, TENANT_ID, null, true, true, false, null);

            assertThat(result.getCompactionEnabled()).isTrue();
            assertThat(result.getCompactionAfterTurns()).isEqualTo(9);
        }

        @Test
        @DisplayName("a present null clears the override back to inherit")
        void presentNullClearsTheOverride() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            entity.setCompactionEnabled(true);
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentEntity result = agentService.setCompactionOverrides(
                    AGENT_ID, TENANT_ID, null, true, null, false, null);

            assertThat(result.getCompactionEnabled()).isNull(); // cleared → inherit
        }

        @Test
        @DisplayName("unknown agent throws, no save")
        void unknownAgentThrows() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> agentService.setCompactionOverrides(
                    AGENT_ID, TENANT_ID, null, true, true, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a VIEWER on an org agent is denied by the write gate - no save")
        void viewerOnOrgAgentDenied() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            entity.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.setCompactionOverrides(
                        AGENT_ID, TENANT_ID, "org-42", true, true, true, 8))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
            verify(agentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("setCompactionModel - per-agent compaction summariser model override")
    class SetCompactionModelTests {

        @Test
        @DisplayName("persists a trimmed provider/name pair on a personal agent")
        void persistsTrimmedPair() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentEntity result = agentService.setCompactionModel(
                    AGENT_ID, TENANT_ID, null, " openai ", " gpt-5-mini ");

            assertThat(result.getCompactionModelProvider()).isEqualTo("openai");
            assertThat(result.getCompactionModelName()).isEqualTo("gpt-5-mini");
            verify(agentRepository).save(entity);
        }

        @Test
        @DisplayName("both null clears the override back to inherit")
        void bothNullClears() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            entity.setCompactionModelProvider("google");
            entity.setCompactionModelName("gemini-3-flash");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentEntity result = agentService.setCompactionModel(AGENT_ID, TENANT_ID, null, null, null);

            assertThat(result.getCompactionModelProvider()).isNull();
            assertThat(result.getCompactionModelName()).isNull();
        }

        @Test
        @DisplayName("both blank also clears - blank normalizes to null before the pair check")
        void bothBlankClears() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            entity.setCompactionModelProvider("google");
            entity.setCompactionModelName("gemini-3-flash");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentEntity result = agentService.setCompactionModel(AGENT_ID, TENANT_ID, null, "", "   ");

            assertThat(result.getCompactionModelProvider()).isNull();
            assertThat(result.getCompactionModelName()).isNull();
        }

        @Test
        @DisplayName("a partial pair (either direction, incl. a blank member) throws BEFORE any DB access")
        void partialPairThrowsBeforeRepo() {
            assertThatThrownBy(() -> agentService.setCompactionModel(
                    AGENT_ID, TENANT_ID, null, "openai", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set together");
            assertThatThrownBy(() -> agentService.setCompactionModel(
                    AGENT_ID, TENANT_ID, null, null, "gpt-5-mini"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set together");
            // A blank member counts as missing, so blank + set is partial too.
            assertThatThrownBy(() -> agentService.setCompactionModel(
                    AGENT_ID, TENANT_ID, null, "   ", "gpt-5-mini"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set together");
            verify(agentRepository, never()).findById(any());
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("unknown agent throws, no save")
        void unknownAgentThrows() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> agentService.setCompactionModel(
                    AGENT_ID, TENANT_ID, null, "openai", "gpt-5-mini"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found");
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a VIEWER on an org agent is denied by the write gate - no save")
        void viewerOnOrgAgentDenied() {
            AgentEntity entity = agentWith(AGENT_ID, TENANT_ID, "Worker");
            entity.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(entity));
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope("org-42", "VIEWER", () ->
                assertThatThrownBy(() -> agentService.setCompactionModel(
                        AGENT_ID, TENANT_ID, "org-42", "openai", "gpt-5-mini"))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("agent"));
            verify(agentRepository, never()).save(any());
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("setInactivityTimeout - per-agent inactivity watchdog window")
    class SetInactivityTimeoutTests {

        private AgentEntity noOrgAgent(String tenantId) {
            AgentEntity e = new AgentEntity();
            e.setId(AGENT_ID);
            e.setTenantId(tenantId); // no organizationId -> isInScope passes on a tenant match
            return e;
        }

        @Test
        @DisplayName("accepts null (clear to default), 0 (disabled) and the 10-7200 range, persisting the value")
        void acceptsValidValuesAndPersists() {
            for (Integer v : new Integer[]{null, 0, 10, 7200, 120}) {
                AgentEntity e = noOrgAgent(TENANT_ID);
                when(agentRepository.findById(AGENT_ID)).thenReturn(java.util.Optional.of(e));
                when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

                AgentEntity saved = agentService.setInactivityTimeout(AGENT_ID, TENANT_ID, null, v);

                assertThat(saved.getInactivityTimeout())
                    .as("value %s must be persisted on the entity", v)
                    .isEqualTo(v);
            }
        }

        @Test
        @DisplayName("rejects out-of-range values (1-9, 7201+, negative) BEFORE touching the repository")
        void rejectsOutOfRangeBeforeRepo() {
            for (int bad : new int[]{9, 7201, -5}) {
                assertThatThrownBy(() -> agentService.setInactivityTimeout(AGENT_ID, TENANT_ID, null, bad))
                    .as("value %s must be rejected", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inactivity_timeout");
            }
            verify(agentRepository, never()).findById(any());
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a tenant-mismatched agent (no org) is rejected - cannot retune another tenant's agent")
        void rejectsTenantMismatch() {
            when(agentRepository.findById(AGENT_ID)).thenReturn(java.util.Optional.of(noOrgAgent("user|someone-else")));

            assertThatThrownBy(() -> agentService.setInactivityTimeout(AGENT_ID, TENANT_ID, null, 120))
                .isInstanceOf(IllegalArgumentException.class);
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("an ORG-scoped agent is denied (OrgAccessDeniedException) when the caller is out of the agent's org scope")
        void deniesOutOfOrgScope() {
            AgentEntity orgAgent = noOrgAgent("owner-tenant");
            orgAgent.setOrganizationId("org-42");
            when(agentRepository.findById(AGENT_ID)).thenReturn(java.util.Optional.of(orgAgent));

            // Caller (TENANT_ID, no active org) is not in org-42 -> isInScope false + agentOrgId != null
            // -> the org-scoped deny path (OrgAccessDeniedException), distinct from the no-org
            // tenant-mismatch IllegalArgumentException above. canAccess is unstubbed -> false by default.
            assertThatThrownBy(() -> agentService.setInactivityTimeout(AGENT_ID, TENANT_ID, null, 120))
                .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class);
            verify(agentRepository, never()).save(any());
        }
    }
}
