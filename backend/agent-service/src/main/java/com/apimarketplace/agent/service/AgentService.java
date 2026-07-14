package com.apimarketplace.agent.service;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.ReasoningEffort;
import com.apimarketplace.agent.dto.AgentAvatarResponse;
import com.apimarketplace.agent.repository.AgentMetricsAggregationRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
public class AgentService {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agentRepository;
    private final AgentDefaultsConfig defaults;
    private final OrgAccessGuard orgAccessService;
    private final AgentMetricsAggregationRepository metricsAggregationRepository;
    private final com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard;

    // TODO: Replace with a lightweight RestTemplate call to conversation-service.
    // ConversationClient will not be available in agent-service at startup.
    @Autowired(required = false)
    private ConversationClient conversationServiceClient;

    @Autowired(required = false)
    private com.apimarketplace.agent.webhook.AgentWebhookTokenService webhookTokenService;

    @Autowired(required = false)
    private RestTemplate restTemplate;

    // Optional: present in production (PublicationClientConfig); null in unit tests that don't
    // exercise the paged-list status path. When null, the paged list skips the visibility filter and
    // emits no publication badges (best-effort, mirrors PublicationClient's own fail-soft behaviour).
    @Autowired(required = false)
    private PublicationClient publicationClient;

    @Value("${services.trigger-service.url:http://localhost:8091}")
    private String triggerServiceUrl;

    public AgentService(AgentRepository agentRepository,
                       AgentDefaultsConfig defaults,
                       OrgAccessGuard orgAccessService,
                       AgentMetricsAggregationRepository metricsAggregationRepository,
                       com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard) {
        this.agentRepository = agentRepository;
        this.defaults = defaults;
        this.orgAccessService = orgAccessService;
        this.metricsAggregationRepository = metricsAggregationRepository;
        this.entitlementGuard = entitlementGuard;
    }

    /**
     * The 5 INTERNAL resource list keys that gate per-resource access.
     * Security rule: an absent key MUST NEVER mean "unrestricted". The canonical
     * representation is an explicit list - empty {@code []} for no access, or a
     * non-empty list of IDs for explicit grants.
     *
     * <p>This list does NOT include {@code mode} (governs MCP/catalogue tools - may
     * default to {@code "all"}), {@code tools} (resolved from {@code mode}), or
     * {@code webSearch}/{@code imageGeneration} (booleans, not lists).
     */
    private static final List<String> INTERNAL_RESOURCE_KEYS =
        List.of("workflows", "tables", "interfaces", "agents", "applications");

    /**
     * Backfill any of the 5 internal resource keys missing from {@code toolsConfig}
     * with an empty list, so absent never silently means "all".
     *
     * <p>Single chokepoint for create / update / clone / restore. Returns a NEW
     * {@code LinkedHashMap} (caller-owned, mutation-safe). Pass {@code null} to get
     * a fresh map seeded with the 5 keys at {@code []} - used when an agent has no
     * pre-existing config at all.
     *
     * <p><b>Per-family GRANT sentinel.</b> After backfilling the 5 lists, each
     * family also gets a {@code <family>Grant} ∈ {@code {none|all|custom}} so the
     * row is self-describing and a BUILDER agent can be durably granted "all". The
     * derivation is a behaviour no-op for every existing row: when {@code <family>Grant}
     * is ABSENT it is derived FROM the (already-normalized) list - non-empty ⇒
     * {@code "custom"}, empty ⇒ {@code "none"} - which is exactly what the list rule
     * already resolved to. When {@code <family>Grant} is PRESENT it is preserved
     * verbatim; in particular an explicit {@code "all"} is NOT flattened back to
     * {@code "none"} by the {@code []} list placeholder (the grant drives, the list
     * is just a placeholder payload).
     *
     * <p><b>List reconciliation.</b> The id list is meaningful ONLY for {@code "custom"}.
     * For a resolved {@code "none"} or {@code "all"} grant the list is reset to {@code []}
     * so no stale id list can linger behind a none/all grant - the list-driven credential
     * emitters never surface a stale {@code ['x']} for a non-custom family. A {@code "custom"}
     * list is preserved verbatim. Idempotent.
     */
    public static Map<String, Object> normalizeToolsConfig(Map<String, Object> toolsConfig) {
        Map<String, Object> normalized = toolsConfig != null
            ? new LinkedHashMap<>(toolsConfig)
            : new LinkedHashMap<>();
        // mode is allowed to default to "all" (MCP catalogue product behavior),
        // but persisting it explicitly makes every row self-describing - DB
        // inspection unambiguously shows intent and downstream readers do not
        // have to fall back to runtime defaults.
        if (!normalized.containsKey("mode")) {
            normalized.put("mode", "all");
        }
        for (String key : INTERNAL_RESOURCE_KEYS) {
            if (!normalized.containsKey(key)) {
                normalized.put(key, List.of());
            }
        }
        // Derive the per-family grant sentinel (behaviour no-op for existing rows)
        // and reconcile the family's list with the resolved grant.
        for (String key : INTERNAL_RESOURCE_KEYS) {
            String grantKey = key + "Grant";
            Object presentGrant = normalized.get(grantKey);
            boolean validGrant = "none".equals(presentGrant) || "all".equals(presentGrant)
                || "custom".equals(presentGrant);
            if (!validGrant) {
                // Absent OR unrecognised (e.g. a malformed "bogus") → derive deny-by-default
                // from the list: non-empty ⇒ custom, empty/absent ⇒ none. This is the write
                // chokepoint, so a junk sentinel can never be persisted (it would otherwise
                // fail OPEN - see AgentConfigProvider.isXNone, which is the complement of
                // all/custom for the same reason).
                Object list = normalized.get(key);
                boolean nonEmpty = list instanceof List<?> l && !l.isEmpty();
                normalized.put(grantKey, nonEmpty ? "custom" : "none");
            }
            // A VALID present grant is preserved as-is (including "all"); the list backfill
            // above never overwrites it.
            // The id list is meaningful ONLY for "custom" - it is the "custom" payload.
            // For "none" and "all" the list carries no information (a none family has no
            // access, an all family is unrestricted), so reset it to [] at the source.
            // This guarantees no stale list lingers behind a none/all grant, so the
            // list-driven credential emitters (AgentNode/SubAgentExecutionHandler
            // passAllowedIds, which emit the raw list for any non-'all' grant) can never
            // surface a stale ['x'] for a grant='none' family. Idempotent: a custom list
            // is preserved verbatim; an already-[] none/all list is a no-op.
            Object grant = normalized.get(grantKey);
            if ("none".equals(grant) || "all".equals(grant)) {
                normalized.put(key, List.of());
            }
        }
        return normalized;
    }

    public AgentEntity createAgent(String tenantId,
                                   String name,
                                   String description,
                                   String systemPrompt,
                                   String modelProvider,
                                   String modelName,
                                   BigDecimal temperature,
                                   Integer maxTokens,
                                   Integer maxIterations,
                                   Integer executionTimeout,
                                   Map<String, Object> toolsConfig,
                                   UUID workflowId,
                                   Long dataSourceId,
                                   UUID conversationId,
                                   Map<String, Object> config,
                                   String avatarUrl,
                                   Boolean isPublic,
                                   Boolean isActive,
                                   String organizationId,
                                   BigDecimal creditBudget,
                                   String budgetResetMode) {
        return createAgent(tenantId, name, description, systemPrompt, modelProvider, modelName,
            temperature, maxTokens, maxIterations, executionTimeout, toolsConfig, workflowId,
            dataSourceId, conversationId, config, avatarUrl, isPublic, isActive, organizationId,
            creditBudget, budgetResetMode, null);
    }

    public AgentEntity createAgent(String tenantId,
                                   String name,
                                   String description,
                                   String systemPrompt,
                                   String modelProvider,
                                   String modelName,
                                   BigDecimal temperature,
                                   Integer maxTokens,
                                   Integer maxIterations,
                                   Integer executionTimeout,
                                   Map<String, Object> toolsConfig,
                                   UUID workflowId,
                                   Long dataSourceId,
                                   UUID conversationId,
                                   Map<String, Object> config,
                                   String avatarUrl,
                                   Boolean isPublic,
                                   Boolean isActive,
                                   String organizationId,
                                   BigDecimal creditBudget,
                                   String budgetResetMode,
                                   Map<String, Integer> guardOverrides) {
        return createAgent(tenantId, name, description, systemPrompt, modelProvider, modelName,
            temperature, maxTokens, maxIterations, executionTimeout, toolsConfig, workflowId,
            dataSourceId, conversationId, config, avatarUrl, isPublic, isActive, organizationId,
            creditBudget, budgetResetMode, guardOverrides, null);
    }

