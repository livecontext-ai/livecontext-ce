package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSource;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.domain.PriceUnit;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.domain.WorkflowRunPricingPin;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import com.apimarketplace.auth.credential.repository.WorkflowRunPricingPinRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Writer for immutable pricing-version snapshots attached to a platform credential.
 *
 * <p>The key invariant is that {@code version} is monotonically increasing with
 * no gaps per credential. Without coordination, two admins publishing at the
 * same time could each read {@code max=3} and both insert {@code version=4},
 * which violates the {@code (platform_credential_id, version)} unique constraint
 * and leaks into billing: a stale in-flight run might bind to the losing v4,
 * while a new run binds to the winning v4 with a different rate. We serialize
 * on a PostgreSQL transaction-level advisory lock keyed by credential id so the
 * read-max/insert-next sequence is atomic.
 *
 * <p>Never update an existing row; publish always produces a new version.
 */
@Service
public class PlatformCredentialPricingService {

    private static final Logger log = LoggerFactory.getLogger(PlatformCredentialPricingService.class);

    // Stable namespace for the advisory-lock key so it doesn't collide with
    // other advisory locks elsewhere in auth-service.
    private static final long ADVISORY_LOCK_NAMESPACE = 0x70634D6172L; // "pcMar"

    private final JdbcTemplate jdbc;
    private final PlatformCredentialPricingVersionRepository versionRepo;
    private final PricingVersionEntryRepository entryRepo;
    private final PlatformCredentialRepository credentialRepo;
    private final WorkflowRunPricingPinRepository pinRepo;
    private final MarkupPolicy policy;

    public PlatformCredentialPricingService(JdbcTemplate jdbc,
                                             PlatformCredentialPricingVersionRepository versionRepo,
                                             PricingVersionEntryRepository entryRepo,
                                             PlatformCredentialRepository credentialRepo,
                                             WorkflowRunPricingPinRepository pinRepo,
                                             MarkupPolicy policy) {
        this.jdbc = jdbc;
        this.versionRepo = versionRepo;
        this.entryRepo = entryRepo;
        this.credentialRepo = credentialRepo;
        this.pinRepo = pinRepo;
        this.policy = policy;
    }

    /**
     * Publish a new pricing version whose rows are exactly {@code prices}.
     *
     * <p>Kept as the shape every existing caller speaks. The seeded v1 bootstrap
     * uses it because there is nothing to carry forward; an admin republish uses
     * the 5-arg overload so it cannot silently drop rows it did not mention.
     */
    @Transactional
    public PlatformCredentialPricingVersion publishNextVersion(Long credentialId,
                                                                BigDecimal defaultMarkup,
                                                                List<PriceSpec> prices,
                                                                String createdBy) {
        return publishNextVersion(credentialId, defaultMarkup, prices, createdBy, false);
    }

    /**
     * Publish a new pricing version for the given credential.
     *
     * <p>Runs under an advisory lock so the read-max / insert-next sequence is
     * atomic against concurrent publishes. The lock is released when the
     * transaction commits.
     *
     * <p><b>V428 - why a list of {@link PriceSpec} and not a map.</b> A price is
     * no longer one number per endpoint: it names a model, a unit, a fixed part,
     * a variable part and two clamps. The old {@code Map<apiToolId, credits>}
     * could carry exactly one of those six, which is why the starting prices
     * declared in the catalog seed had nowhere to land. Every call site now
     * speaks the same shape; a flat legacy price is {@link PriceSpec#flat} and
     * bills the same amount it always did.
     *
     * <p><b>What "republish" means here.</b> A version is immutable, so editing a
     * price is really publishing the NEXT version. That makes the row set of the
     * new version a decision, not a detail:
     * <ul>
     *   <li>{@code carryForwardUnlistedPrices=true} (how the admin surface and any
     *       older client publish): the new version STARTS as a copy of the latest
     *       version's rows, and {@code prices} upserts onto it keyed by
     *       (endpoint, model). A caller that can only express one flat amount per
     *       endpoint therefore changes that endpoint's flat price and leaves every
     *       per-model, per-unit row it cannot express intact. Without this, one
     *       republish from a pre-V428 client collapses a per-second video price
     *       into a flat one, and nothing in the response says so.</li>
     *   <li>{@code carryForwardUnlistedPrices=false}: {@code prices} IS the row
     *       set. This is how a surface that renders and edits every row publishes
     *       (what you see is what gets published, deletions included), and how the
     *       catalog seed bootstraps v1.</li>
     * </ul>
     * The version-wide default is NOT carried forward either way: it is a single
     * scalar the caller always states explicitly, so honouring the value passed is
     * the only reading that cannot surprise.
     *
     * @throws IllegalArgumentException if the credential does not exist, the
     *                                  markup config violates policy, or two
     *                                  prices claim the same (endpoint, model).
     */
    @Transactional
    public PlatformCredentialPricingVersion publishNextVersion(Long credentialId,
                                                                BigDecimal defaultMarkup,
                                                                List<PriceSpec> prices,
                                                                String createdBy,
                                                                boolean carryForwardUnlistedPrices) {
        PlatformCredential credential = credentialRepo.findById(credentialId)
                .orElseThrow(() -> new IllegalArgumentException("credential not found: " + credentialId));

        // Policy enforcement at the write boundary - DB CHECK is the backstop.
        policy.validateMarkupConfig(credential.authType(), defaultMarkup,
                credential.maxCallsPerRun() != null ? credential.maxCallsPerRun() : 0);
        validatePrices(prices);

        acquireAdvisoryLock(credentialId);

        // Read the live rows INSIDE the lock: a concurrent publish would
        // otherwise let us carry forward from a version that is no longer the
        // latest (resurrecting prices the other admin had just removed) or
        // validate against a unit that has already been replaced.
        java.util.LinkedHashMap<String, PricingVersionEntry> live = latestEntriesByKey(credentialId);

        // A unit may be re-expressed, never re-dimensioned: what measures the
        // call was fixed when the row was first published, and the caller keeps
        // reporting that same measurement whatever the admin types here.
        validateUnitsAgainstLiveRows(prices, live);

        List<PriceSpec> effective = carryForwardUnlistedPrices
                ? mergeOntoLatest(live, prices)
                : (prices == null ? List.of() : prices);

        // A version with no default AND no per-tool price would bill zero for
        // every tool, which is indistinguishable from "not priced". Refuse it so
        // the version history stays meaningful and the inspector toggle stays honest.
        if (defaultMarkup == null && effective.isEmpty()) {
            throw new IllegalArgumentException(
                    "pricing version needs either a default markup or at least one per-tool override");
        }

        Integer max = versionRepo.findMaxVersion(credentialId);
        int next = (max == null) ? 1 : max + 1;

        PlatformCredentialPricingVersion version = new PlatformCredentialPricingVersion();
        version.setPlatformCredentialId(credentialId);
        version.setVersion(next);
        version.setDefaultMarkupCredits(defaultMarkup);
        version.setCreatedBy(createdBy);
        PlatformCredentialPricingVersion saved = versionRepo.save(version);

        if (!effective.isEmpty()) {
            List<PricingVersionEntry> entries = new ArrayList<>(effective.size());
            for (PriceSpec price : effective) {
                entries.add(price.toEntry(saved.getId()));
            }
            entryRepo.saveAll(entries);
        }

        log.info("Published pricing version v{} for credential {} (default={}, requested={}, published={}, carryForward={})",
                next, credentialId, defaultMarkup,
                prices == null ? 0 : prices.size(), effective.size(), carryForwardUnlistedPrices);
        return saved;
    }

