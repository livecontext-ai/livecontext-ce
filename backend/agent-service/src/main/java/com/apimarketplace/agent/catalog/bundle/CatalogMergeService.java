package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.bridge.BridgeAllowlist;
import com.apimarketplace.agent.catalog.CatalogDefaults;
import com.apimarketplace.agent.config.ModelPricingConfig;
import com.apimarketplace.agent.domain.ModelCategory;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsId;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;


/**
 * Shared merge loop used by {@link CatalogBundleApplier} (bundle-driven,
 * activates a row in {@code catalog_bundles}) and by
 * {@code ModelCatalogSyncService} (feed-driven, writes nothing to
 * {@code catalog_bundles}). Both paths must honour the same invariants:
 *
 * <ul>
 *   <li>{@code is_custom=true} rows are CE-local additions - never touched.</li>
 *   <li>Each field listed in a row's {@code user_modified_fields} array is
 *       preserved from the local row; all other fields are overwritten.</li>
 *   <li>Rows present locally but absent from the incoming set are
 *       {@code deprecated_at=now()} - but only when {@link MergeOptions#deprecateMissing}
 *       is true (sync path defaults to false to avoid mass-deprecate on a
 *       partial feed; bundle path defaults to true because a bundle IS a
 *       complete authoritative snapshot).</li>
 *   <li>Pricing changes are collected and flushed to auth-service via
 *       {@link AuthPricingSyncClient} AFTER the enclosing transaction commits -
 *       the caller is responsible for having an active {@code @Transactional}
 *       boundary so the afterCommit hook fires correctly.</li>
 * </ul>
 *
 * <p>This class is intentionally not annotated with {@code @Transactional}: the
 * caller ({@code CatalogBundleApplier.apply()} or
 * {@code ModelCatalogSyncService.syncNow()}) owns the TX boundary, ensuring
 * rollback of the entire batch on any failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogMergeService {

    private final ModelConfigOverrideRepository modelRepo;
    private final ModelCategorySettingsRepository categoryRepo;
    private final AuthPricingSyncClient authPricingSyncClient;
    private final CatalogDefaults catalogDefaults;

    /**
     * The curated {@code ai.agent.rate-limits} table, consulted only to decide
     * whether a model already has a researched per-model limit that the generic
     * fallback must not overwrite. See {@link #hasCuratedRateLimit}.
     *
     * <p>Setter-injected rather than added to the Lombok constructor: six unit
     * tests build this service directly, and none of them exercises rate-limit
     * fallbacks. A null value simply restores the previous "always stamp the
     * fallback" behaviour, so those harnesses stay valid without edits.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelPricingConfig modelPricingConfig;

    /** Per-row pricing change queued for the afterCommit mirror. */
    private record PricingChange(String provider, String modelId,
                                 BigDecimal priceInput, BigDecimal priceOutput,
                                 String providerKind,
                                 BigDecimal priceCacheRead, BigDecimal priceCacheWrite) {}

    public record MergeResult(int inserted, int updated, int deprecated,
                              int skippedCustom, int skippedUserModified,
                              int pricingChangeCount) {
        public static MergeResult empty() {
            return new MergeResult(0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Apply the provided model maps to {@code model_config_overrides}. Returns
     * counts per bucket. Caller must hold a transaction for the afterCommit
     * pricing mirror to fire.
     */
    public MergeResult merge(List<Map<String, Object>> modelMaps, MergeOptions opts) {
        Objects.requireNonNull(modelMaps, "modelMaps");
        Objects.requireNonNull(opts, "opts");

        Instant now = Instant.now();

        // Index incoming models by (provider, modelId) for O(1) absence detection.
        Set<String> incomingKeys = new HashSet<>(modelMaps.size());
        for (Map<String, Object> m : modelMaps) {
            String p = str(m.get("provider"));
            String id = str(m.get("modelId"));
            if (p != null && id != null) incomingKeys.add(keyOf(p, id));
        }

        int inserted = 0, updated = 0, deprecated = 0;
        int skippedCustom = 0, skippedUserModified = 0;
        List<PricingChange> pricingChanges = new ArrayList<>();

        // Baseline for assigning sequential rankings to newly inserted models
        // so they appear at the bottom of the admin list in stable order.
        int nextRanking = modelRepo.findMaxRanking() + 1;

        // Track persisted (provider, modelId) → row.id for the category-apply
        // post-pass. Populated below as rows land in DB so the FK on
        // model_category_settings is always satisfiable.
        Map<String, Long> idByKey = new HashMap<>();
        // Track (provider, modelId) → categories map taken verbatim from the
        // payload. Applied in a second pass once all model rows are persisted.
        Map<String, Map<String, Map<String, Object>>> categoriesByKey = new HashMap<>();
        // Track NEWLY INSERTED rows whose payload carried NO `categories` sidecar
        // (the model-catalog SEED shape: {version, issuer, models[]} - buildSeedExport
        // omits the categories sidecar). Without a category row a model is invisible
        // to every category-scoped selector (chat / browser_agent picker) even though
        // it lives in model_config_overrides. Step 4 backfills mode-aware defaults for
        // these so a seed-shipped model is selectable out of the box. Value = the
        // row's `mode` (null/'chat' → chat+browser_agent ; 'image' → image_generation),
        // mirroring ModelCategory.acceptsMode. The bundle path never lands here (its
        // payload always declares categories), so this is a no-op for bundle applies.
        Map<String, String> insertedNoCategoryMode = new HashMap<>();

        // 1. Upsert incoming rows.
        for (Map<String, Object> m : modelMaps) {
            String provider = str(m.get("provider"));
            String modelId  = str(m.get("modelId"));
            if (provider == null || modelId == null) continue;

            // Capture the bundle's per-category overrides for this row (V156).
            // null when missing, kept verbatim - applied in step 4 below.
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> rowCategories =
                    m.get("categories") instanceof Map<?, ?> raw
                            ? (Map<String, Map<String, Object>>) raw
                            : null;
            if (rowCategories != null) {
                categoriesByKey.put(keyOf(provider, modelId), rowCategories);
            }

            Optional<ModelConfigOverrideEntity> existing =
                    modelRepo.findByProviderAndModelId(provider, modelId);

            if (existing.isEmpty()) {
                ModelConfigOverrideEntity row = new ModelConfigOverrideEntity();
                row.setProvider(provider);
                row.setModelId(modelId);
                // Prefer the row-level source (stamped by feed parsers:
                // 'litellm' / 'openrouter'); fall back to the merge-option
                // default (bundle-path sets 'bundle').
                String rowSource = str(m.get("source"));
                row.setSource(rowSource != null ? rowSource : opts.source());
                applyFields(row, m, Collections.emptySet(), opts.partialUpdate());
                // Insert-time `enabled` policy, per caller intent
                // (opts.honorEnabledOnInsert - see MergeOptions):
                //   • Feed sync (LiteLLM/OpenRouter, forSync=false): new models
                //     land INACTIVE regardless of the payload. Feeds are
                //     untrusted and can introduce many models at once;
                //     auto-enabling would silently expose un-reviewed models to
                //     the picker and chat. The admin opts each one in via
                //     /settings/ai-providers.
                //   • Signed BUNDLE (forBundle=true, since V381): the payload's
                //     `enabled` is honored. It is resolved cloud-side as
                //     bundle_enabled ?? enabled - an explicit, signed per-model
                //     cloud-admin decision about what CE installs get - so
                //     force-disabling here would silently veto that decision
                //     for every model the CE had not seen yet. Enabling grants
                //     no capability by itself: a provider without keys/bridge
                //     stays out of the picker.
                //   • model-catalog SEED (forSeed=true): keeps the payload's
                //     `enabled` (default true when omitted) - the seed IS the
                //     curated, code-shipped baseline, usable out of the box.
                // Only fresh INSERTS are affected - the update branch below
                // leaves existing rows' enabled untouched.
                if (opts.honorEnabledOnInsert()) {
                    if (row.getEnabled() == null) row.setEnabled(true);
                } else {
                    row.setEnabled(false);
                }
                if (row.getRanking() == null) {
                    row.setRanking(nextRanking++);
                }
                if (opts.bundleVersion() != null) row.setBundleVersion(opts.bundleVersion());
                row.setLastSyncedAt(now);
                // Force provider_kind='bridge' for any insert under one of the
                // 4 local-CLI bridges. Defends against CatalogBundlePayload
                // stripping provider_kind from the canonical serialization -
                // without this, a new bridge row arriving via bundle apply on
                // CE would default to 'byok' and get non-zero pricing treatment.
                if (BridgeAllowlist.isBridgeProvider(provider)) {
                    row.setProviderKind("bridge");
                }
                applyRateLimitDefaults(row);
                ModelConfigOverrideEntity savedRow = modelRepo.save(row);
                // savedRow may be null in unit-test harnesses where the repo
                // mock returns its default (no value stubbed). The category
                // post-pass will then skip this row - acceptable since no
                // FK target exists for it anyway.
                if (savedRow != null && savedRow.getId() != null) {
                    idByKey.put(keyOf(provider, modelId), savedRow.getId());
                    // Seed-path insert with no categories sidecar → schedule a
                    // mode-aware default-category backfill (step 4) so the model
                    // is selectable. Bundle inserts carry categories, so they
                    // populated categoriesByKey above and skip this.
                    if (rowCategories == null || rowCategories.isEmpty()) {
                        insertedNoCategoryMode.put(keyOf(provider, modelId), str(m.get("mode")));
                    }
                }
                inserted++;
                if (row.getPriceInput() != null || row.getPriceOutput() != null) {
                    pricingChanges.add(new PricingChange(provider, modelId,
                            row.getPriceInput(), row.getPriceOutput(),
                            row.getProviderKind(),
                            row.getPriceCacheRead(), row.getPriceCacheWrite()));
                }
                continue;
            }

            ModelConfigOverrideEntity row = existing.get();

            if (row.isCustom()) {
                skippedCustom++;
                // is_custom rows are CE-local - bundles never overwrite them.
                // Don't track the id in idByKey, otherwise the V156 category
                // post-pass would write through to the sidecar and effectively
                // bypass the is_custom guard for the per-category dimension.
                continue;
            }
            idByKey.put(keyOf(provider, modelId), row.getId());

            // Bridge rows are mutable via the bundle path so cloud's
            // BridgeAllowlist (backend/shared-agent-lib/.../bridge/BridgeAllowlist.java)
            // can evolve and CE clients follow on next bundle apply. Protection
            // against a malicious feed writing to a bridge row is enforced
            // upstream: ModelCatalogSyncService.EXCLUDED_PROVIDERS drops bridge
            // rows before they ever reach merge(). Bundle apply is trusted
            // because it's already signature-verified.

            Set<String> protect = Set.of(row.getUserModifiedFields() == null
                    ? new String[0] : row.getUserModifiedFields());
            BigDecimal prevInput  = row.getPriceInput();
            BigDecimal prevOutput = row.getPriceOutput();
            // V491: a cache price moving on its own is a billing change too. Without
            // these two the mirror would keep the stale cache rate until the input or
            // output price happened to move, which for a mature model is never.
            BigDecimal prevCacheRead  = row.getPriceCacheRead();
            BigDecimal prevCacheWrite = row.getPriceCacheWrite();
            applyFields(row, m, protect, opts.partialUpdate());
            if (opts.bundleVersion() != null) row.setBundleVersion(opts.bundleVersion());
            row.setLastSyncedAt(now);
            // Un-deprecate: the incoming set still includes the model.
            row.setDeprecatedAt(null);
            applyRateLimitDefaults(row);
            modelRepo.save(row);
            updated++;
            if (!protect.isEmpty()) skippedUserModified++;

            boolean priceChanged = !Objects.equals(prevInput, row.getPriceInput())
                                || !Objects.equals(prevOutput, row.getPriceOutput())
                                || !Objects.equals(prevCacheRead, row.getPriceCacheRead())
                                || !Objects.equals(prevCacheWrite, row.getPriceCacheWrite());
            if (priceChanged &&
                    (row.getPriceInput() != null || row.getPriceOutput() != null)) {
                pricingChanges.add(new PricingChange(provider, modelId,
                        row.getPriceInput(), row.getPriceOutput(),
                        row.getProviderKind(),
                        row.getPriceCacheRead(), row.getPriceCacheWrite()));
            }
        }

        // 2. Deprecate non-custom rows absent from the incoming set - only
        //    when caller opts in. Sync-path callers default to false because
        //    a single bad feed response would mass-deprecate the catalog.
        //    Bundle-path callers are trusted (the payload is signature-verified)
        //    AND their snapshot includes the bridge rows, so bridges
        //    disappearing from the snapshot = cloud removed them from the
        //    allowlist → CE deprecates accordingly. is_custom rows remain
        //    untouched (CE-local adds).
        if (opts.deprecateMissing()) {
            for (ModelConfigOverrideEntity row : modelRepo.findAllByOrderByRankingAsc()) {
                if (row.isCustom()) continue;
                if (row.getDeprecatedAt() != null) continue;
                if (incomingKeys.contains(keyOf(row.getProvider(), row.getModelId()))) continue;
                row.setDeprecatedAt(now);
                modelRepo.save(row);
                deprecated++;
            }
        }

        // 3. Schedule the pricing mirror for afterCommit.
        schedulePricingSyncAfterCommit(pricingChanges, opts.label());

        // 4. V156 sidecar - apply per-category (rank, enabled) for each row
        // whose payload carried a `categories` field. Custom rows (CE-local
        // adds) are skipped wholesale to mirror the existing is_custom rule
        // for the parent row. Old bundles (no `categories` field) are a
        // no-op here.
        if (categoryRepo != null) {
            for (Map.Entry<String, Long> e : idByKey.entrySet()) {
                Map<String, Map<String, Object>> rowCategories = categoriesByKey.get(e.getKey());
                if (rowCategories == null || rowCategories.isEmpty()) continue;
                Long modelConfigId = e.getValue();
                if (modelConfigId == null) continue;
                applyRowCategories(modelConfigId, rowCategories);
            }
            // 4b. SEED-path backfill: a newly inserted model that carried no
            // categories sidecar gets mode-aware DEFAULT categories so it is
            // assigned to the categories its mode is eligible for. Only
            // chat and browser_agent have a reader; see ModelCategory.
            // Only fresh inserts are touched (never an update), so an admin who
            // deliberately unassigned a category on an existing model is never
            // overridden. Gated on the option so the bundle path (authoritative
            // categories sidecar) stays a strict no-op here.
            if (opts.assignDefaultCategoriesOnInsert()) {
                for (Map.Entry<String, String> e : insertedNoCategoryMode.entrySet()) {
                    Long modelConfigId = idByKey.get(e.getKey());
                    if (modelConfigId == null) continue;
                    assignDefaultCategories(modelConfigId, e.getValue());
                }
            }
        }

        return new MergeResult(inserted, updated, deprecated,
                skippedCustom, skippedUserModified, pricingChanges.size());
    }

    /**
     * Backfill mode-aware DEFAULT category rows for a freshly inserted model
     * whose payload declared no {@code categories} sidecar (the model-catalog
     * SEED shape). A model with zero category rows is invisible to every
     * category-scoped selector, so a seed-shipped model would land in
     * {@code model_config_overrides} yet never appear in the chat / browser_agent
     * picker - this closes that gap.
     *
     * <p>Defaults mirror {@link ModelCategory#acceptsMode}: a chat-capable model
     * ({@code mode} null or {@code "chat"}) is enabled under {@code chat} AND
     * {@code browser_agent}; an image model ({@code mode = "image"}) under
     * {@code image_generation}. Idempotent: an existing sidecar row for the
     * category is left untouched (so a re-applied seed never resurrects a
     * category an admin later disabled on an existing row - this path only runs
     * for inserts anyway).
     *
     * <p>The {@code image_generation} row it writes for an image-mode model is
     * inert: no screen reads that category any more (see {@link ModelCategory}).
     * It is still written so the row stays consistent with the categories the
     * API answers for, and so nothing has to be back-filled if one ever gets a
     * reader again.
     */
    private void assignDefaultCategories(Long modelConfigId, String mode) {
        for (String category : ModelCategory.defaultKeys()) {
            if (!ModelCategory.acceptsMode(category, mode)) continue;
            ModelCategorySettingsId id = new ModelCategorySettingsId(modelConfigId, category);
            if (categoryRepo.findById(id).isPresent()) continue;
            ModelCategorySettingsEntity setting = new ModelCategorySettingsEntity();
            setting.setModelConfigId(modelConfigId);
            setting.setCategory(category);
            setting.setEnabled(Boolean.TRUE);
            setting.setRank(null);
            categoryRepo.save(setting);
        }
    }

    /**
     * Upsert one model's per-category settings. Treats the payload's
     * {@code categories} map as the AUTHORITATIVE set for non-custom rows:
     * any local sidecar row whose category is missing from the payload is
     * deleted, so the cloud can retire a category by simply not emitting it
     * in the next bundle (mirrors how {@code deprecateMissing} retires parent
     * rows). Invalid category keys in the payload are skipped without aborting
     * the apply.
     *
     * <p>Idempotent - re-applying the same bundle leaves the sidecar
     * untouched (the trigger detects no diff, no-ops the audit log).
     */
    private void applyRowCategories(Long modelConfigId,
                                    Map<String, Map<String, Object>> categories) {
        // Track which categories the payload declared for this model so we
        // can drop the local sidecar rows that disappeared.
        Set<String> declaredCategories = new HashSet<>();

        for (Map.Entry<String, Map<String, Object>> e : categories.entrySet()) {
            String category = e.getKey();
            if (!ModelCategory.isValidShape(category)) {
                log.warn("Bundle carries invalid category key '{}' for model {} - skipping",
                        category, modelConfigId);
                continue;
            }
            Map<String, Object> v = e.getValue();
            if (v == null) continue;

            Boolean enabled = boolOf(v.get("enabled"));
            Integer rank = intOf(v.get("rank"));

            ModelCategorySettingsEntity setting = categoryRepo
                    .findById(new ModelCategorySettingsId(modelConfigId, category))
                    .orElseGet(() -> {
                        ModelCategorySettingsEntity s = new ModelCategorySettingsEntity();
                        s.setModelConfigId(modelConfigId);
                        s.setCategory(category);
                        return s;
                    });
            setting.setEnabled(enabled == null ? Boolean.TRUE : enabled);
            setting.setRank(rank);
            categoryRepo.save(setting);
            declaredCategories.add(category);
        }

        // Delete sidecar rows whose category vanished from the payload. Without
        // this, a cloud-side category retire (drop browser_agent for some model)
        // would silently leave stale local data behind. The cascade is keyed on
        // model_config_id only (id of the parent row); category-equality is
        // enforced row by row.
        //
        // OPERATIONAL CAVEAT: this also deletes CE-LOCAL sidecar rows that the
        // cloud bundle never knew about. Concretely, if a CE admin manually
        // toggled (model X, image_generation, enabled=false) and the cloud's
        // bundle for X only declares (chat, browser_agent), the local
        // image_generation row gets deleted on the next bundle apply. This
        // mirrors the parent-row authority rule (cloud is authoritative for
        // non-custom rows), but it means CE-local per-category overrides need
        // to be set on `is_custom=true` parent rows to survive a bundle apply.
        // The log line is INFO so operators see the deletion without grepping
        // an audit table.
        for (ModelCategorySettingsEntity local : categoryRepo.findByModelConfigId(modelConfigId)) {
            if (!declaredCategories.contains(local.getCategory())) {
                log.info("Bundle apply deleting sidecar row model_config_id={} category={} " +
                        "(category absent from payload - cloud is authoritative for non-custom rows)",
                        modelConfigId, local.getCategory());
                categoryRepo.delete(local);
            }
        }
    }

    /**
     * Register an afterCommit hook that mirrors each pricing change into
     * auth-service. Package-private so
     * {@code CatalogBundleApplierTest}/{@code CatalogMergeServiceTest} can invoke
     * it directly without a real transaction proxy.
     *
     * <p>If no TX is active (unit-test harness), calls run immediately - the
     * contract is "sync after the local write is durable", and a harness
     * synchronous call satisfies that without a fake TX manager.
     */
    void schedulePricingSyncAfterCommit(List<PricingChange> pricingChanges, String label) {
        if (pricingChanges.isEmpty()) return;

        Runnable flush = () -> {
            int ok = 0, failed = 0;
            for (PricingChange c : pricingChanges) {
                try {
                    authPricingSyncClient.sync(c.provider(), c.modelId(),
                            c.priceInput(), c.priceOutput(), c.providerKind(),
                            c.priceCacheRead(), c.priceCacheWrite());
                    ok++;
                } catch (Exception e) {
                    log.warn("Unexpected error syncing {}/{} to auth-service: {}",
                            c.provider(), c.modelId(), e.getMessage());
                    failed++;
                }
            }
            log.info("[{}] pricing mirror → auth-service: ok={}, failed={}",
                    label, ok, failed);
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { flush.run(); }
            });
        } else {
            flush.run();
        }
    }

    // ── Field application ────────────────────────────────────────────────────

    /** Every field name applyFields can write - used to build the partial-update skip set. */
    /** Package-private so the producer-parity test can assert against it directly. */
    static final Set<String> APPLIED_FIELD_NAMES = Set.of(
            "displayName", "description", "tier", "ranking", "recommended", "enabled",
            "priceInput", "priceOutput", "rateLimitTpm", "rateLimitRpm",
            "rateLimitTpmPerTenant", "rateLimitRpmPerTenant", "contextWindow",
            "maxOutputTokens", "supportsTools", "supportsVision", "canonicalId",
            "deprecationDate", "releaseDate", "supportsPromptCaching", "supportsReasoning",
            "supportsComputerUse", "supportsResponseSchema", "supportsWebSearch", "mode",
            "priceInputBatch", "priceOutputBatch", "priceCacheRead", "priceCacheWrite",
            "priceFloorInput", "priceFloorOutput", "supportedEndpoints", "supportedModalities",
            "supportedOutputModalities", "feedMetadata", "modalities");

    /**
     * Copy fields from {@code m} onto {@code row}, skipping any name present in
     * {@code protectedFields}. The incoming map follows
     * {@link CatalogBundlePayload#toCanonicalMap} conventions; sync feeds
     * (LiteLLM, OpenRouter) normalise into the same shape.
     *
     * <p>{@code partial=true} (seed path) adds PATCH semantics: any field the
     * payload does NOT carry (key absent from {@code m}) is treated as protected,
     * so a minimal curated seed only refreshes what it ships and never nulls the
     * enrichment (tier, contextWindow, capability flags…) a bundle/feed/admin set
     * on the row. {@code partial=false} (bundle/feed) keeps the authoritative
     * behaviour: a field absent from the payload overwrites the row to null.
     */
    private static void applyFields(ModelConfigOverrideEntity row, Map<String, Object> m,
                                    Set<String> protectedFields, boolean partial) {
        if (partial) {
            // Protect every field the payload doesn't carry, on top of the
            // caller's protected set. Union so user_modified_fields still apply.
            Set<String> effective = new java.util.HashSet<>(protectedFields);
            for (String f : APPLIED_FIELD_NAMES) {
                if (!m.containsKey(f)) effective.add(f);
            }
            protectedFields = effective;
        }
        setIfUnprotected(protectedFields, "displayName", () -> row.setDisplayName(str(m.get("displayName"))));
        setIfUnprotected(protectedFields, "description", () -> row.setDescription(str(m.get("description"))));
        setIfUnprotected(protectedFields, "tier",         () -> row.setTier(str(m.get("tier"))));
        setIfUnprotected(protectedFields, "ranking",      () -> {
            Integer incoming = intOf(m.get("ranking"));
            if (incoming != null) row.setRanking(incoming);
        });
        setIfUnprotected(protectedFields, "recommended",  () -> row.setRecommended(boolOf(m.get("recommended"))));
        setIfUnprotected(protectedFields, "enabled",      () -> row.setEnabled(boolOf(m.get("enabled"))));
        setIfUnprotected(protectedFields, "priceInput",   () -> row.setPriceInput(bigDec(m.get("priceInput"))));
        setIfUnprotected(protectedFields, "priceOutput",  () -> row.setPriceOutput(bigDec(m.get("priceOutput"))));
        setIfUnprotected(protectedFields, "rateLimitTpm", () -> row.setRateLimitTpm(intOf(m.get("rateLimitTpm"))));
        setIfUnprotected(protectedFields, "rateLimitRpm", () -> row.setRateLimitRpm(intOf(m.get("rateLimitRpm"))));
        setIfUnprotected(protectedFields, "rateLimitTpmPerTenant",
                () -> row.setRateLimitTpmPerTenant(intOf(m.get("rateLimitTpmPerTenant"))));
        setIfUnprotected(protectedFields, "rateLimitRpmPerTenant",
                () -> row.setRateLimitRpmPerTenant(intOf(m.get("rateLimitRpmPerTenant"))));
        setIfUnprotected(protectedFields, "contextWindow",   () -> row.setContextWindow(intOf(m.get("contextWindow"))));
        setIfUnprotected(protectedFields, "maxOutputTokens", () -> row.setMaxOutputTokens(intOf(m.get("maxOutputTokens"))));
        setIfUnprotected(protectedFields, "supportsTools",   () -> row.setSupportsTools(boolOf(m.get("supportsTools"))));
        setIfUnprotected(protectedFields, "supportsVision",  () -> row.setSupportsVision(boolOf(m.get("supportsVision"))));
        setIfUnprotected(protectedFields, "canonicalId",     () -> row.setCanonicalId(str(m.get("canonicalId"))));

        // V125 enrichment fields - all optional, always updated unless protected.
        setIfUnprotected(protectedFields, "deprecationDate",
                () -> row.setDeprecationDate(localDateOf(m.get("deprecationDate"))));
        setIfUnprotected(protectedFields, "releaseDate",
                () -> row.setReleaseDate(localDateOf(m.get("releaseDate"))));
        setIfUnprotected(protectedFields, "supportsPromptCaching",
                () -> row.setSupportsPromptCaching(boolOf(m.get("supportsPromptCaching"))));
        setIfUnprotected(protectedFields, "supportsReasoning",
                () -> row.setSupportsReasoning(boolOf(m.get("supportsReasoning"))));
        setIfUnprotected(protectedFields, "supportsComputerUse",
                () -> row.setSupportsComputerUse(boolOf(m.get("supportsComputerUse"))));
        setIfUnprotected(protectedFields, "supportsResponseSchema",
                () -> row.setSupportsResponseSchema(boolOf(m.get("supportsResponseSchema"))));
        setIfUnprotected(protectedFields, "supportsWebSearch",
                () -> row.setSupportsWebSearch(boolOf(m.get("supportsWebSearch"))));
        setIfUnprotected(protectedFields, "mode",
                () -> row.setMode(str(m.get("mode"))));
        setIfUnprotected(protectedFields, "priceInputBatch",
                () -> row.setPriceInputBatch(bigDec(m.get("priceInputBatch"))));
        setIfUnprotected(protectedFields, "priceOutputBatch",
                () -> row.setPriceOutputBatch(bigDec(m.get("priceOutputBatch"))));
        setIfUnprotected(protectedFields, "priceCacheRead",
                () -> row.setPriceCacheRead(bigDec(m.get("priceCacheRead"))));
        setIfUnprotected(protectedFields, "priceCacheWrite",
                () -> row.setPriceCacheWrite(bigDec(m.get("priceCacheWrite"))));
        setIfUnprotected(protectedFields, "priceFloorInput",
                () -> row.setPriceFloorInput(bigDec(m.get("priceFloorInput"))));
        setIfUnprotected(protectedFields, "priceFloorOutput",
                () -> row.setPriceFloorOutput(bigDec(m.get("priceFloorOutput"))));
        setIfUnprotected(protectedFields, "supportedEndpoints",
                () -> row.setSupportedEndpoints(stringArrayOf(m.get("supportedEndpoints"))));
        setIfUnprotected(protectedFields, "supportedModalities",
                () -> row.setSupportedModalities(stringArrayOf(m.get("supportedModalities"))));
        setIfUnprotected(protectedFields, "supportedOutputModalities",
                () -> row.setSupportedOutputModalities(stringArrayOf(m.get("supportedOutputModalities"))));
        setIfUnprotected(protectedFields, "feedMetadata",
                () -> row.setFeedMetadata(mapOf(m.get("feedMetadata"))));

        // Modalities: guard against malformed payloads that could blow the
        // whole apply with a ClassCastException mid-loop.
        Object rawModalities = m.get("modalities");
        Map<String, Object> modalities;
        if (rawModalities == null) {
            modalities = null;
        } else if (rawModalities instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            modalities = cast;
        } else {
            log.warn("Model {}/{} has malformed modalities (type={}), skipping modalities field",
                    row.getProvider(), row.getModelId(), rawModalities.getClass().getSimpleName());
            return;
        }
        setIfUnprotected(protectedFields, "modalities", () -> row.setModalities(modalities));
    }

    private static void setIfUnprotected(Set<String> protectedFields, String field, Runnable action) {
        if (!protectedFields.contains(field)) action.run();
    }

    /**
     * Fill in the 4 rate-limit columns from {@link CatalogDefaults} when the
     * feed didn't supply them. Every model row must have non-null caps so
     * the rate limiter can enforce a ceiling - LiteLLM only populates
     * rpm/tpm for ~1.8% of entries, so without defaults the majority of
     * rows land with null and bypass the limiter.
     *
     * <p><b>Except when the model has a curated limit in
     * {@code ai.agent.rate-limits}.</b> Those two layers were fighting:
     * {@code CachedModelRateLimitProvider} resolves DB column first and only
     * falls back to the YAML seed when the column is NULL, so stamping the
     * generic 60000/500 here silently overrode every researched per-model
     * value. Measured on production 2026-08-24, that had killed 44 of the 50
     * curated entries - openai/gpt-5.4-mini enforcing 60k TPM against a
     * curated 10M, deepseek/deepseek-chat 60k against 2M. Leaving the columns
     * NULL for those models is what hands control back to the curated table;
     * the ceiling is not lost, it is the stricter, researched one.
     *
     * <p><b>And the TPM fallback is a floor, not a flat number.</b> A token
     * ceiling is only a ceiling relative to what one request costs, and that is
     * bounded by the model's context window - so a single value cannot serve a
     * 32k model and a 1M one. When the ceiling drops below the cost of one
     * request the limiter inverts: no request ever fits a fresh window, so every
     * call waits out the full 60s window before proceeding. Measured on
     * production 2026-09-11, deepseek-v4-flash (1M context, ~50 000 estimated
     * tokens per tool-heavy agent turn) against the then-flat 60 000 ceiling:
     * 16 of 17 calls delayed, ~51s of limiter wait each, for provider responses
     * that took 1.5-10s. The fallback therefore takes the larger of the
     * configured flat value and {@code contextWindow x multiplier}.
     * {@code applyFields} has already written contextWindow by the time we run,
     * on both the insert and the update path.
     */
    private void applyRateLimitDefaults(ModelConfigOverrideEntity row) {
        if (catalogDefaults == null) return; // defensive for non-Spring test harnesses
        if (hasCuratedRateLimit(row.getProvider(), row.getModelId())) return;
        if (row.getRateLimitTpm()          == null) row.setRateLimitTpm(contextAwareTpmDefault(row));
        if (row.getRateLimitRpm()          == null) row.setRateLimitRpm(catalogDefaults.getRateLimitRpm());
        // Read the global column back rather than reusing the value computed
        // above: when the feed supplied its own global TPM that value was NOT
        // written, and sizing the tenant share off the discarded one lands a
        // per-tenant cap ABOVE the platform cap (measured on a 1M-context row
        // with a feed-supplied 800000: tenant 1048576 against global 800000).
        // Under PER_TENANT only the tenant dimension is checked, so that row
        // would out-run the published limit it was meant to respect.
        if (row.getRateLimitTpmPerTenant() == null) row.setRateLimitTpmPerTenant(tenantTpmDefault(row.getRateLimitTpm()));
        if (row.getRateLimitRpmPerTenant() == null) row.setRateLimitRpmPerTenant(catalogDefaults.getRateLimitRpmPerTenant());
    }

    /**
     * Largest TPM this method will derive from a context window. A context
     * window is feed-supplied and therefore untrusted: without a ceiling a
     * malformed value would overflow the {@code integer} column. No real
     * provider tier comes close to this, so clamping here can only ever bite a
     * bad row.
     */
    private static final int MAX_DERIVED_TPM = 100_000_000;

    /** A tenant's share of the platform window: the tenant floor is 1/this of it. */
    private static final int TENANT_TPM_DIVISOR = 4;

    /**
     * The flat default, raised to {@code contextWindow x multiplier} when that
     * is larger. The result is therefore {@code >= } the configured flat value,
     * never below it.
     *
     * <p>A configured value of {@code null} or {@code <= 0} is returned
     * untouched. Those are not "small ceilings" to be raised, they are the
     * codebase's own off-switches, and raising one would quietly re-enable
     * traffic an operator had capped: {@code RateLimitConfig.hasGlobalTokenLimit}
     * reads {@code -1} as "no limit" (shipped that way by
     * {@code V216__deepseek_disable_per_tenant_tpm.sql}) and
     * {@code ProviderRateLimiter} documents {@code 0} as the block-everything
     * kill-switch. An absent or non-positive multiplier, or a row with no usable
     * context window, likewise leaves the flat value alone.
     */
    private Integer contextAwareTpmDefault(ModelConfigOverrideEntity row) {
        Integer flat = catalogDefaults.getRateLimitTpm();
        if (flat == null || flat <= 0) return flat;
        Integer ctx = row.getContextWindow();
        Integer multiplier = catalogDefaults.getRateLimitTpmContextMultiplier();
        if (ctx == null || ctx <= 0 || multiplier == null || multiplier <= 0) return flat;
        long derived = Math.min((long) ctx * multiplier, MAX_DERIVED_TPM);
        return (int) Math.max(flat.longValue(), derived);
    }

    /**
     * The configured per-tenant default, held between two bounds against the
     * row's effective global TPM.
     *
     * <p>The lower bound is a {@link #TENANT_TPM_DIVISOR}th of the global
     * value: a per-tenant cap under the cost of one request is not a throttle
     * but a hard failure, rejected outright by
     * {@code ProviderRateLimiter.acquireWithWait} as a non-retryable
     * {@code request_exceeds_tenant_capacity} under PER_TENANT and HYBRID.
     *
     * <p>The upper bound is the global value itself, and it is not decoration:
     * the configured flat floor is a single number while the global value is
     * per-model, so any row whose global TPM lands below that floor would
     * otherwise hand one tenant more than the whole platform. Real rows sit
     * there (the shipped seed alone carries 12 models at 250000 and 9 at
     * 100000), and under PER_TENANT only the tenant dimension is checked, so
     * such a row would let a single tenant out-run the published platform limit
     * several times over.
     *
     * <p>Off-switch contract as in {@link #contextAwareTpmDefault}: a
     * configured {@code null} or {@code <= 0} is returned untouched. A global
     * TPM that is itself absent or switched off gives nothing to bound against,
     * so the flat value stands.
     *
     * @param effectiveGlobalTpm the value actually on the row, feed-supplied or
     *                           defaulted, never the discarded default
     */
    private Integer tenantTpmDefault(Integer effectiveGlobalTpm) {
        Integer flat = catalogDefaults.getRateLimitTpmPerTenant();
        if (flat == null || flat <= 0) return flat;
        if (effectiveGlobalTpm == null || effectiveGlobalTpm <= 0) return flat;
        int atLeastAShare = Math.max(flat, effectiveGlobalTpm / TENANT_TPM_DIVISOR);
        return Math.min(atLeastAShare, effectiveGlobalTpm);
    }

    /**
     * Does {@code ai.agent.rate-limits} carry an entry for this model? Key
     * lookup mirrors {@link com.apimarketplace.agent.service.CachedModelRateLimitProvider}
     * exactly - scoped {@code provider:modelId} first, then the bare
     * {@code modelId} - so the two cannot disagree about which models are
     * curated. A disagreement would be the worst outcome: the merge would skip
     * the fallback while the resolver found no curated value, leaving the model
     * with no ceiling at all.
     *
     * <p>Null-safe on {@code modelPricingConfig} so the non-Spring test
     * harnesses that pass {@code null} keep the pre-existing behaviour.
     */
    private boolean hasCuratedRateLimit(String provider, String modelId) {
        if (modelPricingConfig == null || modelId == null) return false;
        Map<String, ModelPricingConfig.ModelRateLimitInfo> curated = modelPricingConfig.getRateLimits();
        if (curated == null || curated.isEmpty()) return false;
        return curated.containsKey(provider + ":" + modelId) || curated.containsKey(modelId);
    }

    private static String keyOf(String provider, String modelId) {
        return provider + '\0' + modelId;
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static Integer intOf(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean boolOf(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static BigDecimal bigDec(Object v) {
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate localDateOf(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        try {
            return LocalDate.parse(v.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String[] stringArrayOf(Object v) {
        if (v == null) return null;
        if (v instanceof String[] arr) return arr;
        if (v instanceof Collection<?> col) {
            return col.stream().filter(Objects::nonNull).map(Object::toString).toArray(String[]::new);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object v) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return null;
    }
}