    /**
     * Widest create overload - adds the per-agent {@code reasoningEffort}
     * (bridge/CLI providers; {@code minimal|low|medium|high|xhigh}; blank/null =
     * inherit the per-model default then the CLI's own default).
     */
    public AgentEntity createAgent(String tenantId,
                                   String name,
                                   String description,
                                   String systemPrompt,
                                   String modelProvider,
                                   String modelName,
                                   BigDecimal temperature,
                                   Integer maxTokens,
                                   Integer maxIterations,
                                   Integer executionTimeout,
                                   Map<String, Object> toolsConfig,
                                   UUID workflowId,
                                   Long dataSourceId,
                                   UUID conversationId,
                                   Map<String, Object> config,
                                   String avatarUrl,
                                   Boolean isPublic,
                                   Boolean isActive,
                                   String organizationId,
                                   BigDecimal creditBudget,
                                   String budgetResetMode,
                                   Map<String, Integer> guardOverrides,
                                   String reasoningEffort) {
        validateCreateOrUpdate(tenantId, name, temperature, maxTokens, maxIterations, executionTimeout);
        validateGuardOverrides(guardOverrides);
        validateReasoningEffort(reasoningEffort);

        // VIEWER write gate (mirrors the existing-agent mutators). A VIEWER member
        // of an org workspace is read-only, so they cannot create an org-scoped
        // agent. orgRole is the gateway/CE-validated X-Organization-Role read from
        // the request-bound context (REST + MCP both bind it the same way canWrite
        // relies on); null (unbound thread / personal scope) fails open.
        if (organizationId != null && !organizationId.isBlank()
                && isViewerRole(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole())) {
            logger.warn("OrgAccess denied: VIEWER user {} attempted to create an agent in org {}",
                    tenantId, organizationId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", "new");
        }

        // Duplicate name check - backed by V269 partial unique index on
        // (organization_id, name) WHERE is_active = true. Batch A2 (2026-05-20):
        // route through the org-strict variant so two personal workspaces can
        // legitimately host an agent of the same name without colliding.
        Optional<AgentEntity> dupName = (organizationId != null && !organizationId.isBlank())
                ? agentRepository.findByOrganizationIdStrictAndNameAndIsActiveTrue(organizationId, name)
                : agentRepository.findByTenantIdAndNameAndIsActiveTrue(tenantId, name);
        dupName.ifPresent(existing -> {
            throw new IllegalArgumentException("An active agent with name '" + name + "' already exists (ID: " + existing.getId() + "). Use agent(action='update', ...) to modify it, or choose a different name.");
        });

        // Plan resource limit check (mapped to HTTP 409 by global handler in auth-client,
        // and to a "DO NOT RETRY" tool result by AgentCrudModule in shared-agent-lib).
        // Batch A2 - org-aware count so org workspaces see the org's quota, not
        // the personal owner's count.
        if (entitlementGuard != null) {
            entitlementGuard.check(tenantId,
                    com.apimarketplace.auth.client.entitlement.ResourceType.AGENT,
                    () -> (organizationId != null && !organizationId.isBlank())
                            ? agentRepository.countByOrganizationIdStrict(organizationId)
                            : agentRepository.countByTenantId(tenantId));
        }

        // Apply defaults from yml config when values are null
        BigDecimal effectiveTemperature = temperature != null ? temperature : BigDecimal.valueOf(defaults.getTemperature());
        Integer effectiveMaxTokens = maxTokens != null ? maxTokens : defaults.getMaxTokens();
        Integer effectiveMaxIterations = maxIterations != null ? maxIterations : defaults.getMaxIterations();
        Integer effectiveTimeout = executionTimeout != null ? executionTimeout : defaults.getExecutionTimeout();

        AgentEntity entity = new AgentEntity(
            tenantId,
            name,
            description,
            systemPrompt,
            modelProvider,
            modelName,
            effectiveTemperature,
            effectiveMaxTokens,
            effectiveMaxIterations,
            normalizeToolsConfig(toolsConfig),
            workflowId,
            dataSourceId,
            conversationId,
            config,
            isPublic,
            isActive
        );

        // Set avatar URL -- use provided value or assign a random preset
        entity.setAvatarUrl(avatarUrl != null ? avatarUrl : randomAvatarPreset());
        entity.setExecutionTimeout(effectiveTimeout);

        // Credit budget
        entity.setCreditBudget(creditBudget);
        if (budgetResetMode != null) {
            entity.setBudgetResetMode(budgetResetMode);
        }

        if (organizationId != null) {
            entity.setOrganizationId(organizationId);
        }

        // Per-agent reasoning effort (bridge/CLI). Normalize to the canonical wire
        // form; blank/unknown → null (inherit model default then CLI default).
        ReasoningEffort effort = ReasoningEffort.fromString(reasoningEffort);
        entity.setReasoningEffort(effort != null ? effort.wire() : null);

        // Guard overrides (V100). On create, a null map leaves all columns NULL (→ YAML defaults).
        applyGuardOverrides(entity, guardOverrides);

        AgentEntity saved = agentRepository.save(entity);

        // Create a dedicated conversation for this agent
        if (conversationServiceClient != null) {
            try {
                String convId = conversationServiceClient.findOrCreateAgentConversation(
                    saved.getId().toString(), tenantId, name, organizationId);
                if (convId != null) {
                    saved.setConversationId(UUID.fromString(convId));
                    saved = agentRepository.save(saved);
                    logger.info("Created conversation {} for new agent {}", convId, saved.getId());
                }
            } catch (Exception e) {
                logger.warn("Failed to create conversation for agent {}: {}", saved.getId(), e.getMessage());
            }
        }

        return saved;
    }

    /**
     * Back-compat 2-arg overload. Pre-V261 this delegated to
     * {@link #getAgent(UUID, String, String, String) getAgent(id, tenantId, null, null)},
     * which the strict-isolation predicate {@link ScopeGuard#isInStrictScope} treats
     * as "personal workspace" - rejecting any row with a non-null {@code organization_id}.
     *
     * <p>Prod fire 2026-05-20 ~21:21 UTC on conv {@code c67d368a}: {@code agent.list}
     * returned an org-scoped agent but {@code agent.get}/{@code agent.update} on the
     * same id said "Agent not found" because ~17 callsites in agent-service still
     * used this 2-arg overload, dropping the org context. Rather than touch all 17
     * (high churn + ongoing risk of new callsites being added), this overload is
     * now <b>self-healing</b>: it reads
     * {@link com.apimarketplace.common.web.TenantResolver#currentRequestOrganizationId}
     * from the inbound HTTP request context (gateway injects {@code X-Organization-ID}
     * via {@code AuthenticationFilter}) and, when present, routes to the 3-arg overload
     * so the strict-isolation predicate matches org-scoped rows correctly.
     *
     * <p>Threads that are not request-bound (daemon scans, agent recovery sweeps,
     * Redis listeners) MUST still wrap in {@code TenantResolver.runWithOrgScope}
     * before calling - those wraps were added by the 2026-05-20 Redis-listener
     * hotfix (commit {@code ea5821dc4}). On unwrapped threads {@code currentRequestOrganizationId}
     * returns null and the 2-arg overload behaves identically to the legacy
     * 4-arg(null,null) path - back-compat for callers that genuinely need
     * personal-scope semantics (e.g. early-onboarding pre-V261 agents).
     */
    @Transactional(readOnly = true)
    public Optional<AgentEntity> getAgent(UUID id, String tenantId) {
        // 2026-05-21 follow-up to commits f3b4522a0 + d4ad7ad27:
        // self-heal now also reads orgRole so non-OWNER org members hit the
        // owner-or-org-scope predicate (canReadAgent needs role for VIEWER
        // and other non-OWNER deny-list resolution). Covers ~15 legacy
        // 2-arg callsites (AgentController, InternalAgentController,
        // SubAgentExecutionHandler, AgentConversationModule) without
        // per-site edits.
        String orgId = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        String orgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        if (orgId != null && !orgId.isBlank()) {
            return getAgent(id, tenantId, orgId, orgRole);
        }
        return getAgent(id, tenantId, null, null);
    }

    /**
     * Owner-or-org-scope variant of {@link #getAgent(UUID, String)}.
     *
     * <p>The agent is visible if (a) the caller owns it directly, OR (b) the
     * agent carries a non-null {@code organization_id} matching the caller's
     * active workspace. Mirrors the read predicate used by
     * {@code listAgents} (which routes via {@code findByOrganizationOrOwner}
     * + {@code filterAccessible}) so detail-page visibility stays consistent
     * with list visibility.
     *
     * <p>Audit 2026-05-16 MF (prod incident): org-teammate hit 404 on
     * {@code GET /api/agents/{id}} because the read path was strict-tenant.
     */
    @Transactional(readOnly = true)
    public Optional<AgentEntity> getAgent(UUID id, String tenantId, String orgId) {
        // 2026-05-21: also self-heal orgRole at the 3-arg overload - same
        // reason as the 2-arg overload above.
        String orgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        return getAgent(id, tenantId, orgId, orgRole);
    }

    @Transactional(readOnly = true)
    public Optional<AgentEntity> getAgent(UUID id, String tenantId, String orgId, String orgRole) {
        return agentRepository.findById(id)
            .filter(a -> isInScope(a, tenantId, orgId))
            .filter(a -> canReadAgent(a, tenantId, orgRole));
    }

    /**
     * Strict-isolation scope predicate for {@link AgentEntity}, aligned with
     * {@link ScopeGuard#isInStrictScope} (2026-05-18). Active org workspace
     * matches only rows tagged with that org; personal workspace matches
     * rows owned by the caller AND not tagged with any org. Used by
     * {@link #getAgent} for read access, and by every mutation entrypoint
     * ({@link #updateAgent}, {@link #cloneAgent}, {@link #deleteAgent},
     * {@link #resetCredits}) for the positive scope check before delegating
     * to the {@code OrgAccessGuard} deny-list.
     */
    private static boolean isInScope(AgentEntity a, String tenantId, String orgId) {
        if (a == null) return false;
        return ScopeGuard.isInStrictScope(
                tenantId, orgId, a.getTenantId(), a.getOrganizationId());
    }

    private boolean canReadAgent(AgentEntity agent, String tenantId, String orgRole) {
        String agentOrgId = agent.getOrganizationId();
        if (agentOrgId == null || agentOrgId.isBlank()) {
            return true;
        }
        boolean allowed = orgAccessService.canAccess(agentOrgId, tenantId, "agent", agent.getId().toString(), orgRole);
        if (!allowed) {
            logger.warn("OrgAccess deny-list: user {} restricted from reading agent {} in org {}",
                    tenantId, agent.getId(), agentOrgId);
        }
        return allowed;
    }

    /**
     * True iff {@code orgRole} is the read-only VIEWER org role. Mirrors
     * {@code WorkflowCrudController.isViewerRole} so agent-service applies the
     * same documented role boundary (OrganizationRole VIEWER = "Read-only access
     * to organization resources") as workflow-service and interface-service.
     */
    private static boolean isViewerRole(String orgRole) {
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }

    /**
     * VIEWER write gate for an org-scoped agent. A VIEWER member has read-only
     * access to org resources, so they must not create/update/delete/clone/reset
     * or otherwise mutate an org-scoped agent. {@link OrgAccessGuard#canWrite}
     * now enforces this role boundary centrally ({@code isRoleWriteBlocked});
     * this local gate is kept as an earlier, clearer 403 and for the create path
     * (no resource id yet, so {@code canWrite} is never consulted there).
     * Mirrors {@code WorkflowCrudController}'s "VIEWER role cannot modify"
     * gate. Personal-scope agents ({@code agentOrgId == null}) carry no org role
     * and are unaffected. A null {@code orgRole} (unbound thread / personal scope)
     * fails open - the same fail-open {@code canWrite}'s non-admin default uses -
     * so legacy personal/system writes are never broken.
     *
     * @throws com.apimarketplace.auth.client.access.OrgAccessDeniedException (HTTP 403) when a VIEWER targets an org-scoped agent
     */
    private void assertNotViewerWrite(String agentOrgId, String tenantId, String orgRole, UUID agentId, String op) {
        if (agentOrgId != null && isViewerRole(orgRole)) {
            logger.warn("OrgAccess denied: VIEWER user {} attempted to {} agent {} in org {}",
                    tenantId, op, agentId, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", agentId.toString());
        }
    }

    /**
     * Tenant-agnostic lookup by primary key. Used by tool modules that need to read
     * the <em>calling</em> agent's own entity (resolved from {@code __agentId__} in
     * the execution context credentials) to fetch per-agent guard overrides - in that
     * call path the tenant match is already guaranteed by how credentials were built,
     * so re-validating tenantId here would just force every caller to pass it through.
     *
     * <p>Prefer {@link #getAgent(UUID, String)} for any code path that accepts a
     * user-supplied agentId - this variant bypasses the tenant check and is not safe
     * for that use case.</p>
     */
    @Transactional(readOnly = true)
    public Optional<AgentEntity> findById(UUID id) {
        return agentRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<AgentEntity> getAgentByConversationId(UUID conversationId, String tenantId) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        return agentRepository.findByConversationIdOrderByCreatedAtDesc(conversationId)
            .stream()
            .filter(a -> isInScope(a, tenantId, orgId))
            .findFirst();
    }

    public List<AgentEntity> listAgents(String tenantId, String orgId, String orgRole) {
        // Decode tenantId if it's URL encoded (e.g., %7C -> |)
        String decodedTenantId = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;

        if (orgId != null && !orgId.isBlank()) {
            List<AgentEntity> agents = agentRepository.findByOrganizationOrOwner(orgId, decodedTenantId);
            return orgAccessService.filterAccessible(agents, orgId, decodedTenantId, "agent", orgRole,
                    a -> a.getId().toString());
        }
        return agentRepository.findByTenantIdOrderByCreatedAtDesc(decodedTenantId);
    }

    /**
     * List agent (id, avatarUrl) pairs visible to the caller. Mirrors
     * {@link #listAgents} but materializes only the two columns needed by
     * the conversation sidebar - avoids the ~MB-per-tenant payload that
     * full-entity serialization carries because of the system_prompt LOB.
     *
     * <p>Org-aware: when {@code orgId} is set, the org-share + restriction
     * filter is applied identically to {@code listAgents}.
     */
    public List<AgentAvatarResponse> listAgentAvatars(String tenantId, String orgId, String orgRole) {
        String decodedTenantId = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;

        if (orgId != null && !orgId.isBlank()) {
            List<AgentAvatarResponse> avatars =
                    agentRepository.findAvatarsByOrganizationOrOwner(orgId, decodedTenantId);
            return orgAccessService.filterAccessible(avatars, orgId, decodedTenantId, "agent", orgRole,
                    a -> a.id().toString());
        }
        return agentRepository.findAvatarsByTenantId(decodedTenantId);
    }

    /**
     * List ALL agents in the given tenant.
     *
     * <p>Used by callers that have unrestricted agent visibility (the
     * "god agent" pattern: {@code toolsConfig.agents == null}, no allowlist).
     * Returns active and inactive agents alike - callers filter as needed.
     */
    public List<AgentEntity> listAllByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return List.of();
        return agentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * Page envelope for the paged list endpoint, with the per-row publication badge for the page
     * ({@code publicationStatuses}: agent id -> {@code {status, rejectionReason?}}, absent = not
     * shared), batched server-side so the card needs no per-row publication call.
     */
    public record AgentPage(List<AgentEntity> items, int totalCount, int page, int size,
                            Map<String, Map<String, String>> publicationStatuses) {}

    private static final String AGENT_PUBLICATION_TYPE = "AGENT";

    /**
     * Server-paged, DB-searchable, server-sorted + server-visibility-filtered list - the agent sibling
     * of {@code DataSourceService.getDataSourcesPaged} / {@code InterfaceService.listInterfacesPaged}.
     * The visibility filter derives from publication status (owned by publication-service, a different
     * schema - no SQL join), so when active the status batch is resolved over the whole searched set
     * BEFORE paginating; when "all" the status is only needed for the page's badges and is fetched
     * after slicing. Either way it is ONE HTTP call. {@code sort} = name | lastModified (default
     * lastModified); {@code visibility} = all | public | private (default all). Agent publications are
     * keyed by agentConfigId (= the agent id), so the batch uses the AGENT type.
     */
    public AgentPage listAgentsPaged(String tenantId, String orgId, String orgRole,
                                       String q, int page, int size, String sort, String visibility) {
        String decodedTenantId = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;
        boolean hasSearch = q != null && !q.isBlank();

        List<AgentEntity> all;
        if (orgId != null && !orgId.isBlank()) {
            all = agentRepository.findByOrganizationOrOwner(orgId, decodedTenantId);
            all = orgAccessService.filterAccessible(all, orgId, decodedTenantId, "agent", orgRole,
                    a -> a.getId().toString());
            if (hasSearch) {
                String needle = q.trim().toLowerCase();
                all = all.stream()
                        .filter(a -> matchesNameOrDescription(a.getName(), a.getDescription(), needle))
                        .toList();
            }
        } else if (hasSearch) {
            all = agentRepository.searchByTenant(decodedTenantId, q.trim());
        } else {
            all = agentRepository.findByTenantIdOrderByCreatedAtDesc(decodedTenantId);
        }

        // Visibility filter (derives from publication status). When active, resolve the whole-set
        // status ONCE before paginating and reuse it for the page badges below.
        String visFilter = visibility == null ? "all" : visibility.trim().toLowerCase();
        boolean filterByVisibility = publicationClient != null
                && (visFilter.equals("public") || visFilter.equals("private"));
        Map<String, PublicationClient.ResourcePublicationStatusRef> fullSetStatuses = filterByVisibility
                ? publicationClient.findResourcePublicationStatuses(
                        AGENT_PUBLICATION_TYPE, resourceIdsOf(all), decodedTenantId)
                : Map.of();
        if (filterByVisibility) {
            boolean wantPublic = visFilter.equals("public");
            final Map<String, PublicationClient.ResourcePublicationStatusRef> refs = fullSetStatuses;
            all = all.stream()
                    .filter(a -> isShared(refs, a) == wantPublic)
                    .toList();
        }

        // Order, then slice. Stable sort keeps the created_at-DESC base order as the tie-breaker.
        all = sortAgents(all, sort);

        int totalCount = all.size();
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, totalCount);
        int to = Math.min(from + safeSize, totalCount);
        List<AgentEntity> pageItems = all.subList(from, to);

        // Publication badge for the page, batched server-side (no per-row fan-out). Reuse the full-set
        // statuses already fetched for the visibility filter; otherwise fetch just the page's ids.
        Map<String, PublicationClient.ResourcePublicationStatusRef> pageRefs = filterByVisibility
                ? fullSetStatuses
                : (publicationClient != null
                        ? publicationClient.findResourcePublicationStatuses(
                                AGENT_PUBLICATION_TYPE, resourceIdsOf(pageItems), decodedTenantId)
                        : Map.of());
        Map<String, Map<String, String>> publicationStatuses = toPublicationStatusMap(pageItems, pageRefs);

        return new AgentPage(pageItems, totalCount, safePage, safeSize, publicationStatuses);
    }

    private static List<String> resourceIdsOf(List<AgentEntity> list) {
        return list.stream().map(a -> a.getId().toString()).toList();
    }

    private static boolean isShared(Map<String, PublicationClient.ResourcePublicationStatusRef> statuses,
                                    AgentEntity a) {
        PublicationClient.ResourcePublicationStatusRef ref = statuses.get(a.getId().toString());
        return ref != null && ref.published();
    }

    /**
     * Server-side equivalent of the frontend {@code listSort.processList} order: {@code name}
     * (case-insensitive A->Z) or, by default, {@code lastModified} (updatedAt, falling back to
     * createdAt, most-recent first; missing dates last). Stable, so equal keys keep the upstream
     * created_at-DESC order.
     */
    private static List<AgentEntity> sortAgents(List<AgentEntity> list, String sort) {
        String key = sort == null ? "lastmodified" : sort.trim().toLowerCase();
        List<AgentEntity> sorted = new java.util.ArrayList<>(list);
        if (key.equals("name")) {
            sorted.sort(java.util.Comparator.comparing(
                    a -> a.getName() == null ? "" : a.getName(), String.CASE_INSENSITIVE_ORDER));
        } else {
            sorted.sort(AgentService::compareByModifiedDesc);
        }
        return sorted;
    }

    private static int compareByModifiedDesc(AgentEntity a, AgentEntity b) {
        Instant ta = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
        Instant tb = b.getUpdatedAt() != null ? b.getUpdatedAt() : b.getCreatedAt();
        if (ta == null && tb == null) return 0;
        if (ta == null) return 1;   // missing date sorts last
        if (tb == null) return -1;
        return tb.compareTo(ta);    // most-recent first
    }

    private static Map<String, Map<String, String>> toPublicationStatusMap(
            List<AgentEntity> pageItems,
            Map<String, PublicationClient.ResourcePublicationStatusRef> refs) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (AgentEntity a : pageItems) {
            String id = a.getId().toString();
            PublicationClient.ResourcePublicationStatusRef ref = refs.get(id);
            if (ref == null) continue;
            Map<String, String> info = new LinkedHashMap<>();
            info.put("status", ref.status());
            if (ref.rejectionReason() != null) info.put("rejectionReason", ref.rejectionReason());
            out.put(id, info);
        }
        return out;
    }