    /**
     * The rows of the latest published version, keyed by (endpoint, model).
     * Empty when the credential has never been priced.
     *
     * <p>Read once per publish and used for both jobs that need it: carrying
     * forward the rows a request does not mention, and checking that a row's
     * unit is not being moved to something nothing measures.
     */
    private java.util.LinkedHashMap<String, PricingVersionEntry> latestEntriesByKey(Long credentialId) {
        java.util.LinkedHashMap<String, PricingVersionEntry> byKey = new java.util.LinkedHashMap<>();
        versionRepo.findLatest(credentialId).ifPresent(latest -> {
            for (PricingVersionEntry e : entryRepo.findByPricingVersionId(latest.getId())) {
                byKey.put(entryKey(e.getApiToolId(), e.getModelId()), e);
            }
        });
        return byKey;
    }

    /**
     * Reject a price whose unit does not measure the same thing as the unit the
     * live row carries. See {@link MarkupPolicy#validateUnitChange}: the amount
     * charged is a measurement taken by the caller multiplied by this rate, and
     * only the caller knows what it measured, so the dimension is fixed by the
     * row that already exists rather than by whoever republishes it.
     *
     * <p>A row the live version does not have is a new price with nothing to
     * contradict, so it is published as asked.
     */
    private void validateUnitsAgainstLiveRows(List<PriceSpec> requested,
                                               java.util.Map<String, PricingVersionEntry> live) {
        if (requested == null || requested.isEmpty() || live.isEmpty()) {
            return;
        }
        for (PriceSpec p : requested) {
            PricingVersionEntry current = live.get(priceKey(p));
            if (current == null) {
                continue;
            }
            String where = "toolId=" + p.apiToolId()
                    + (p.modelId() == null ? "" : ", modelId=" + p.modelId());
            policy.validateUnitChange(current.unit(), PriceUnit.fromWire(p.priceUnit()), where);
        }
    }

