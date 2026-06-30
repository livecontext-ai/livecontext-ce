package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleSigner;
import com.apimarketplace.agent.domain.SkillBundleEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.repository.SkillBundleRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Cloud-side skill-bundle publisher. Sibling of
 * {@code com.apimarketplace.agent.catalog.bundle.CatalogBundleService}, reusing the same
 * Ed25519 {@link CatalogBundleSigner} (one keypair signs model, API-catalog and skill
 * bundles).
 *
 * <p>Pipeline: {@link #buildBundle()} snapshots every admin-managed global skill
 * ({@code is_global=true}) -> canonical JSON -> SHA-256 + Ed25519 sign -> insert row with
 * {@code isActive=false}. A separate admin-initiated {@link #activateBundle(Long)} flips
 * the active flag (deactivate-all first, same TX) so the partial unique index
 * {@code idx_skill_bundles_one_active} is never violated. CE pulls via
 * {@link #getActiveSignedBundle()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBundleService {

    private final SkillBundleRepository bundleRepository;
    private final SkillRepository skillRepository;
    private final CatalogBundleSigner signer;

    static final int CURRENT_SCHEMA_VERSION = 1;

    /** Max {@code buildBundle()} attempts before giving up on version collision. */
    static final int BUILD_MAX_ATTEMPTS = 5;

    /**
     * Snapshot the current global-skill set, sign it, and insert a new
     * {@code is_active=false} row. Returns the persisted bundle; call
     * {@link #activateBundle(Long)} when the cloud admin is ready to roll it out.
     *
     * @throws IllegalStateException if the signing key is not configured, there are no
     *     global skills, or the retry budget is exhausted
     */
    public SkillBundleEntity buildBundle() {
        if (!signer.canSign()) {
            throw new IllegalStateException(
                    "CATALOG_BUNDLE_SIGNING_KEY_PEM is not configured - cannot build a signed bundle");
        }

        List<SkillEntity> all = skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc();
        // Data-corruption guard: never publish a global skill whose instructions is blank
        // or a bare number. A numeric value is the fingerprint of the getString().toString()
        // corruption that once stored "106735" into instructions; shipping it would push
        // garbage to every CE via the signed bundle. Drop such rows so a partially-corrupted
        // set still publishes its good skills, and an entirely-corrupted set fails the
        // emptiness check below with a clear, actionable error.
        List<SkillEntity> skills = all.stream()
                .filter(SkillBundleService::hasUsableInstructions)
                .toList();
        int dropped = all.size() - skills.size();
        if (dropped > 0) {
            log.warn("Skill bundle build: dropped {} of {} global skill(s) with blank/numeric "
                    + "instructions (data-corruption guard)", dropped, all.size());
        }
        if (skills.isEmpty()) {
            throw new IllegalStateException(all.isEmpty()
                    ? "no global skills (is_global=true) - refusing to publish an empty skill bundle"
                    : "all " + all.size() + " global skills have blank/numeric instructions - "
                            + "refusing to publish a corrupted skill bundle");
        }

        long version = nextVersion();
        for (int attempt = 1; attempt <= BUILD_MAX_ATTEMPTS; attempt++) {
            try {
                return attemptBuild(version, skills);
            } catch (DataIntegrityViolationException e) {
                long next = version + 1;
                log.warn("Skill bundle version {} already taken (attempt {}/{}): {} - retrying with {}",
                        version, attempt, BUILD_MAX_ATTEMPTS, e.getMostSpecificCause().getMessage(), next);
                version = next;
            }
        }
        throw new IllegalStateException(
                "Failed to build skill bundle after " + BUILD_MAX_ATTEMPTS +
                " version-collision retries - another pod is racing at an unexpected rate");
    }

    /**
     * A publishable global skill must carry real instruction CONTENT. We refuse to ship a
     * skill whose instructions is blank or a bare integer: a numeric value is the fingerprint
     * of the {@code getString().toString()} corruption (it once stored {@code "106735"} into
     * instructions), and shipping it would propagate garbage to every CE.
     */
    static boolean hasUsableInstructions(SkillEntity s) {
        String instr = s.getInstructions();
        if (instr == null) return false;
        String t = instr.strip();
        return !t.isEmpty() && !t.matches("\\d+");
    }

    private SkillBundleEntity attemptBuild(long version, List<SkillEntity> skills) {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        // Pin the snapshot time on the entity BEFORE computing the payload - the canonical
        // JSON includes it, so the re-signature path (toSignedBundle) reads the same value
        // back to rehydrate identical bytes. Truncate to micros so the Postgres-reloaded
        // value matches the in-memory one (see CatalogBundleService).
        Instant snapshotAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        SkillBundleEntity entity = new SkillBundleEntity();
        entity.setVersion(version);
        entity.setSchemaVersion(schemaVersion);
        entity.setSigningKeyId(signer.keyId());
        entity.setIssuer(signer.issuer());
        entity.setSkillCount(skills.size());
        entity.setActive(false);
        entity.setImportedAt(snapshotAt);

        byte[] payload = SkillBundlePayload.canonicalBytes(
                version, schemaVersion, signer.issuer(), snapshotAt, skills);

        entity.setChecksum(signer.checksum(payload));
        entity.setSignature(signer.sign(payload));
        entity.setRawBytesSize(payload.length);

        SkillBundleEntity saved = bundleRepository.save(entity);
        log.info("Built skill bundle: version={}, skills={}, bytes={}, checksum={}",
                saved.getVersion(), saved.getSkillCount(), saved.getRawBytesSize(), saved.getChecksum());
        return saved;
    }

    /**
     * Flip a bundle to {@code is_active=true}, deactivating any previously active row in
     * the same TX. No-op if already active.
     *
     * @throws IllegalArgumentException if the bundle id doesn't exist
     */
    @Transactional
    public SkillBundleEntity activateBundle(Long bundleId) {
        SkillBundleEntity bundle = bundleRepository.findById(bundleId)
                .orElseThrow(() -> new IllegalArgumentException("Bundle not found: " + bundleId));

        if (bundle.isActive()) {
            log.info("Skill bundle {} (version={}) already active - no-op", bundleId, bundle.getVersion());
            return bundle;
        }

        int deactivated = bundleRepository.deactivateAll();
        bundle.setActive(true);
        bundle.setActivatedAt(Instant.now());
        SkillBundleEntity saved = bundleRepository.save(bundle);
        log.info("Activated skill bundle: version={} (deactivated {} previously active)",
                saved.getVersion(), deactivated);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<SignedSkillBundle> getActiveSignedBundle() {
        return bundleRepository.findFirstByActiveTrue().map(this::toSignedBundle);
    }

    @Transactional(readOnly = true)
    public Optional<SignedSkillBundle> getSignedBundleByVersion(long version) {
        return bundleRepository.findByVersion(version).map(this::toSignedBundle);
    }

    @Transactional(readOnly = true)
    public List<SkillBundleEntity> listBundles() {
        return bundleRepository.findAll();
    }

    private SignedSkillBundle toSignedBundle(SkillBundleEntity entity) {
        // Re-snapshot the global skills at serve time and re-check the stored checksum. If
        // someone edited / added / removed a global skill since the build, the freshly
        // computed checksum diverges and we refuse to serve a bundle whose signature no
        // longer matches the bytes - the operator must rebuild + activate.
        // Apply the SAME corruption guard as buildBundle (hasUsableInstructions): the stored
        // checksum was computed over the FILTERED set, so serve must filter identically or a
        // partially-corrupted set would compute a divergent checksum and become unservable.
        List<SkillEntity> skills = skillRepository.findByIsGlobalTrueOrderByCreatedAtAsc()
                .stream().filter(SkillBundleService::hasUsableInstructions).toList();
        byte[] payload = SkillBundlePayload.canonicalBytes(
                entity.getVersion(), entity.getSchemaVersion(),
                entity.getIssuer(), entity.getImportedAt(), skills);

        String freshChecksum = signer.checksum(payload);
        if (!freshChecksum.equals(entity.getChecksum())) {
            throw new IllegalStateException(
                    "Skill bundle version " + entity.getVersion() + " is no longer servable: the "
                    + "freshly-computed canonical bytes diverge from the stored checksum. Likely "
                    + "cause: a global skill was added, edited or un-globaled since this bundle was "
                    + "built. Action: rebuild + activate a new bundle (POST /api/model-config/"
                    + "skill-bundles then POST /api/model-config/skill-bundles/{id}/activate). Until "
                    + "then, CE clients keep their last-good cached bundle.");
        }

        return new SignedSkillBundle(
                entity.getVersion(),
                entity.getSchemaVersion(),
                entity.getChecksum(),
                entity.getSignature(),
                entity.getSigningKeyId(),
                entity.getIssuer(),
                entity.getSkillCount(),
                entity.getRawBytesSize(),
                Base64.getEncoder().encodeToString(payload)
        );
    }

    private long nextVersion() {
        long now = System.currentTimeMillis();
        Long lastVersion = bundleRepository.findTopByOrderByVersionDesc()
                .map(SkillBundleEntity::getVersion).orElse(0L);
        return Math.max(now, lastVersion + 1);
    }
}