    private static boolean matchesNameOrDescription(String name, String desc, String needle) {
        return (name != null && name.toLowerCase().contains(needle))
            || (desc != null && desc.toLowerCase().contains(needle));
    }

    public AgentEntity updateAgent(UUID id,
                                   String tenantId,
                                   String name,
                                   String description,
                                   String systemPrompt,
                                   String modelProvider,
                                   String modelName,
                                   BigDecimal temperature,
                                   Integer maxTokens,
                                   Integer maxIterations,
                                   Integer executionTimeout,
                                   Map<String, Object> toolsConfig,
                                   UUID workflowId,
                                   Long dataSourceId,
                                   UUID conversationId,
                                   Map<String, Object> config,
                                   String avatarUrl,
                                   Boolean isPublic,
                                   Boolean isActive,
                                   BigDecimal creditBudget,
                                   String budgetResetMode) {
        return updateAgent(id, tenantId, name, description, systemPrompt, modelProvider, modelName,
            temperature, maxTokens, maxIterations, executionTimeout, toolsConfig, workflowId,
            dataSourceId, conversationId, config, avatarUrl, isPublic, isActive, creditBudget,
            budgetResetMode, null);
    }

    public AgentEntity updateAgent(UUID id,
                                   String tenantId,
                                   String name,
                                   String description,
                                   String systemPrompt,
                                   String modelProvider,
                                   String modelName,
                                   BigDecimal temperature,
                                   Integer maxTokens,
                                   Integer maxIterations,
                                   Integer executionTimeout,
                                   Map<String, Object> toolsConfig,
                                   UUID workflowId,
                                   Long dataSourceId,
                                   UUID conversationId,
                                   Map<String, Object> config,
                                   String avatarUrl,
                                   Boolean isPublic,
                                   Boolean isActive,
                                   BigDecimal creditBudget,
                                   String budgetResetMode,
                                   Map<String, Integer> guardOverrides) {
        return updateAgent(id, tenantId, name, description, systemPrompt, modelProvider, modelName,
            temperature, maxTokens, maxIterations, executionTimeout, toolsConfig, workflowId,
            dataSourceId, conversationId, config, avatarUrl, isPublic, isActive, creditBudget,
            budgetResetMode, guardOverrides, null);
    }