    /**
     * The latest version's rows with {@code requested} upserted on top, keyed by
     * (endpoint, model). Declaration order is "carried rows first, then rows the
     * request introduced", which keeps a republish diff readable in the history.
     */
    private List<PriceSpec> mergeOntoLatest(java.util.Map<String, PricingVersionEntry> live,
                                             List<PriceSpec> requested) {
        java.util.LinkedHashMap<String, PriceSpec> byKey = new java.util.LinkedHashMap<>();
        for (PricingVersionEntry e : live.values()) {
            byKey.put(entryKey(e.getApiToolId(), e.getModelId()), carryForward(e));
        }
        if (requested != null) {
            for (PriceSpec p : requested) {
                byKey.put(priceKey(p), p);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * A published row re-expressed as the price to publish again, unchanged -
     * including who decided it.
     *
     * <p>Carrying the source is not bookkeeping. A version is immutable, so
     * every republish rewrites every row, and a carried row that lost its
     * provenance would come back as {@link com.apimarketplace.auth.credential.domain.PriceSource#ADMIN}
     * by default. One unrelated admin publish would then re-label the whole
     * price list as locally decided and the install would stop receiving cloud
     * price updates for models nobody ever touched.
     */
    private static PriceSpec carryForward(PricingVersionEntry e) {
        return new PriceSpec(e.getApiToolId(), e.getModelId(), e.getPriceUnit(),
                e.getMarkupCredits(), e.getUnitCredits(), e.getMinCredits(), e.getMaxCredits(),
                e.origin());
    }

    /** {@link #priceKey} for a persisted row. */
    private static String entryKey(UUID apiToolId, String modelId) {
        return apiToolId + "|" + (modelId == null ? "" : modelId);
    }

    /** Identity of a price row: the (endpoint, model) pair {@code uk_pve_version_tool_model} keys on. */
    private static String priceKey(PriceSpec price) {
        return price.apiToolId() + "|" + (price.modelId() == null ? "" : price.modelId());
    }

    /**
     * Reject a price list that the DB would refuse anyway, with a message that
     * names the offending endpoint instead of an opaque constraint violation.
     *
     * <p>The duplicate check mirrors {@code uk_pve_version_tool_model}: a
     * collision on (endpoint, model) is always an authoring mistake, and
     * silently keeping one of the two would publish a price nobody chose.
     */
    private void validatePrices(List<PriceSpec> prices) {
        if (prices == null || prices.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (PriceSpec p : prices) {
            if (p == null || p.apiToolId() == null) {
                throw new IllegalArgumentException("per-tool price requires an apiToolId");
            }
            String where = "toolId=" + p.apiToolId()
                    + (p.modelId() == null ? "" : ", modelId=" + p.modelId());
            if (p.baseCredits().signum() < 0 || p.unitCredits().signum() < 0) {
                throw new IllegalArgumentException("per-tool markup must be >= 0 (" + where + ")");
            }
            if (p.minCredits() != null && p.minCredits().signum() < 0) {
                throw new IllegalArgumentException("minCredits must be >= 0 (" + where + ")");
            }
            if (p.maxCredits() != null && p.maxCredits().signum() < 0) {
                throw new IllegalArgumentException("maxCredits must be >= 0 (" + where + ")");
            }
            if (p.minCredits() != null && p.maxCredits() != null
                    && p.minCredits().compareTo(p.maxCredits()) > 0) {
                throw new IllegalArgumentException("minCredits must be <= maxCredits (" + where + ")");
            }
            if (!seen.add(priceKey(p))) {
                throw new IllegalArgumentException("duplicate price for " + where);
            }
        }
    }

    public Optional<PlatformCredentialPricingVersion> findLatest(Long credentialId) {
        return versionRepo.findLatest(credentialId);
    }

    /**
     * V148+ idempotent bootstrap: if the credential has NO pricing version yet,
     * publish v1 with the supplied {@code defaultMarkup} + {@code prices}.
     * If a version already exists (any version), no-op and return the latest.
     *
     * <p>Called by catalog-service's {@code ApiMigrationImporter} after the
     * api_tools seed is complete, so the prices reference UUIDs that exist in
     * {@code catalog.api_tools}. Migration-service can't do this because Flyway
     * runs before catalog seed.
     *
     * <p><b>Never overwrites a published price, by design.</b> The catalog seed
     * only ever declares a STARTING price. Once ANY version exists the platform
     * owner is presumed to own the pricing (either they published from the admin
     * screens, or they accepted the seeded v1), so a re-import of the same
     * catalog files must leave it alone. That is what makes running the importer
     * again after tuning prices in the UI safe: this method reads the latest
     * version first and returns it untouched.
     *
     * <p>Cutover safety net: without this, replacing the V141 hardcoded image-gen
     * pricing with admin-published versions would leave a window where new
     * users have no pricing → free image-gen until admin manually publishes.
     * The bootstrap mirrors V141 numbers (gpt-image-1.5-low/med/high=9/34/133,
     * mini=5/11/36, gemini-2.5-flash-image=39, gemini-3-pro-image=134) so
     * day-1 economics match pre-cutover.
     */
    @Transactional
    public PlatformCredentialPricingVersion bootstrapV1IfAbsent(Long credentialId,
                                                                 BigDecimal defaultMarkup,
                                                                 List<PriceSpec> prices,
                                                                 String createdBy) {
        Optional<PlatformCredentialPricingVersion> existing = versionRepo.findLatest(credentialId);
        if (existing.isPresent()) {
            log.debug("bootstrapV1IfAbsent: credential {} already has version {} - skipping",
                    credentialId, existing.get().getVersion());
            return existing.get();
        }
        log.info("bootstrapV1IfAbsent: publishing v1 for credential {} (default={}, prices={})",
                credentialId, defaultMarkup, prices == null ? 0 : prices.size());
        return publishNextVersion(credentialId, defaultMarkup, prices, createdBy);
    }

    public List<PlatformCredentialPricingVersion> findAllVersions(Long credentialId) {
        return versionRepo.findByPlatformCredentialIdOrderByVersionDesc(credentialId);
    }

    // ── V430: distributing published prices through the signed catalog bundle ──

    /**
     * Rows of the credential's LATEST published version that price one of
     * {@code apiToolIds}, in a stable order.
     *
     * <p>This is the publisher half of carrying generation prices in the signed
     * API-catalog bundle. It reads the LATEST version rather than a pinned one
     * for the same reason {@link #quoteLatest} does: what is being distributed
     * is what a new call would cost today, not what some in-flight run was
     * bound to.
     *
     * <p>An empty id set returns nothing rather than everything. The caller is
     * naming the generation endpoints it found; "I found none" must not turn
     * into "publish the whole price list", which would ship the platform
     * owner's rates for all 700 ordinary endpoints to every install.
     */
    @Transactional(readOnly = true)
    public List<PricingVersionEntry> findLatestPublishedPrices(Long credentialId,
                                                                Set<UUID> apiToolIds) {
        if (credentialId == null || apiToolIds == null || apiToolIds.isEmpty()) {
            return List.of();
        }
        Optional<PlatformCredentialPricingVersion> latest = versionRepo.findLatest(credentialId);
        if (latest.isEmpty()) {
            return List.of();
        }
        List<PricingVersionEntry> rows = new ArrayList<>();
        for (PricingVersionEntry e : entryRepo.findByPricingVersionId(latest.get().getId())) {
            if (apiToolIds.contains(e.getApiToolId())) {
                rows.add(e);
            }
        }
        rows.sort(java.util.Comparator
                .comparing((PricingVersionEntry e) -> String.valueOf(e.getApiToolId()))
                .thenComparing(e -> e.getModelId() == null ? "" : e.getModelId()));
        return rows;
    }

    /**
     * Apply the prices carried by a verified API-catalog bundle to one platform
     * credential.
     *
     * <p><b>Why this is not {@link #bootstrapV1IfAbsent}.</b> The bootstrap
     * answers "has this credential ever been priced", which is the right
     * question for a seed that runs once. A bundle arrives every fifteen
     * minutes for the life of the install, so that question freezes the price
     * list at whatever the first tick published: a model added to the cloud
     * catalog next month would never reach an install that already has a
     * version. And it is not {@link #publishNextVersion} either, which would do
     * the opposite and silently revert the administrator on every tick.
     *
     * <p><b>The rule, per ROW.</b> A row the bundle wrote before
     * ({@link PriceSource#BUNDLE}) is replaced; a row a human here published
     * ({@link PriceSource#ADMIN}, which is also every row predating V430) is
     * preserved and the bundle's version of it is dropped. Rows the bundle does
     * not mention are carried forward untouched: a bundle NEVER removes a price,
     * because a partial or newly-empty price list would otherwise unprice
     * endpoints that are live and being sold.
     *
     * <p><b>Publishes nothing when nothing changed.</b> A version is immutable,
     * so an unconditional publish would mint a version per tick and bury the
     * decisions the history exists to record. Re-applying the same bundle is
     * therefore free, which is what lets the caller re-offer prices on every
     * tick - the case that matters is the operator who pastes a provider key a
     * week after the bundle that priced it landed.
     *
     * @param createdBy stamped on the published version so the history says
     *                  which bundle wrote it
     * @throws IllegalArgumentException if the credential does not exist, or a
     *                                  bundle row would move a live price to a
     *                                  unit nothing measures for it
     *                                  ({@link MarkupPolicy#validateUnitChange})
     */
    @Transactional
    public BundlePriceApplyResult applyBundlePrices(Long credentialId,
                                                     List<PriceSpec> bundlePrices,
                                                     String createdBy) {
        if (credentialId == null || bundlePrices == null || bundlePrices.isEmpty()) {
            return BundlePriceApplyResult.unchanged(0, 0);
        }
        // Locked BEFORE the live rows are read, and held to commit, so the
        // decision "this row is the administrator's" cannot be taken against a
        // version that a concurrent publish has already replaced.
        // publishNextVersion re-acquires the same key below; a PostgreSQL
        // advisory lock is re-entrant within one transaction, and this method
        // calls it on itself so both run in the same one.
        acquireAdvisoryLock(credentialId);

        java.util.LinkedHashMap<String, PricingVersionEntry> live = latestEntriesByKey(credentialId);
        java.util.LinkedHashMap<String, PriceSpec> effective = new java.util.LinkedHashMap<>();
        for (PricingVersionEntry e : live.values()) {
            effective.put(entryKey(e.getApiToolId(), e.getModelId()), carryForward(e));
        }

        int applied = 0;
        int preserved = 0;
        boolean changed = false;
        for (PriceSpec incoming : bundlePrices) {
            if (incoming == null || incoming.apiToolId() == null) {
                continue;
            }
            PriceSpec row = asBundleRow(incoming);
            String key = priceKey(row);
            PricingVersionEntry current = live.get(key);
            if (current != null && current.origin() == PriceSource.ADMIN) {
                preserved++;
                continue;
            }
            applied++;
            if (!row.matches(current)) {
                changed = true;
            }
            effective.put(key, row);
        }

        if (!changed) {
            log.debug("Bundle prices for credential {}: nothing to publish ({} already current, {} locally tuned)",
                    credentialId, applied, preserved);
            return BundlePriceApplyResult.unchanged(applied, preserved);
        }

        // The version-wide default is the install's own decision about its
        // ordinary calls and is not something a catalog bundle prices, so it is
        // carried forward verbatim (null on a credential that has never been
        // priced, which publishNextVersion accepts because the row set is not
        // empty).
        BigDecimal carriedDefault = versionRepo.findLatest(credentialId)
                .map(PlatformCredentialPricingVersion::getDefaultMarkupCredits)
                .orElse(null);
        PlatformCredentialPricingVersion published = publishNextVersion(
                credentialId, carriedDefault, new ArrayList<>(effective.values()), createdBy, false);
        log.info("Bundle prices published for credential {}: v{} ({} applied, {} locally tuned and preserved)",
                credentialId, published.getVersion(), applied, preserved);
        return BundlePriceApplyResult.published(published, applied, preserved);
    }

    /**
     * The same price, stated as bundle-owned.
     *
     * <p>Forced here rather than trusted from the caller: the provenance decides
     * whether the NEXT bundle may replace the row, so a row that arrived through
     * a bundle and got stored as {@code admin} would pin that price on the
     * install forever with nobody having chosen it.
     */
    private static PriceSpec asBundleRow(PriceSpec incoming) {
        return incoming.source() == PriceSource.BUNDLE
                ? incoming
                : new PriceSpec(incoming.apiToolId(), incoming.modelId(), incoming.priceUnit(),
                        incoming.baseCredits(), incoming.unitCredits(), incoming.minCredits(),
                        incoming.maxCredits(), PriceSource.BUNDLE);
    }

    /**
     * Outcome of {@link #applyBundlePrices} for one credential.
     *
     * @param published whether a new pricing version was written
     * @param version   the published version number, null when nothing changed
     * @param applied   bundle rows this install accepted
     * @param preserved bundle rows dropped because the local row is the
     *                  administrator's
     */
    public record BundlePriceApplyResult(boolean published, Long pricingVersionId, Integer version,
                                          int applied, int preserved) {

        static BundlePriceApplyResult unchanged(int applied, int preserved) {
            return new BundlePriceApplyResult(false, null, null, applied, preserved);
        }

        static BundlePriceApplyResult published(PlatformCredentialPricingVersion v,
                                                 int applied, int preserved) {
            return new BundlePriceApplyResult(true, v.getId(), v.getVersion(), applied, preserved);
        }
    }

    /**
     * Every published row of a pricing version, in a stable order (endpoint,
     * then the endpoint-wide row before its per-model rows).
     *
     * <p>This is the read the admin surface needs: {@link #findOverrides} can only
     * describe a row that is one flat number, which is a shrinking minority of
     * what a version now holds.
     */
    public List<PricingVersionEntry> findPrices(Long pricingVersionId) {
        List<PricingVersionEntry> entries =
                new ArrayList<>(entryRepo.findByPricingVersionId(pricingVersionId));
        entries.sort(java.util.Comparator
                .comparing((PricingVersionEntry e) -> String.valueOf(e.getApiToolId()))
                // NULL model = the endpoint-wide row, listed first so the row a
                // per-model price overrides is always the one above it.
                .thenComparing(e -> e.getModelId() == null ? "" : e.getModelId()));
        return entries;
    }

    /**
     * Flat per-endpoint prices of a version, keyed by apiToolId. Empty map when
     * the version has none (i.e. every tool uses the default).
     *
     * <p><b>Only genuinely flat rows appear here, and that is the point.</b> The
     * map can carry exactly one number per endpoint, so since V428 there are two
     * kinds of row it cannot describe without lying:
     * <ul>
     *   <li>a per-model row - two models on one endpoint would collide on the key
     *       and the last one read would silently win, reporting a price nobody
     *       published;</li>
     *   <li>a unit-priced row - its {@code markup_credits} is only the FIXED part,
     *       so a "60 credits per second" price with no fixed part would read as 0,
     *       i.e. as free.</li>
     * </ul>
     * Both are omitted here and reported in full by {@link #findPrices}. A caller
     * that only knows this map therefore sees fewer prices than exist, never a
     * wrong one.
     */
    public Map<UUID, BigDecimal> findOverrides(Long pricingVersionId) {
        List<PricingVersionEntry> entries = entryRepo.findByPricingVersionId(pricingVersionId);
        Map<UUID, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (PricingVersionEntry e : entries) {
            if (e.getModelId() != null) continue;
            if (e.unit() != PriceUnit.CALL) continue;
            if (e.getUnitCredits() != null && e.getUnitCredits().signum() != 0) continue;
            out.put(e.getApiToolId(), e.getMarkupCredits());
        }
        return out;
    }

    /**
     * Non-admin read used by the workflow inspector: for the latest published
     * pricing version of {@code credentialId}, resolve the per-call markup
     * that would apply to {@code apiToolId}.
     *
     * <p>Returns empty when the credential has no published pricing version at
     * all - the inspector reads this as "no platform pricing available" and
     * hides the user/platform source toggle.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> resolveLatestMarkupForTool(Long credentialId, UUID apiToolId) {
        return quoteLatest(credentialId, apiToolId, null, null).map(Quote::credits);
    }

    /**
     * V428 quote: what the latest published price WOULD charge, without
     * reserving anything or pinning a version.
     *
     * <p>This is what lets a price be shown before a call rather than
     * discovered on the invoice. The builder inspector renders it next to the
     * node, and the generation tool returns it so an agent can weigh cost
     * before spending a customer's credits.
     *
     * <p>Deliberately reads the LATEST version, not a pinned one: a quote
     * describes what a new call would cost, whereas a call already in flight
     * keeps the version it was pinned to.
     *
     * @param modelId  generation model, or null for the endpoint price
     * @param quantity PLATFORM measurement of the call (seconds, assets,
     *                 characters), or null when the caller cannot measure it
     *                 yet. Null quotes what the billing path would charge for
     *                 an unmeasured call, which is ONE published unit, not a
     *                 bare rate.
     */
    @Transactional(readOnly = true)
    public Optional<Quote> quoteLatest(Long credentialId, UUID apiToolId,
                                        String modelId, BigDecimal quantity) {
        return quoteLatest(credentialId, apiToolId, modelId, quantity, null);
    }

    /**
     * Same quote, for a call whose CHOICES move it off the published rate.
     *
     * <p>The surface that shows a price before the spend has to reach the same
     * amount the billing path will charge, and since a 1080p render or an
     * attached reference image changes that amount, the quote has to be told
     * about them. The factor is computed by the caller that holds the
     * parameters, against the same declared modifiers the billing path reads,
     * so the two cannot drift.
     *
     * <p>A quote is a display, not a charge: nothing here is trusted for
     * billing, and a surface that asked for a smaller factor would only
     * misquote a price to itself.
     *
     * @param multiplier factor the call's choices apply to the published rate,
     *                   or null for a call at that rate
     */
    @Transactional(readOnly = true)
    public Optional<Quote> quoteLatest(Long credentialId, UUID apiToolId,
                                        String modelId, BigDecimal quantity, BigDecimal multiplier) {
        Optional<PlatformCredentialPricingVersion> latest = versionRepo.findLatest(credentialId);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Optional<PricingVersionEntry> entry = resolveEntry(latest.get().getId(), apiToolId, modelId);
        // The null quantity is handed STRAIGHT to the policy, never replaced
        // here. Substituting ONE looks harmless and is not: the policy reads a
        // quantity as a PLATFORM measurement and converts it into the published
        // unit, so a "1" injected here means one SECOND, and on a per-minute row
        // it quotes base + rate/60 while the very same null on the billing path
        // (MarkupPolicy.billableQuantity) charges base + rate x 1. A row
        // published at 480 credits per minute quoted 8 and charged 480. The rule
        // for a missing measurement is written once, in the policy, and this
        // method's job is to not have a second opinion about it.
        BigDecimal credits = policy.resolveEffectivePrice(latest.get(), entry, quantity, multiplier);
        // Report the quantity in the unit the quote is expressed in, not the one
        // the caller measured: a surface that prints "480 credits per minute" next
        // to a quantity of 60 seconds contradicts the number right beside it.
        //
        // A MISSING measurement stays null, and deliberately so. The credits
        // above are what an unmeasured call would be charged, which is the whole
        // point of the line before this one, but reporting a quantity of 1
        // alongside them would be the same mistake this method was just fixed
        // for, one layer up: the surfaces switch on this field, and a "1" they
        // cannot distinguish from a measured one turns "60 credits per second"
        // into "1 second = 60 credits for this run" for a node that is going to
        // bill thirty. Null is the honest answer to "how big is this call", and
        // the surface then quotes the rate alone.
        //
        // resolveScopeMarkup, the billing half of this file, answers null here
        // for the same input. The two agree.
        return Optional.of(new Quote(credits, latest.get().getId(), entry.orElse(null),
                quantity == null ? null : policy.billableQuantity(entry, quantity),
                multiplier));
    }

    /**
     * A price shown before it is charged.
     *
     * @param credits          amount the call would cost
     * @param pricingVersionId version the quote was read from
     * @param entry            published row it came from, null when the
     *                         version default applied
     * @param quantity         quantity the price was reached with, expressed in
     *                         the published unit, null when the caller measured
     *                         nothing. A surface reads null as "the size of this
     *                         call is not known yet" and quotes the rate alone,
     *                         so it must never be filled in with an assumption.
     * @param multiplier       factor the call's own choices applied to the
     *                         published rate, echoed so a surface can explain
     *                         why the total is not simply rate x quantity. Null
     *                         for a call at the published rate, which is the
     *                         only thing a quote could say before modifiers.
     */
    public record Quote(BigDecimal credits, Long pricingVersionId,
                         PricingVersionEntry entry, BigDecimal quantity, BigDecimal multiplier) {

        /** The shape every caller built before a call's choices could move its price. */
        public Quote(BigDecimal credits, Long pricingVersionId,
                      PricingVersionEntry entry, BigDecimal quantity) {
            this(credits, pricingVersionId, entry, quantity, null);
        }
    }

    /**
     * True when the latest pricing version of {@code credentialId} has any
     * non-zero rate - either a positive default or at least one positive
     * per-tool override. Used by the inspector when no specific tool context
     * is known (e.g. the node has not yet been bound to a tool), so the
     * toggle still reflects "this integration has pricing somewhere".
     */
    @Transactional(readOnly = true)
    public boolean hasAnyNonZeroMarkup(Long credentialId) {
        Optional<PlatformCredentialPricingVersion> latest = versionRepo.findLatest(credentialId);
        if (latest.isEmpty()) {
            return false;
        }
        BigDecimal def = latest.get().getDefaultMarkupCredits();
        if (def != null && def.signum() > 0) {
            return true;
        }
        for (PricingVersionEntry e : entryRepo.findByPricingVersionId(latest.get().getId())) {
            // V428: a row can price entirely through its per-unit rate, leaving
            // markup_credits (the FIXED part) at zero. Looking only at the base
            // would report "no pricing" for exactly the integrations this
            // feature adds, and the inspector would then hide the platform
            // toggle on every generation endpoint.
            if (isPositive(e.getMarkupCredits()) || isPositive(e.getUnitCredits())
                    || isPositive(e.getMinCredits())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /**
     * {@code pg_advisory_xact_lock} held for the remainder of the transaction.
     * Key derived from the credential id so unrelated credentials don't contend.
     */
    private void acquireAdvisoryLock(Long credentialId) {
        // Single-bigint form: Postgres only exposes pg_advisory_xact_lock(bigint)
        // and pg_advisory_xact_lock(int, int). The namespace constant exceeds int32
        // range, so we fold it together with credentialId into one bigint key.
        long key = ADVISORY_LOCK_NAMESPACE ^ Long.rotateLeft(credentialId, 17);
        jdbc.execute("SELECT pg_advisory_xact_lock(" + key + ")");
    }

    /**
     * Admin action: cancel every live (non-cancelled) pin belonging to a user.
     * Each affected run stops billing markup at its next debit. Returns the
     * number of pins transitioned from live → cancelled. Idempotent - a second
     * call returns 0 because the pins are already cancelled.
     */
    @Transactional
    public int cancelActivePinsForUser(Long userId) {
        List<WorkflowRunPricingPin> live = pinRepo.findByUserIdAndCancelledFalse(userId);
        if (live.isEmpty()) {
            return 0;
        }
        Set<String> runIds = new HashSet<>();
        for (WorkflowRunPricingPin pin : live) {
            runIds.add(pin.getRunId());
        }
        Instant now = Instant.now();
        int total = 0;
        for (String runId : runIds) {
            total += pinRepo.cancelByRunId(runId, now);
        }
        log.info("Cancelled {} active markup pins for user {} across {} runs", total, userId, runIds.size());
        return total;
    }

    /**
     * V148+ scope-aware lookup. Returns the live (non-cancelled) pin for the
     * given scope+credential tuple, or empty if none exists. Lookup-only;
     * never creates a pin. Used by the delinquent-bypass branch of
     * {@code CreditService.tryReserveMarkup} to decide whether the caller is
     * a fresh request or an in-flight RUN sub-allocation.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowRunPricingPin> lookupPin(String scopeKind, String scopeId, Long credentialId) {
        return pinRepo.findByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
                scopeKind, scopeId, credentialId);
    }

    /**
     * V148+ scope-aware existence check. Cheaper than {@link #lookupPin} when
     * the caller only needs the boolean - no entity hydration.
     */
    @Transactional(readOnly = true)
    public boolean existsPin(String scopeKind, String scopeId, Long credentialId) {
        return pinRepo.existsByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
                scopeKind, scopeId, credentialId);
    }

    /**
     * V148+ unified pin create-or-touch. Replaces {@link #savePin} for new
     * code (the legacy method delegates to this with {@code scopeKind="RUN"}).
     *
     * <p>Atomic semantics:
     * <ol>
     *   <li>Look up existing live pin by {@code (scopeKind, scopeId, credentialId)}.</li>
     *   <li>If found: bump {@code last_used_at} and return - refreshes the
     *       STREAM-pin TTL even if no new ledger row is written.</li>
     *   <li>If absent: insert a new pin, bound to {@code pricingVersionId}.
     *       Race-safe via the partial UNIQUE on
     *       {@code (scope_kind, scope_id, platform_credential_id) WHERE cancelled = FALSE}
     *       - a concurrent insert raises {@link DataIntegrityViolationException},
     *       caught here, and the winner is re-read.</li>
     * </ol>
     *
     * <p>"Touch on hit" semantics matter for STREAM pins: a long-running chat
     * session whose tool calls pin lazily on call #1 will then keep the pin
     * fresh on every subsequent call so the 30-day TTL sweep doesn't reap the
     * pin mid-conversation.
     */
    @Transactional
    public WorkflowRunPricingPin pinForScope(String scopeKind, String scopeId,
                                              Long userId, Long credentialId,
                                              Long pricingVersionId) {
        Optional<WorkflowRunPricingPin> existing =
                pinRepo.findByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
                        scopeKind, scopeId, credentialId);
        if (existing.isPresent()) {
            WorkflowRunPricingPin pin = existing.get();
            pin.setLastUsedAt(Instant.now());
            return pinRepo.save(pin);
        }
        WorkflowRunPricingPin pin = new WorkflowRunPricingPin();
        pin.setScopeKind(scopeKind);
        pin.setScopeId(scopeId);
        pin.setRunId(scopeId); // legacy mirror - drop after post-soak migration
        pin.setUserId(userId);
        pin.setPlatformCredentialId(credentialId);
        pin.setPricingVersionId(pricingVersionId);
        pin.setLastUsedAt(Instant.now());
        try {
            return pinRepo.save(pin);
        } catch (DataIntegrityViolationException raced) {
            return pinRepo.findByScopeKindAndScopeIdAndPlatformCredentialIdAndCancelledFalse(
                            scopeKind, scopeId, credentialId)
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * V148+ scope-aware cancellation. Called by:
     * <ul>
     *   <li>Workflow run-terminal chokepoint: {@code cancelPinsForScope("RUN", runId)}.</li>
     *   <li>Stream end-of-conversation hook (if any): {@code cancelPinsForScope("STREAM", streamId)}.</li>
     * </ul>
     * Idempotent - re-running is a no-op once pins are cancelled. Returns the
     * number of rows transitioned from live → cancelled.
     */
    @Transactional
    public int cancelPinsForScope(String scopeKind, String scopeId) {
        if (scopeKind == null || scopeId == null) return 0;
        return pinRepo.cancelByScope(scopeKind, scopeId, Instant.now());
    }

    /**
     * Resolve the effective per-call markup for a (scope, credential, tool)
     * tuple. Creates the pin lazily on first call; on hit, just looks up the
     * pinned version's entry/default rate.
     *
     * <p>Returns empty when the credential has no published pricing version -
     * caller (catalog) interprets that as "fail-closed: refuse the call". The
     * delinquent gate is enforced separately by {@code tryReserveMarkup}.
     */
    @Transactional
    public Optional<ResolvedMarkup> resolveScopeMarkup(String scopeKind, String scopeId,
                                                        Long userId, Long credentialId,
                                                        UUID apiToolId) {
        return resolveScopeMarkup(scopeKind, scopeId, userId, credentialId, apiToolId, null, null);
    }

    /**
     * V428 model- and quantity-aware resolution.
     *
     * <p>Two things changed with generation pricing. An endpoint can back
     * several models at different prices, so the price row is looked up for the
     * model FIRST and only falls back to the endpoint-wide row; and a price can
     * scale on a call dimension, so the caller supplies the quantity and gets
     * back the amount to charge rather than the ingredients to charge it.
     *
     * <p>Passing null for both is exactly the pre-V428 behaviour, which is what
     * the 5-arg overload does.
     *
     * @param modelId  generation model called, or null for the endpoint price
     * @param quantity PLATFORM measurement of the call (seconds, assets,
     *                 characters), or null when the caller has no notion of a
     *                 size. It is converted into the published unit here, so a
     *                 rate published per minute is never multiplied by a count
     *                 of seconds.
     */
    @Transactional
    public Optional<ResolvedMarkup> resolveScopeMarkup(String scopeKind, String scopeId,
                                                        Long userId, Long credentialId,
                                                        UUID apiToolId,
                                                        String modelId,
                                                        BigDecimal quantity) {
        return resolveScopeMarkup(scopeKind, scopeId, userId, credentialId, apiToolId,
                modelId, quantity, null);
    }

    /**
     * Same resolution, for a call whose CHOICES move it off the published rate.
     *
     * <p>A published price scales on the size of the call and on nothing else,
     * which is right until the caller picks something the provider charges
     * extra for: a higher resolution, a reference image inlined into the
     * request. The factor is derived by catalog-service, which holds the
     * parameters and the model's declared modifiers, and applied here to the
     * amount, where the rate and the clamps already live.
     *
     * @param multiplier factor for this call, or null for one at the published
     *                   rate. Both leave the amount exactly as it was before
     *                   modifiers existed.
     */
    @Transactional
    public Optional<ResolvedMarkup> resolveScopeMarkup(String scopeKind, String scopeId,
                                                        Long userId, Long credentialId,
                                                        UUID apiToolId,
                                                        String modelId,
                                                        BigDecimal quantity,
                                                        BigDecimal multiplier) {
        Optional<PlatformCredentialPricingVersion> latest = versionRepo.findLatest(credentialId);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        WorkflowRunPricingPin pin = pinForScope(scopeKind, scopeId, userId, credentialId,
                latest.get().getId());
        // Use the pinned version (not necessarily the latest) so a mid-session
        // admin republish doesn't reprice an already-live scope.
        Long pinnedVersionId = pin.getPricingVersionId();
        Optional<PlatformCredentialPricingVersion> pinnedVersion =
                versionRepo.findById(pinnedVersionId);
        if (pinnedVersion.isEmpty()) {
            return Optional.empty();
        }
        Optional<PricingVersionEntry> entry = resolveEntry(pinnedVersionId, apiToolId, modelId);
        BigDecimal effective = policy.resolveEffectivePrice(pinnedVersion.get(), entry, quantity, multiplier);
        // Report what was actually billed on, in the published unit, so a caller
        // logging or explaining the charge cannot restate the raw measurement as
        // if it were the number of units charged.
        return Optional.of(new ResolvedMarkup(pin.getId(), pinnedVersionId, effective,
                entry.orElse(null),
                quantity == null ? null : policy.billableQuantity(entry, quantity),
                multiplier));
    }

    /**
     * Price row for a call: the model's own row wins, then the endpoint-wide
     * row. The fallback is what lets an admin price a whole endpoint in one
     * gesture and override only the model that deserves a different rate.
     */
    private Optional<PricingVersionEntry> resolveEntry(Long pricingVersionId, UUID apiToolId, String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            Optional<PricingVersionEntry> perModel =
                    entryRepo.findByPricingVersionIdAndApiToolIdAndModelId(pricingVersionId, apiToolId, modelId);
            if (perModel.isPresent()) return perModel;
        }
        return entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(pricingVersionId, apiToolId);
    }

    /**
     * DTO returned by {@link #resolveScopeMarkup}.
     *
     * <p>{@code effectiveMarkup} is the amount to charge for THIS call, already
     * resolved. The {@code entry} alongside it is the published row it came
     * from, carried so a caller can EXPLAIN the price ("60 credits per second")
     * instead of only stating it. It is null when the version default applied.
     * {@code quantity} completes that explanation: how many of the row's own
     * units were charged, never the raw measurement the caller sent.
     */
    public record ResolvedMarkup(Long pinId, Long pricingVersionId, BigDecimal effectiveMarkup,
                                  PricingVersionEntry entry, BigDecimal quantity,
                                  BigDecimal multiplier) {

        /** Pre-V428 shape, kept so existing construction sites stay valid. */
        public ResolvedMarkup(Long pinId, Long pricingVersionId, BigDecimal effectiveMarkup) {
            this(pinId, pricingVersionId, effectiveMarkup, null, null, null);
        }

        /** The shape callers built before a call's choices could move its price. */
        public ResolvedMarkup(Long pinId, Long pricingVersionId, BigDecimal effectiveMarkup,
                               PricingVersionEntry entry, BigDecimal quantity) {
            this(pinId, pricingVersionId, effectiveMarkup, entry, quantity, null);
        }
    }

    /**
     * Create a pin binding a run to a specific (credential, pricing version) pair.
     * Idempotent under concurrency: if another thread wins the race to insert
     * between our read and our write, the unique constraint {@code uk_wrpp_run_cred}
     * throws {@link DataIntegrityViolationException} - we swallow it and re-read
     * the winning row. Without this second catch, check-then-insert under parallel
     * run-init for the same {@code (runId, credentialId)} would silently drop the
     * losing pin and the run would bill zero markup for that credential.
     */
    @Transactional
    public WorkflowRunPricingPin savePin(String runId, Long userId,
                                          Long credentialId, Long pricingVersionId) {
        Optional<WorkflowRunPricingPin> existing =
                pinRepo.findByRunIdAndPlatformCredentialId(runId, credentialId);
        if (existing.isPresent()) {
            return existing.get();
        }
        WorkflowRunPricingPin pin = new WorkflowRunPricingPin();
        pin.setRunId(runId);
        pin.setUserId(userId);
        pin.setPlatformCredentialId(credentialId);
        pin.setPricingVersionId(pricingVersionId);
        try {
            return pinRepo.save(pin);
        } catch (DataIntegrityViolationException raced) {
            return pinRepo.findByRunIdAndPlatformCredentialId(runId, credentialId)
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * All pins (live and cancelled) attached to a run. Used by the debit path
     * to decide whether a tool call should bill markup and against which rate.
     */
    public List<WorkflowRunPricingPin> findPinsForRun(String runId) {
        return pinRepo.findByRunId(runId);
    }

    /**
     * Terminal-state / cancel hook: cancel every live pin on a run. Called by
     * the orchestrator's run-terminal chokepoint so a failed/cancelled run does
     * not continue billing markup if any stragglers fire. Idempotent.
     */
    @Transactional
    public int cancelPinsForRun(String runId) {
        return pinRepo.cancelByRunId(runId, Instant.now());
    }

    /**
     * Live pin lookup used by the hot-path debit flow. Returns empty when no pin
     * exists for {@code (runId, credentialId)} or the pin has been cancelled -
     * either case means markup does not apply for this tool call.
     */
    public Optional<WorkflowRunPricingPin> findLivePin(String runId, Long credentialId) {
        return pinRepo.findByRunIdAndPlatformCredentialId(runId, credentialId)
                .filter(p -> !p.isCancelled());
    }
}
