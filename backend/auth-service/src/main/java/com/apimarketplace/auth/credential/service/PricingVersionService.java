package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side façade: given a pinned pricing version id, resolve the effective
 * per-call markup for a specific api tool. This is the single call-site the
 * orchestrator uses at every debit, so the lookup must be stable and cheap
 * (both tables are indexed on the access keys).
 */
@Service
public class PricingVersionService {

    private final PlatformCredentialPricingVersionRepository versionRepo;
    private final PricingVersionEntryRepository entryRepo;
    private final MarkupPolicy policy;

    public PricingVersionService(PlatformCredentialPricingVersionRepository versionRepo,
                                  PricingVersionEntryRepository entryRepo,
                                  MarkupPolicy policy) {
        this.versionRepo = versionRepo;
        this.entryRepo = entryRepo;
        this.policy = policy;
    }

    /**
     * Resolve the effective markup for a tool under a pinned version.
     * Returns {@link Optional#empty()} if the version is unknown (stale pin).
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> resolveMarkup(Long pricingVersionId, UUID apiToolId) {
        Optional<PlatformCredentialPricingVersion> version = versionRepo.findById(pricingVersionId);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        Optional<PricingVersionEntry> entry = entryRepo
                .findByPricingVersionIdAndApiToolId(pricingVersionId, apiToolId);
        return Optional.of(policy.resolveEffectiveMarkup(version.get(), entry));
    }

    /**
     * Same as {@link #resolveMarkup} but returns the full frozen descriptor
     * (used by callers that need the version number for auditing /
     * source_id construction).
     */
    @Transactional(readOnly = true)
    public Optional<FrozenMarkup> resolveFrozenMarkup(Long pricingVersionId, UUID apiToolId) {
        Optional<PlatformCredentialPricingVersion> version = versionRepo.findById(pricingVersionId);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        Optional<PricingVersionEntry> entry = entryRepo
                .findByPricingVersionIdAndApiToolId(pricingVersionId, apiToolId);
        BigDecimal rate = policy.resolveEffectiveMarkup(version.get(), entry);
        return Optional.of(new FrozenMarkup(
                version.get().getId(),
                version.get().getPlatformCredentialId(),
                version.get().getVersion(),
                rate
        ));
    }

    /**
     * Snapshot of the pricing a run is bound to.
     *
     * @param pricingVersionId    id of the {@code platform_credential_pricing_version} row
     * @param credentialId        owning platform credential
     * @param version             monotonic version number under the credential
     * @param effectiveMarkup     per-call markup for the requested tool
     */
    public record FrozenMarkup(Long pricingVersionId,
                                Long credentialId,
                                Integer version,
                                BigDecimal effectiveMarkup) {
    }
}