    /**
     * Org-aware overload. {@code callerOrgId} is the gateway-validated
     * {@code X-Organization-ID} header - the gateway forwards it ONLY when the
     * caller is a real member of that org (PR0.5b contract), so
     * {@code callerOrgId.equals(agent.organization_id)} is a positive scope
     * check (not a deny-list lookup). canAccess (deny-list) layers on top
     * for restrictions within the membership (PR-2.f).
     *
     * <p>Audit 2026-05-16 round-2: the prior pattern used canAccess as the
     * scope predicate, which is wrong because canAccess returns true for
     * non-members (their restriction set is trivially empty → not-contains
     * returns true). Result was a CROSS-ORG WRITE leak.
     */
    public AgentEntity updateAgent(UUID id,
                                   String tenantId,
                                   String name,
                                   String description,
                                   String systemPrompt,
                                   String modelProvider,
                                   String modelName,
                                   BigDecimal temperature,
                                   Integer maxTokens,
                                   Integer maxIterations,
                                   Integer executionTimeout,
                                   Map<String, Object> toolsConfig,
                                   UUID workflowId,
                                   Long dataSourceId,
                                   UUID conversationId,
                                   Map<String, Object> config,
                                   String avatarUrl,
                                   Boolean isPublic,
                                   Boolean isActive,
                                   BigDecimal creditBudget,
                                   String budgetResetMode,
                                   Map<String, Integer> guardOverrides,
                                   String callerOrgId) {
        return updateAgent(id, tenantId, name, description, systemPrompt, modelProvider, modelName,
            temperature, maxTokens, maxIterations, executionTimeout, toolsConfig, workflowId,
            dataSourceId, conversationId, config, avatarUrl, isPublic, isActive, creditBudget,
            budgetResetMode, guardOverrides, callerOrgId, null);
    }

    /**
     * Widest update overload - adds the per-agent {@code reasoningEffort}
     * (bridge/CLI providers; {@code minimal|low|medium|high|xhigh}). A blank
     * string clears the stored value; {@code null} leaves it unchanged.
     */
    public AgentEntity updateAgent(UUID id,
                                   String tenantId,
                                   String name,
                                   String description,
                                   String systemPrompt,
                                   String modelProvider,
                                   String modelName,
                                   BigDecimal temperature,
                                   Integer maxTokens,
                                   Integer maxIterations,
                                   Integer executionTimeout,
                                   Map<String, Object> toolsConfig,
                                   UUID workflowId,
                                   Long dataSourceId,
                                   UUID conversationId,
                                   Map<String, Object> config,
                                   String avatarUrl,
                                   Boolean isPublic,
                                   Boolean isActive,
                                   BigDecimal creditBudget,
                                   String budgetResetMode,
                                   Map<String, Integer> guardOverrides,
                                   String callerOrgId,
                                   String reasoningEffort) {
        return updateAgent(id, tenantId, name, description, systemPrompt, modelProvider, modelName,
            temperature, maxTokens, maxIterations, executionTimeout, toolsConfig, workflowId,
            dataSourceId, conversationId, config, avatarUrl, isPublic, isActive, creditBudget,
            budgetResetMode, guardOverrides, callerOrgId, reasoningEffort, true);
    }

