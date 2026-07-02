package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.ModelExecutionLinkEntity;
import com.apimarketplace.agent.domain.ModelExecutionLinkScope;
import com.apimarketplace.agent.repository.ModelExecutionLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralised resolver + admin store for model execution links (CLOUD only).
 *
 * <p>A link maps a BILLED {@code (provider, model)} pair to an EXECUTION target -
 * a CLI bridge (claude-code, ...) OR a regular API provider (e.g. openrouter). At
 * execution time {@link #resolve(String, String)} answers "is this billed pair
 * linked, and if so which provider/model runs it?". Callers run the agent on the
 * execution target, then re-stamp the BILLED identity onto the response so credit
 * consumption stays on the billed price. The billed identity is NEVER changed by
 * this service - only the execution transport.
 *
 * <p>Gated behind {@code model-catalog.execution-links.enabled}: present in cloud
 * (default on), absent in the CE monolith (default off in
 * {@code application-ce.yml}). Every caller injects it {@code @Autowired(required
 * = false)} so a null bean = feature inert.
 *
 * <p>Enabled links are cached in-memory ({@link #CACHE_TTL_MS}); the set is tiny
 * (an admin configures a handful) so a full reload is cheap, and writes
 * invalidate the cache immediately.
 */
@Service
@ConditionalOnProperty(name = "model-catalog.execution-links.enabled", havingValue = "true", matchIfMissing = true)
public class ModelExecutionLinkService {

    private static final Logger log = LoggerFactory.getLogger(ModelExecutionLinkService.class);

    private static final long CACHE_TTL_MS = 60_000L;

    private final ModelExecutionLinkRepository repository;

    /** Snapshot of ENABLED links keyed by {@link #key(String, String)}. */
    private volatile Map<String, ExecutionRoute> cache = Map.of();
    private volatile long cacheExpiresAt = 0L;
    /**
     * Bumped by every write. A rebuild that started before a concurrent write
     * (so its {@code findAll()} may predate the commit) is discarded instead of
     * masking the write for a full TTL - closes the cache TOCTOU window.
     */
    private final java.util.concurrent.atomic.AtomicLong writeVersion = new java.util.concurrent.atomic.AtomicLong();

    public ModelExecutionLinkService(ModelExecutionLinkRepository repository) {
        this.repository = repository;
    }

    /**
     * The execution target a billed model is linked to: a CLI bridge OR a regular
     * API provider. {@code executionModel} is already resolved (never null): it
     * falls back to the billed model id when the row left it blank.
     */
    public record ExecutionRoute(String executionProvider, String executionModel) {}

    /**
     * Resolve the execution route for a billed {@code (provider, model)} pair on a
     * given app surface, or empty when the pair is not linked for that surface / its
     * link is disabled / inputs are blank. Provider matching is case-insensitive
     * (slugs are lowercase); the model id is matched verbatim.
     *
     * <p>Scope precedence: an exact-surface link (resolved from {@code activitySource}
     * via {@link ModelExecutionLinkScope#fromActivitySource(String)}) wins; otherwise
     * the {@link ModelExecutionLinkScope#ALL} wildcard row applies. A disabled
     * exact-surface row is not in the snapshot, so it transparently falls through to
     * the {@link ModelExecutionLinkScope#ALL} row.
     *
     * @param activitySource the run's logical origin (e.g. {@code CHAT}, {@code WORKFLOW});
     *                       {@code null}/unknown ⇒ only the {@link ModelExecutionLinkScope#ALL}
     *                       row can match.
     */
    public Optional<ExecutionRoute> resolve(String billedProvider, String billedModel, String activitySource) {
        if (billedProvider == null || billedProvider.isBlank()
                || billedModel == null || billedModel.isBlank()) {
            return Optional.empty();
        }
        Map<String, ExecutionRoute> snap = snapshot();
        ModelExecutionLinkScope surfaceScope = ModelExecutionLinkScope.fromActivitySource(activitySource);
        if (surfaceScope != null) {
            ExecutionRoute exact = snap.get(key(billedProvider, billedModel, surfaceScope));
            if (exact != null) {
                return Optional.of(exact);
            }
        }
        return Optional.ofNullable(snap.get(key(billedProvider, billedModel, ModelExecutionLinkScope.ALL)));
    }

    /**
     * The effective execution pair for a SINGLE-COMPLETION caller. Unlike the full
     * agent path there is no billed-identity re-stamp to carry: single completions
     * are not billed per-model, so the caller just executes on this pair.
     */
    public record SingleCompletionTarget(String provider, String model) {}

    /**
     * Resolve the execution target for a bare single completion (the
     * {@code json-completion} path: COLD-summary generation, single-turn JSON
     * extraction). This is the third consumer of the link system, alongside the
     * full agent execution ({@code AgentRemoteExecutionService}) and the CE relay
     * ({@code CloudLlmRelayController}).
     *
     * <p>Only {@link ModelExecutionLinkScope#ALL} links apply: a single completion
     * carries no activity source, so no surface-scoped row can match.
     *
     * <p>A link that targets a CLI bridge is NOT executable here - a bridge owns its
     * own agent loop and cannot serve a bare completion (same constraint as the CE
     * relay's {@code BRIDGE_EXECUTION_NOT_RELAYABLE}). Falling through to the billed
     * provider would silently execute on the key the admin linked AWAY from (the
     * misleading "credit balance too low" failure shape), so it throws instead.
     *
     * @throws IllegalArgumentException when the resolved link targets a CLI bridge
     *         (maps to 400 INVALID_ARGUMENT at the controller layer)
     */
    public SingleCompletionTarget resolveSingleCompletionTarget(String billedProvider, String billedModel) {
        ExecutionRoute route = resolve(billedProvider, billedModel, null).orElse(null);
        if (route == null) {
            return new SingleCompletionTarget(billedProvider, billedModel);
        }
        if (com.apimarketplace.agent.service.execution.SubAgentBridgeClient.isBridgeProvider(route.executionProvider())) {
            throw new IllegalArgumentException(
                "BRIDGE_EXECUTION_NOT_RELAYABLE: model execution link routes " + billedProvider + "/" + billedModel
                    + " to CLI bridge " + route.executionProvider()
                    + ", which cannot serve a single JSON completion. Point the link's execution target at an"
                    + " API provider, or use a non-linked model for this call.");
        }
        log.debug("Single-completion link route: billed={}/{} -> exec={}/{}",
            billedProvider, billedModel, route.executionProvider(), route.executionModel());
        return new SingleCompletionTarget(route.executionProvider(), route.executionModel());
    }

    private Map<String, ExecutionRoute> snapshot() {
        long now = System.currentTimeMillis();
        if (now < cacheExpiresAt) {
            return cache;
        }
        long versionAtReadStart = writeVersion.get();
        Map<String, ExecutionRoute> rebuilt = new ConcurrentHashMap<>();
        for (ModelExecutionLinkEntity e : repository.findAll()) {
            if (!e.isEnabled()) continue;
            String executionModel = (e.getExecutionModel() != null && !e.getExecutionModel().isBlank())
                    ? e.getExecutionModel()
                    : e.getBilledModel();
            rebuilt.put(key(e.getBilledProvider(), e.getBilledModel(), e.getScope()),
                    new ExecutionRoute(e.getExecutionProvider(), executionModel));
        }
        // Publish only if no write landed while we were loading: otherwise this
        // snapshot may predate the commit and would mask the write for a full TTL.
        if (writeVersion.get() == versionAtReadStart) {
            cache = rebuilt;
            cacheExpiresAt = now + CACHE_TTL_MS;
        }
        return rebuilt;
    }

    private void invalidate() {
        writeVersion.incrementAndGet();
        cacheExpiresAt = 0L;
    }

    private static String key(String provider, String model, ModelExecutionLinkScope scope) {
        return provider.trim().toLowerCase() + " " + model.trim() + " " + scope.name();
    }

    // ── Admin CRUD ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ModelExecutionLinkEntity> list() {
        return repository.findAllByOrderByBilledProviderAscBilledModelAscScopeAsc();
    }

    /**
     * Create or update the link for a billed {@code (provider, model)} pair.
     * {@code executionProvider} may be ANY provider slug - a CLI bridge (claude-code,
     * codex, gemini-cli, mistral-vibe) OR a regular API provider (e.g. openrouter);
     * the routing layer dispatches a bridge slug to the CLI bridge and any other
     * provider to the direct agent loop. {@code executionModel} is free (any id,
     * blank ⇒ reuse the billed model). The mapping is intentionally unrestricted -
     * the real output comes from the chosen provider while the bill stays the billed
     * identity. The admin is responsible for the execution provider being configured
     * (e.g. an OpenRouter API key present); an unconfigured provider fails the run.
     * {@code scope} narrows the link to one app surface ({@code null} ⇒
     * {@link ModelExecutionLinkScope#ALL}, the wildcard).
     *
     * @throws IllegalArgumentException on blank required fields
     */
    @Transactional
    public ModelExecutionLinkEntity upsert(String billedProvider, String billedModel,
                                           String executionProvider, String executionModel,
                                           ModelExecutionLinkScope scope, boolean enabled) {
        require(billedProvider, "billedProvider");
        require(billedModel, "billedModel");
        require(executionProvider, "executionProvider");
        ModelExecutionLinkScope resolvedScope = scope == null ? ModelExecutionLinkScope.ALL : scope;
        ModelExecutionLinkEntity entity = repository
                .findByBilledProviderAndBilledModelAndScope(
                        billedProvider.trim().toLowerCase(), billedModel.trim(), resolvedScope)
                .orElseGet(ModelExecutionLinkEntity::new);
        entity.setBilledProvider(billedProvider.trim().toLowerCase());
        entity.setBilledModel(billedModel.trim());
        entity.setExecutionProvider(executionProvider.trim().toLowerCase());
        entity.setExecutionModel(executionModel == null || executionModel.isBlank() ? null : executionModel.trim());
        entity.setScope(resolvedScope);
        entity.setEnabled(enabled);
        ModelExecutionLinkEntity saved = repository.save(entity);
        invalidate();
        log.info("Model execution link upserted: {}/{} -> {}/{} (scope={}, enabled={})",
                saved.getBilledProvider(), saved.getBilledModel(),
                saved.getExecutionProvider(), saved.getExecutionModel(), saved.getScope(), saved.isEnabled());
        return saved;
    }

    @Transactional
    public boolean delete(String billedProvider, String billedModel, ModelExecutionLinkScope scope) {
        require(billedProvider, "billedProvider");
        require(billedModel, "billedModel");
        ModelExecutionLinkScope resolvedScope = scope == null ? ModelExecutionLinkScope.ALL : scope;
        Optional<ModelExecutionLinkEntity> existing = repository
                .findByBilledProviderAndBilledModelAndScope(
                        billedProvider.trim().toLowerCase(), billedModel.trim(), resolvedScope);
        if (existing.isEmpty()) {
            return false;
        }
        repository.delete(existing.get());
        invalidate();
        log.info("Model execution link deleted: {}/{} (scope={})", billedProvider, billedModel, resolvedScope);
        return true;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