    /**
     * REST-aware update overload. {@code creditBudgetProvided} preserves patch
     * semantics for the public budget cap: absent means unchanged, explicit null
     * means clear to unlimited.
     */
    public AgentEntity updateAgent(UUID id,
                                   String tenantId,
                                   String name,
                                   String description,
                                   String systemPrompt,
                                   String modelProvider,
                                   String modelName,
                                   BigDecimal temperature,
                                   Integer maxTokens,
                                   Integer maxIterations,
                                   Integer executionTimeout,
                                   Map<String, Object> toolsConfig,
                                   UUID workflowId,
                                   Long dataSourceId,
                                   UUID conversationId,
                                   Map<String, Object> config,
                                   String avatarUrl,
                                   Boolean isPublic,
                                   Boolean isActive,
                                   BigDecimal creditBudget,
                                   String budgetResetMode,
                                   Map<String, Integer> guardOverrides,
                                   String callerOrgId,
                                   String reasoningEffort,
                                   boolean creditBudgetProvided) {
        AgentEntity existing = agentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));

        // Strict-isolation positive scope check (2026-05-18, see isInScope).
        String agentOrgId = existing.getOrganizationId();
        if (!isInScope(existing, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                logger.warn("OrgAccess denied: user {} (active org {}) not in scope to update agent {} (org {})",
                        tenantId, callerOrgId, id, agentOrgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                        "agent", id.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        // PR-2.f canWrite visibility gate - fires even for owner.
        // 2026-05-21 prod fix: previously passed `null` for orgRole → OrgAccessGuard
        // role-based deny-list defaulted to deny → workspace OWNERS got "Access to
        // this agent is restricted" on update. Read role from TenantResolver
        // (gateway-injected X-Organization-Role, request-bound, falls back to
        // async ThreadLocal). Mirrors what clone(L688), delete(L795), getAgent(L340)
        // already do via their orgRole parameter.
        String callerOrgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        assertNotViewerWrite(agentOrgId, tenantId, callerOrgRole, id, "update");
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", id.toString(), callerOrgRole)) {
            logger.warn("OrgAccess deny-list: user {} (role {}) restricted from updating agent {} in org {}",
                    tenantId, callerOrgRole, id, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "agent", id.toString());
        }

        validateCreateOrUpdate(tenantId, name, temperature, maxTokens, maxIterations, executionTimeout);
        validateGuardOverrides(guardOverrides);
        validateReasoningEffort(reasoningEffort);
        validateNoCircularAgentReferences(id, toolsConfig, tenantId);

        // Update fields
        existing.setName(name);
        if (description != null) {
            existing.setDescription(description);
        }
        if (systemPrompt != null) {
            existing.setSystemPrompt(systemPrompt);
        }
        if (modelProvider != null) {
            existing.setModelProvider(modelProvider);
        }
        if (modelName != null) {
            existing.setModelName(modelName);
        }
        if (temperature != null) {
            existing.setTemperature(temperature);
        }
        if (maxTokens != null) {
            existing.setMaxTokens(maxTokens);
        }
        if (maxIterations != null) {
            existing.setMaxIterations(maxIterations);
        }
        if (executionTimeout != null) {
            existing.setExecutionTimeout(executionTimeout);
        }
        // Per-agent reasoning effort (bridge/CLI). null ⇒ leave unchanged; blank ⇒
        // clear (inherit model default); a known level ⇒ store canonical wire form.
        if (reasoningEffort != null) {
            ReasoningEffort effort = ReasoningEffort.fromString(reasoningEffort);
            existing.setReasoningEffort(effort != null ? effort.wire() : null);
        }
        if (toolsConfig != null) {
            // MERGE patch into existing instead of REPLACE - keeps REST PUT and the
            // LLM `agent(action='update')` tool path on identical semantics, and
            // prevents the modal from accidentally wiping resource lists when it
            // submits a partial config (e.g. only `mode` changed).
            //
            // normalizeToolsConfig then guarantees the 5 internal keys exist (absent
            // → []), so the merged blob is always self-describing - no downstream
            // reader can fall back to "absent means all".
            Map<String, Object> merged = new LinkedHashMap<>();
            if (existing.getToolsConfig() != null) {
                merged.putAll(existing.getToolsConfig());
            }
            merged.putAll(toolsConfig);
            existing.setToolsConfig(normalizeToolsConfig(merged));
        }
        existing.setWorkflowId(workflowId);
        if (dataSourceId != null) {
            existing.setDataSourceId(dataSourceId);
        }
        // Update conversationId if provided (can be null to clear it)
        existing.setConversationId(conversationId);
        if (config != null) {
            existing.setConfig(config);
        }
        if (avatarUrl != null) {
            existing.setAvatarUrl(avatarUrl);
        }
        if (isPublic != null) {
            existing.setIsPublic(isPublic);
        }
        if (isActive != null) {
            existing.setIsActive(isActive);
        }

        // Credit budget: absent patch leaves the cap unchanged; explicit null clears to unlimited.
        if (creditBudgetProvided) {
            existing.setCreditBudget(creditBudget);
        }
        if (budgetResetMode != null) {
            existing.setBudgetResetMode(budgetResetMode);
        }

        // Guard overrides (V100). Map key presence = intent signal:
        //   - key absent → column unchanged
        //   - key present with value → set column to value
        //   - key present with null → clear column to NULL (resolver falls back to YAML default)
        applyGuardOverrides(existing, guardOverrides);

        AgentEntity saved = agentRepository.save(existing);

        return saved;
    }

    /**
     * V340 - set the per-agent backlog opt-in flag in isolation.
     * <p>
     * Backlog participation is a single boolean; threading it through every
     * {@code createAgent}/{@code updateAgent} overload (and the clone path) would
     * be pure churn for one field. This focused mutator runs the SAME strict
     * positive-scope check + {@code canWrite} deny-list gate as
     * {@link #updateAgent}, so it cannot be used to flip the flag on a
     * cross-workspace agent.
     *
     * @param callerOrgId gateway-validated {@code X-Organization-ID} (positive scope), may be null for personal scope
     */
    @Transactional
    public AgentEntity setBacklogEnabled(UUID id, String tenantId, String callerOrgId, boolean enabled) {
        AgentEntity existing = agentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));

        String agentOrgId = existing.getOrganizationId();
        if (!isInScope(existing, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                logger.warn("OrgAccess denied: user {} (active org {}) not in scope to set backlogEnabled on agent {} (org {})",
                        tenantId, callerOrgId, id, agentOrgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", id.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        String callerOrgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        assertNotViewerWrite(agentOrgId, tenantId, callerOrgRole, id, "set backlog on");
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", id.toString(), callerOrgRole)) {
            logger.warn("OrgAccess deny-list: user {} (role {}) restricted from setting backlogEnabled on agent {} in org {}",
                    tenantId, callerOrgRole, id, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", id.toString());
        }

        existing.setBacklogEnabled(enabled);
        return agentRepository.save(existing);
    }

    /**
     * Patch the per-agent inactivity watchdog window (seconds), running the EXACT same org-scope +
     * write-access gate as {@link #setBacklogEnabled}. A dedicated setter (like backlog/compaction)
     * so {@code createAgent}'s positional signature stays untouched. {@code null} clears back to the
     * platform default (5 min); {@code 0} disables the watchdog; {@code [10, 7200]} sets a custom window.
     */
    public AgentEntity setInactivityTimeout(UUID id, String tenantId, String callerOrgId, Integer inactivityTimeout) {
        if (inactivityTimeout != null && inactivityTimeout != 0
                && (inactivityTimeout < 10 || inactivityTimeout > 7200)) {
            throw new IllegalArgumentException("inactivity_timeout must be 0 (disabled) or between 10 and 7200 (seconds)");
        }
        AgentEntity existing = agentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));

        String agentOrgId = existing.getOrganizationId();
        if (!isInScope(existing, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                logger.warn("OrgAccess denied: user {} (active org {}) not in scope to set inactivityTimeout on agent {} (org {})",
                        tenantId, callerOrgId, id, agentOrgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", id.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        String callerOrgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        assertNotViewerWrite(agentOrgId, tenantId, callerOrgRole, id, "set inactivity timeout on");
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", id.toString(), callerOrgRole)) {
            logger.warn("OrgAccess deny-list: user {} (role {}) restricted from setting inactivityTimeout on agent {} in org {}",
                    tenantId, callerOrgRole, id, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", id.toString());
        }

        existing.setInactivityTimeout(inactivityTimeout);
        return agentRepository.save(existing);
    }

    /**
     * Patch the per-agent compaction overrides (enable + cadence), V350. Runs the
     * EXACT same org-scope + write-access gate as {@link #setBacklogEnabled} /
     * {@link #updateAgent}. Per-field patch semantics: a {@code *Present=false} flag
     * leaves that column untouched; {@code present=true} with a {@code null} value
     * CLEARS the override (back to inherit). {@code compactionAfterTurns}, when set,
     * must be {@code >= 1} (mirrors the DB CHECK / resolver contract).
     */
    public AgentEntity setCompactionOverrides(UUID id, String tenantId, String callerOrgId,
                                              boolean enabledPresent, Boolean compactionEnabled,
                                              boolean afterTurnsPresent, Integer compactionAfterTurns) {
        if (afterTurnsPresent && compactionAfterTurns != null && compactionAfterTurns < 1) {
            throw new IllegalArgumentException("compactionAfterTurns must be >= 1");
        }
        AgentEntity existing = agentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));

        String agentOrgId = existing.getOrganizationId();
        if (!isInScope(existing, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                logger.warn("OrgAccess denied: user {} (active org {}) not in scope to set compaction on agent {} (org {})",
                        tenantId, callerOrgId, id, agentOrgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", id.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        String callerOrgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        assertNotViewerWrite(agentOrgId, tenantId, callerOrgRole, id, "set compaction on");
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", id.toString(), callerOrgRole)) {
            logger.warn("OrgAccess deny-list: user {} (role {}) restricted from setting compaction on agent {} in org {}",
                    tenantId, callerOrgRole, id, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", id.toString());
        }

        if (enabledPresent) {
            existing.setCompactionEnabled(compactionEnabled);
        }
        if (afterTurnsPresent) {
            existing.setCompactionAfterTurns(compactionAfterTurns);
        }
        return agentRepository.save(existing);
    }

    /**
     * Patch the per-agent compaction SUMMARISER model override (V106 columns,
     * user-facing write path). Runs the EXACT same org-scope + write-access gate as
     * {@link #setCompactionOverrides}. The pair is all-or-nothing: both non-blank
     * sets the override, both {@code null}/blank clears it (back to inherit), a
     * partial pair throws - {@code AgentCompactionModelResolver} treats partial
     * pairs as unset, so persisting one would silently do nothing.
     */
    public AgentEntity setCompactionModel(UUID id, String tenantId, String callerOrgId,
                                          String compactionModelProvider, String compactionModelName) {
        String provider = compactionModelProvider == null || compactionModelProvider.isBlank()
            ? null : compactionModelProvider.trim();
        String name = compactionModelName == null || compactionModelName.isBlank()
            ? null : compactionModelName.trim();
        if ((provider == null) != (name == null)) {
            throw new IllegalArgumentException(
                "compactionModelProvider and compactionModelName must be set together (or both cleared)");
        }
        AgentEntity existing = agentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));

        String agentOrgId = existing.getOrganizationId();
        if (!isInScope(existing, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                logger.warn("OrgAccess denied: user {} (active org {}) not in scope to set compaction model on agent {} (org {})",
                        tenantId, callerOrgId, id, agentOrgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", id.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        String callerOrgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        assertNotViewerWrite(agentOrgId, tenantId, callerOrgRole, id, "set compaction model on");
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", id.toString(), callerOrgRole)) {
            logger.warn("OrgAccess deny-list: user {} (role {}) restricted from setting compaction model on agent {} in org {}",
                    tenantId, callerOrgRole, id, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", id.toString());
        }

        existing.setCompactionModelProvider(provider);
        existing.setCompactionModelName(name);
        return agentRepository.save(existing);
    }

    /**
     * Per-resource write-access gate for an agent's <em>satellite</em> config
     * (webhook / schedule / widget) that lives in side tables or in an external
     * service, so it is mutated without going through {@link #updateAgent}.
     *
     * <p>Runs the EXACT same two-stage check as {@link #updateAgent}: (1) the
     * strict positive-scope check ({@link #isInScope}) - a cross-workspace caller
     * is rejected before any deny-list lookup; (2) the {@link OrgAccessGuard#canWrite}
     * deny-list gate - a MEMBER with a per-resource {@code READ}/{@code DENY} row on
     * this agent is blocked even though they can READ it (so the controller's
     * {@code getAgent} 404-or-read pass alone is NOT sufficient authorization to
     * MUTATE). OWNER/ADMIN bypass and the {@code orgRole=null} non-admin default are
     * inherited from {@code canWrite}; {@code orgRole} is read from the request-bound
     * {@link TenantResolver}, identical to {@code updateAgent}.
     *
     * <p>A personal-scope agent ({@code organizationId == null}) that passes the
     * scope check has no org deny-list and is always writable - same as every other
     * mutator here.
     *
     * @param callerOrgId gateway-validated {@code X-Organization-ID} (positive scope), may be null for personal scope
     * @throws com.apimarketplace.auth.client.access.OrgAccessDeniedException if the caller is out of scope or write-restricted on an org-scoped agent
     * @throws IllegalArgumentException if the agent does not exist, or on a personal-scope tenant mismatch
     */
    @Transactional(readOnly = true)
    public void assertCanWriteAgent(UUID agentId, String tenantId, String callerOrgId) {
        AgentEntity existing = agentRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        String agentOrgId = existing.getOrganizationId();
        if (!isInScope(existing, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                logger.warn("OrgAccess denied: user {} (active org {}) not in scope to mutate config of agent {} (org {})",
                        tenantId, callerOrgId, agentId, agentOrgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", agentId.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        String callerOrgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        assertNotViewerWrite(agentOrgId, tenantId, callerOrgRole, agentId, "mutate config of");
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", agentId.toString(), callerOrgRole)) {
            logger.warn("OrgAccess deny-list: user {} (role {}) restricted from mutating config of agent {} in org {}",
                    tenantId, callerOrgRole, agentId, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException("agent", agentId.toString());
        }
    }

    /**
     * Clone an agent by creating a copy.
     * Copies: name (with " (Copy)" suffix), description, systemPrompt, modelProvider, modelName,
     * temperature, maxTokens, maxIterations, toolsConfig, workflowId, dataSourceId, config, avatarUrl, isPublic, isActive.
     * Does NOT copy: conversationId (fresh agent, no linked conversation).
     */
    public AgentEntity cloneAgent(UUID sourceId, String tenantId) {
        return cloneAgent(sourceId, tenantId, null);
    }

    public AgentEntity cloneAgent(UUID sourceId, String tenantId, String orgRole) {
        return cloneAgent(sourceId, tenantId, orgRole, null);
    }

    /**
     * Org-aware overload. See {@link #updateAgent(UUID, String, ...)} for the
     * scope semantics. {@code callerOrgId} is gateway-validated.
     */
    public AgentEntity cloneAgent(UUID sourceId, String tenantId, String orgRole, String callerOrgId) {
        AgentEntity source = agentRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + sourceId));

        // Strict-isolation positive scope check (2026-05-18, see isInScope).
        String agentOrgId = source.getOrganizationId();
        if (!isInScope(source, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                        "agent", sourceId.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        assertNotViewerWrite(agentOrgId, tenantId, orgRole, sourceId, "clone");
        // PR-2.f canWrite visibility gate - owner can be restricted too.
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", sourceId.toString(), orgRole)) {
            logger.warn("OrgAccess deny-list: user {} restricted from cloning agent {} in org {}",
                    tenantId, sourceId, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "agent", sourceId.toString());
        }

        AgentEntity clone = new AgentEntity(
            tenantId, source.getName() + " (Copy)", source.getDescription(),
            source.getSystemPrompt(), source.getModelProvider(), source.getModelName(),
            source.getTemperature(), source.getMaxTokens(), source.getMaxIterations(),
            normalizeToolsConfig(source.getToolsConfig()), source.getWorkflowId(), source.getDataSourceId(),
            null,  // conversationId = null (fresh)
            source.getConfig(), source.getIsPublic(), source.getIsActive()
        );
        clone.setAvatarUrl(source.getAvatarUrl());
        clone.setExecutionTimeout(source.getExecutionTimeout());
        clone.setInactivityTimeout(source.getInactivityTimeout());
        // Audit 2026-05-17 round-3 - clone lands in CALLER's workspace, not source's.
        // The caller-org is forwarded as callerOrgId by every public path; the MCP
        // tool path doesn't (yet) wire a clone action, but if it ever does the same
        // contract applies. Personal-workspace clones leave organization_id NULL.
        if (callerOrgId != null && !callerOrgId.isBlank()) {
            clone.setOrganizationId(callerOrgId);
        }
        return agentRepository.save(clone);
    }

    /**
     * Assign an agent to a project.
     */
    public boolean assignToProject(UUID agentId, UUID projectId, String tenantId) {
        String orgScope = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        String orgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        return agentRepository.findById(agentId)
            .filter(a -> matchesProjectWorkspace(a, tenantId, orgScope))
            .filter(a -> canWriteOrgAgent(a, tenantId, orgScope, orgRole))
            .map(a -> {
                assertNotViewerWrite(a.getOrganizationId(), tenantId, orgRole, a.getId(), "assign to a project");
                a.setProjectId(projectId);
                agentRepository.save(a);
                return true;
            })
            .orElse(false);
    }

    /**
     * Remove an agent from a project.
     */
    public boolean removeFromProject(UUID agentId, UUID projectId, String tenantId) {
        String orgScope = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        String orgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();
        return agentRepository.findById(agentId)
            .filter(a -> matchesProjectWorkspace(a, tenantId, orgScope) && projectId.equals(a.getProjectId()))
            .filter(a -> canWriteOrgAgent(a, tenantId, orgScope, orgRole))
            .map(a -> {
                assertNotViewerWrite(a.getOrganizationId(), tenantId, orgRole, a.getId(), "remove from a project");
                a.setProjectId(null);
                agentRepository.save(a);
                return true;
            })
            .orElse(false);
    }

    /**
     * Unassign all agents from a project. Batch A2 - when an org workspace is
     * in request scope we route to the org-strict finder so the unassign sweeps
     * only the workspace's agents, not every tenant row a project might link.
     */
    public void unassignAllFromProject(UUID projectId, String tenantId) {
        String orgScope = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        List<AgentEntity> agents = (orgScope != null && !orgScope.isBlank())
                ? agentRepository.findByProjectIdAndOrganizationIdStrict(projectId, orgScope)
                : agentRepository.findByProjectIdAndTenantId(projectId, tenantId);
        for (AgentEntity agent : agents) {
            agent.setProjectId(null);
            agentRepository.save(agent);
        }
    }

    private boolean matchesProjectWorkspace(AgentEntity agent, String tenantId, String orgScope) {
        return ScopeGuard.isInStrictScope(tenantId, orgScope, agent.getTenantId(), agent.getOrganizationId());
    }

    private boolean canWriteOrgAgent(AgentEntity agent, String tenantId, String orgScope, String orgRole) {
        String agentOrgId = agent.getOrganizationId();
        if (agentOrgId == null || agentOrgId.isBlank()) {
            return true;
        }
        String effectiveOrgId = orgScope != null && !orgScope.isBlank() ? orgScope : agentOrgId;
        return orgAccessService.canWrite(effectiveOrgId, tenantId, "agent", agent.getId().toString(), orgRole);
    }

    /**
     * Backward-compat overload. Equivalent to {@link #deleteAgent(UUID, String, String)}
     * with {@code orgRole = null} - the org-scoped deny-list check runs with
     * non-admin semantics. Used by internal callers (cascade flows, tool modules)
     * that do not carry an explicit role context. HTTP callers MUST use the
     * 3-arg overload with the gateway-validated {@code X-Organization-Role}.
     */
    public void deleteAgent(UUID id, String tenantId) {
        deleteAgent(id, tenantId, null);
    }

    public void deleteAgent(UUID id, String tenantId, String orgRole) {
        deleteAgent(id, tenantId, orgRole, null);
    }

    /**
     * Org-aware overload. See {@link #updateAgent} for scope semantics.
     */
    public void deleteAgent(UUID id, String tenantId, String orgRole, String callerOrgId) {
        AgentEntity existing = agentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));

        // Strict-isolation positive scope check (2026-05-18, see isInScope).
        // Round-2 audit (2026-05-16) replaced the prior canAccess deny-list
        // path that let Carol cross-org DELETE Alice's agent; 2026-05-18 also
        // closes the workspace-mismatch leak (caller in OrgA deleting their
        // personal agent because userId matched).
        String agentOrgId = existing.getOrganizationId();
        if (!isInScope(existing, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                        "agent", id.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        assertNotViewerWrite(agentOrgId, tenantId, orgRole, id, "delete");
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", id.toString(), orgRole)) {
            logger.warn("OrgAccess deny-list: user {} restricted from deleting agent {} in org {}",
                    tenantId, id, agentOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "agent", id.toString());
        }

        // Delete associated conversations. Forward the AGENT's own workspace
        // (agentOrgId) so the cascade's strict-scope gate matches org-tagged
        // rows. The 2-arg overload defaults orgId=null, which makes
        // ScopeGuard.isInStrictScope skip every conversation whose
        // organization_id is non-null - i.e. a team agent's conversation
        // survives the delete, orphaned and still active. This cascade runs in
        // an async-friendly context, so the org must be passed explicitly
        // rather than read from RequestContextHolder.
        if (conversationServiceClient != null) {
            try {
                conversationServiceClient.deleteConversationsByAgentId(id.toString(), tenantId, agentOrgId);
            } catch (Exception e) {
                logger.warn("Failed to delete conversations for agent {}: {}", id, e.getMessage());
            }
        }

        // Delete observability data (executions, stats) to prevent orphaned metrics
        if (metricsAggregationRepository != null) {
            try {
                metricsAggregationRepository.deleteExecutionsByAgent(tenantId, id);
                metricsAggregationRepository.deleteToolCallStatsByAgent(tenantId, id);
                metricsAggregationRepository.deleteSubAgentCallStats(tenantId, id);
                // Option D org-aware fleet rollups - purge the four per-agent rollups too,
                // else the deleted agent's rows would surface in /agents/stats (the reads
                // apply no agent-exists filter, matching the legacy scan).
                metricsAggregationRepository.deleteToolCallStatsByAgentOrg(tenantId, id);
                metricsAggregationRepository.deleteResourceCallStatsByAgentOrg(tenantId, id);
                metricsAggregationRepository.deleteModelExecStatsByAgentOrg(tenantId, id);
                metricsAggregationRepository.deleteSubAgentCallStatsOrg(tenantId, id);
            } catch (Exception e) {
                logger.warn("Failed to delete metrics for agent {}: {}", id, e.getMessage());
            }
        }

        // Delete webhook tokens (no JPA cascade - separate table).
        // Audit 2026-05-17 round-3 F18 - bare swallow with no log made
        // orphan webhook tokens invisible. Promote to WARN so the cleanup
        // gap shows up in logs without aborting the agent delete.
        if (webhookTokenService != null) {
            try {
                webhookTokenService.deleteWebhook(id);
            } catch (Exception e) {
                logger.warn("Failed to delete webhook token for agent {}: {}", id, e.getMessage());
            }
        }

        // Delete schedule in trigger-service (external service, best-effort).
        // Forward X-User-ID + X-Organization-ID so the trigger-service's
        // owner-or-org scope filter (audit 2026-05-16 round-3) can verify the
        // caller has authority over the agent's schedules.
        if (restTemplate != null && triggerServiceUrl != null) {
            try {
                org.springframework.http.HttpHeaders schedHeaders = new org.springframework.http.HttpHeaders();
                if (tenantId != null && !tenantId.isBlank()) {
                    schedHeaders.set("X-User-ID", tenantId);
                }
                String schedOrgId = existing.getOrganizationId();
                if (schedOrgId != null && !schedOrgId.isBlank()) {
                    schedHeaders.set("X-Organization-ID", schedOrgId);
                }
                restTemplate.exchange(
                        triggerServiceUrl + "/api/internal/trigger/schedules/by-agent/" + id,
                        HttpMethod.DELETE, new HttpEntity<>(schedHeaders), Void.class);
            } catch (Exception e) {
                logger.debug("No schedule to delete for agent {}: {}", id, e.getMessage());
            }
        }

        agentRepository.delete(existing);
    }

    // ==================== Credit Budget ====================

    /**
     * Check if the agent's periodic budget needs to be reset, and reset if so.
     * Returns true if a reset was performed.
     */
    public boolean resetBudgetIfNeeded(UUID agentId) {
        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null || agent.getCreditBudget() == null) return false;

        String mode = agent.getBudgetResetMode();
        if (mode == null || "cumulative".equals(mode)) return false;

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime lastReset = agent.getBudgetLastReset() != null
            ? agent.getBudgetLastReset().atZone(ZoneOffset.UTC)
            : agent.getCreatedAt().atZone(ZoneOffset.UTC);

        boolean shouldReset = false;
        if ("monthly".equals(mode)) {
            shouldReset = now.getYear() != lastReset.getYear()
                || now.getMonthValue() != lastReset.getMonthValue();
        } else if ("weekly".equals(mode)) {
            WeekFields weekFields = WeekFields.of(Locale.getDefault());
            int nowWeek = now.get(weekFields.weekOfWeekBasedYear());
            int lastWeek = lastReset.get(weekFields.weekOfWeekBasedYear());
            shouldReset = now.getYear() != lastReset.getYear() || nowWeek != lastWeek;
        }

        if (shouldReset) {
            // Targeted UPDATE with CAS on budget_last_reset + atomic credits_reserved = 0 gate.
            // See the project docs - the prior pattern
            // (setCreditsConsumed(ZERO); save(agent)) would silently no-op because
            // credits_consumed carries updatable=false in AgentEntity.
            //
            // Dispatch on nullness of budgetLastReset to avoid Postgres 42P18 - see
            // AgentRepository.resetConsumedIfFirstReset JavaDoc + BudgetResolver §4.6.
            Instant expectedLastReset = agent.getBudgetLastReset();
            int updated = expectedLastReset == null
                ? agentRepository.resetConsumedIfFirstReset(agentId, Instant.now())
                : agentRepository.resetConsumedIfUnreservedAndUnchanged(
                    agentId, Instant.now(), expectedLastReset);
            if (updated == 0) {
                // Lost CAS race OR blocked by in-flight reservation. Idempotent: the
                // next poll re-reads and either skips (already reset) or retries.
                logger.info("Auto-reset for agent {} (mode={}) did not apply - "
                    + "lost CAS race or credits_reserved > 0", agentId, mode);
                return false;
            }
            logger.info("Reset credit budget for agent {} (mode={})", agentId, mode);
            return true;
        }
        return false;
    }

    /**
     * Manually reset credits consumed for an agent (backs POST /api/agents/{id}/budget/reset).
     *
     * <p>The controller pre-checks {@code credits_reserved == 0} and returns 409 upfront
     * if reservations are held; a return of 0 from the targeted query here means a race
     * slipped through between pre-check and UPDATE - callers should map it to 409.
     *
     * @return true if the reset was applied, false if blocked by an in-flight reservation
     *         that appeared between the controller pre-check and the UPDATE.
     */
    public boolean resetCredits(UUID agentId, String tenantId) {
        return resetCredits(agentId, tenantId, null);
    }

    /**
     * Org-aware overload - accepts orgRole for the write visibility gate. Audit 2026-05-16:
     * prior implementation was strict-tenant-only AND had no org access gate.
     * Teammates of an org-shared agent's owner couldn't reset its budget.
     */
    public boolean resetCredits(UUID agentId, String tenantId, String orgRole) {
        return resetCredits(agentId, tenantId, orgRole, null);
    }

    /**
     * Org-aware overload. See {@link #updateAgent} for scope semantics.
     */
    public boolean resetCredits(UUID agentId, String tenantId, String orgRole, String callerOrgId) {
        AgentEntity agent = agentRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        // Strict-isolation positive scope check (2026-05-18, see isInScope).
        String agentOrgId = agent.getOrganizationId();
        if (!isInScope(agent, tenantId, callerOrgId)) {
            if (agentOrgId != null) {
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                        "agent", agentId.toString());
            }
            throw new IllegalArgumentException("Agent tenant mismatch");
        }
        assertNotViewerWrite(agentOrgId, tenantId, orgRole, agentId, "reset budget of");
        if (agentOrgId != null
                && !orgAccessService.canWrite(agentOrgId, tenantId, "agent", agentId.toString(), orgRole)) {
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "agent", agentId.toString());
        }
        // Targeted UPDATE. The credits_reserved = 0 gate is atomic in the WHERE clause
        // (no in-memory pre-check needed). See the project docs
        int updated = agentRepository.zeroCreditsConsumedById(agentId, Instant.now());
        if (updated == 0) {
            logger.info("Manual reset for agent {} refused - credits_reserved > 0 "
                + "(race vs. concurrent spawn between controller pre-check and UPDATE)",
                agentId);
            return false;
        }
        logger.info("Manually reset credits for agent {}", agentId);
        return true;
    }

    // ==================== Avatar Presets ====================

    /** Must match AVATAR_PRESETS in frontend/components/agents/AvatarPicker.tsx */
    private static final String[] AVATAR_PRESETS = {
        "preset:purple", "preset:blue", "preset:green", "preset:orange", "preset:pink",
        "preset:yellow", "preset:teal", "preset:indigo", "preset:slate", "preset:red",
        "preset:emerald", "preset:coral", "preset:gold", "preset:cyan", "preset:lavender",
        "preset:burgundy", "preset:fuchsia", "preset:lime", "preset:sand", "preset:mint",
        "preset:olive", "preset:periwinkle", "preset:peach", "preset:navy", "preset:wine",
        "preset:charcoal", "preset:forest", "preset:bubblegum", "preset:arctic", "preset:sunshine"
    };

    private static final java.util.Set<String> AVATAR_PRESET_IDS = java.util.Set.of(AVATAR_PRESETS);

    private static String randomAvatarPreset() {
        return AVATAR_PRESETS[ThreadLocalRandom.current().nextInt(AVATAR_PRESETS.length)];
    }

    /** The known avatar preset ids (e.g. "preset:blue"), for validating a supplied avatar. */
    public static java.util.List<String> avatarPresetIds() {
        return java.util.List.of(AVATAR_PRESETS);
    }

    /** True when {@code presetId} (the part before any "?c1=..") is a known avatar preset. */
    public static boolean isKnownAvatarPreset(String presetId) {
        return AVATAR_PRESET_IDS.contains(presetId);
    }

    /** Must match AVATAR_TOOLS in frontend/components/agents/avatarTools.ts */
    private static final String[] AVATAR_TOOLS = {
        "wrench", "hammer", "code", "terminal", "cpu", "database", "cloud", "bug", "git-branch",
        "key", "lock", "shield", "search", "chart", "calculator", "flask", "microscope",
        "compass", "lightbulb", "headset", "megaphone", "mail", "phone", "globe", "languages",
        "briefcase", "calendar", "dollar", "scale", "newspaper", "mic", "paintbrush", "palette",
        "camera", "music", "film", "pen", "book", "graduation-cap", "rocket", "coffee",
        "gamepad", "wand", "heart", "star", "leaf", "plane", "map", "stethoscope", "utensils",
        "dumbbell", "truck", "shopping-cart", "bot", "crown", "trophy", "medal", "award", "gem",
        "target", "zap", "flame", "handshake", "glasses", "swords", "brain"
    };

    private static final java.util.Set<String> AVATAR_TOOL_IDS = java.util.Set.of(AVATAR_TOOLS);

    /** The known avatar tool-badge ids (e.g. "wrench"), for validating a supplied avatar. */
    public static java.util.List<String> avatarToolIds() {
        return java.util.List.of(AVATAR_TOOLS);
    }

    /** True when {@code toolId} (the "?tool=.." value) is a known avatar tool badge. */
    public static boolean isKnownAvatarTool(String toolId) {
        return AVATAR_TOOL_IDS.contains(toolId);
    }

    // ==================== Validation ====================

    private void validateCreateOrUpdate(String tenantId,
                                        String name,
                                        BigDecimal temperature,
                                        Integer maxTokens,
                                        Integer maxIterations,
                                        Integer executionTimeout) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("name cannot exceed 255 characters");
        }
        if (temperature != null && (temperature.compareTo(BigDecimal.ZERO) < 0 || temperature.compareTo(BigDecimal.valueOf(2)) > 0)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (maxIterations != null && (maxIterations < 1 || maxIterations > 1000)) {
            throw new IllegalArgumentException("max_iterations must be between 1 and 1000");
        }
        if (executionTimeout != null && (executionTimeout < 10 || executionTimeout > 7200)) {
            throw new IllegalArgumentException("execution_timeout must be between 10 and 7200 (seconds)");
        }
    }

    /**
     * Delegates to {@link com.apimarketplace.agent.config.GuardOverrides#validate} - the
     * shared source of truth for guard-key names and range rules (mirrored by V100 CHECK
     * constraints and by conversation-scope chatConfig.turnLimits validation).
     */
    private void validateGuardOverrides(Map<String, Integer> guardOverrides) {
        com.apimarketplace.agent.config.GuardOverrides.validate(guardOverrides);
    }

    /**
     * Reject a non-blank {@code reasoningEffort} that isn't a known
     * {@link ReasoningEffort} level. Null/blank is allowed (= inherit the
     * per-model default, then the CLI's own default).
     */
    private void validateReasoningEffort(String reasoningEffort) {
        if (!ReasoningEffort.isValidOrBlank(reasoningEffort)) {
            throw new IllegalArgumentException("Invalid reasoningEffort '" + reasoningEffort
                + "'. Expected one of: " + ReasoningEffort.validValuesCsv()
                + " (or empty to inherit).");
        }
    }

    /**
     * Applies guard overrides to an entity. Only keys present in the map are touched;
     * a null value clears the column (→ YAML default at resolve time).
     */
    private void applyGuardOverrides(AgentEntity entity, Map<String, Integer> guardOverrides) {
        if (guardOverrides == null || guardOverrides.isEmpty()) return;
        if (guardOverrides.containsKey(com.apimarketplace.agent.config.GuardOverrides.MAX_PER_RESOURCE_PER_TURN)) {
            entity.setMaxPerResourcePerTurn(guardOverrides.get(com.apimarketplace.agent.config.GuardOverrides.MAX_PER_RESOURCE_PER_TURN));
        }
        if (guardOverrides.containsKey(com.apimarketplace.agent.config.GuardOverrides.LOOP_IDENTICAL_STOP)) {
            entity.setLoopIdenticalStop(guardOverrides.get(com.apimarketplace.agent.config.GuardOverrides.LOOP_IDENTICAL_STOP));
        }
        if (guardOverrides.containsKey(com.apimarketplace.agent.config.GuardOverrides.LOOP_CONSECUTIVE_STOP)) {
            entity.setLoopConsecutiveStop(guardOverrides.get(com.apimarketplace.agent.config.GuardOverrides.LOOP_CONSECUTIVE_STOP));
        }
    }

    /**
     * Validates that setting sub-agents does not create a circular reference.
     * Traverses the sub-agent graph to detect if any transitive sub-agent
     * leads back to the current agent.
     *
     * @param agentId     the agent being updated (null for create -- skips cycle check)
     * @param toolsConfig the proposed toolsConfig
     * @param tenantId    the tenant scope
     */
    @SuppressWarnings("unchecked")
    private void validateNoCircularAgentReferences(UUID agentId, Map<String, Object> toolsConfig, String tenantId) {
        if (toolsConfig == null || agentId == null) return;

        Object agentsObj = toolsConfig.get("agents");
        if (!(agentsObj instanceof List<?> agentsList) || agentsList.isEmpty()) return;

        // Parse sub-agent UUIDs
        Set<UUID> newSubAgents = new HashSet<>();
        for (Object item : agentsList) {
            if (item instanceof String str) {
                try {
                    newSubAgents.add(UUID.fromString(str));
                } catch (IllegalArgumentException ignored) { }
            }
        }

        if (newSubAgents.isEmpty()) return;

        // Self-reference check
        if (newSubAgents.contains(agentId)) {
            throw new IllegalArgumentException("Agent cannot reference itself as a sub-agent");
        }

        // Load all agents WITH pessimistic lock (SELECT FOR UPDATE) - serializes
        // concurrent sub-agent updates so two parallel updates can't each pass
        // the cycle check independently but together create a circular ref.
        // Batch A2 (2026-05-20): scope the lock to the active workspace. The
        // graph that matters for cycles is the org's visible agents, not every
        // row tagged with the calling user's tenantId across orgs.
        String orgScope = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        List<AgentEntity> allAgents = (orgScope != null && !orgScope.isBlank())
                ? agentRepository.findByOrganizationIdForUpdateStrict(orgScope)
                : agentRepository.findByTenantIdForUpdate(tenantId);

        Map<UUID, Set<UUID>> graph = new HashMap<>();
        for (AgentEntity a : allAgents) {
            graph.put(a.getId(), extractSubAgentIds(a.getToolsConfig()));
        }

        // Apply proposed change
        graph.put(agentId, newSubAgents);

        // DFS from each new sub-agent: if any path leads back to agentId, it's a cycle
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> stack = new ArrayDeque<>(newSubAgents);
        while (!stack.isEmpty()) {
            UUID current = stack.pop();
            if (current.equals(agentId)) {
                throw new IllegalArgumentException("Circular agent reference detected: one of the selected sub-agents already has this agent as a sub-agent (directly or transitively)");
            }
            if (visited.add(current)) {
                Set<UUID> children = graph.getOrDefault(current, Set.of());
                stack.addAll(children);
            }
        }
    }

    private Set<UUID> extractSubAgentIds(Map<String, Object> toolsConfig) {
        if (toolsConfig == null) return Set.of();
        Object agentsObj = toolsConfig.get("agents");
        if (!(agentsObj instanceof List<?> list)) return Set.of();
        Set<UUID> result = new HashSet<>();
        for (Object item : list) {
            if (item instanceof String str) {
                try {
                    result.add(UUID.fromString(str));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return result;
    }
}
