package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PricingVersionService.class);

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
                .findByPricingVersionIdAndApiToolIdAndModelIdIsNull(pricingVersionId, apiToolId);
        warnIfUnitPriced(entry, apiToolId);
        return Optional.of(policy.resolveEffectivePriceWithoutQuantity(version.get(), entry));
    }

    /**
     * This path has no quantity: it serves callers that only ever met flat
     * per-call prices, which was every price before V428.
     *
     * <p>An admin can now attach a per-unit rate to an endpoint that such a
     * caller also reaches. Charging only the fixed part there would bill ZERO
     * for a pure per-second rate, handing out the platform's own provider key
     * for free and silently. {@code resolveEffectivePriceWithoutQuantity} bills
     * one unit instead, and this logs the mismatch so it is visible rather than
     * merely survivable.
     */
    private void warnIfUnitPriced(Optional<PricingVersionEntry> entry, UUID apiToolId) {
        if (policy.isUnitPricedWithoutQuantity(entry)) {
            log.warn("Tool {} has a per-{} price but was billed through the flat path, which "
                            + "carries no quantity. Charged one unit. A per-unit price belongs on an "
                            + "endpoint reached through the generation surface, which knows the size "
                            + "of the call.",
                    apiToolId, entry.get().getPriceUnit());
        }
    }

    /**
     * Same as {@link #resolveMarkup} but returns the full frozen descriptor
     * (used by callers that need the version number for auditing /
     * source_id construction).
     */
    @Transactional(readOnly = true)
    public Optional<FrozenMarkup> resolveFrozenMarkup(Long pricingVersionId, UUID apiToolId) {
        return resolveFrozenMarkup(pricingVersionId, apiToolId, null, null);
    }

    /**
     * V428 variant: the same frozen descriptor, resolved for a specific
     * generation model and a measured call.
     *
     * <p>The two-argument form above cannot price a generation, and says so: an
     * endpoint can back several models at different rates, and a per-unit rate
     * resolved without a size is the price of ONE unit. Its only honest answer
     * for a relayed generation was therefore to refuse, which is what a
     * cloud-linked self-hosted install has been getting.
     *
     * <p>Both extra arguments are resolved HERE rather than by the caller, for
     * the same reason the direct path resolves them here: the row carries the
     * unit, so the row has to be the thing that converts the measurement. A
     * caller that scaled the quantity itself would be a second copy of the
     * pricing rules, and the first divergence between the two copies is a
     * mis-charge nobody sees.
     *
     * @param modelId  generation model the call names, or null for the
     *                 endpoint-wide row
     * @param quantity PLATFORM measurement of the call (seconds, assets,
     *                 characters), or null when the caller could not measure it.
     *                 Null keeps the one-unit behaviour of the flat path, which
     *                 is defensible rather than free.
     */
    @Transactional(readOnly = true)
    public Optional<FrozenMarkup> resolveFrozenMarkup(Long pricingVersionId, UUID apiToolId,
                                                       String modelId, BigDecimal quantity) {
        return resolveFrozenMarkup(pricingVersionId, apiToolId, modelId, quantity, null);
    }

    /**
     * Same frozen descriptor, for a call whose CHOICES move it off the
     * published rate.
     *
     * <p>A relayed 1080p render costs the platform owner more than the 720p one
     * the rate is quoted for, and the relay reads that factor back out of the
     * body it was sent rather than being told it. Applied to the amount here,
     * beside the rate and the clamps, so the relayed price and the direct one
     * are reached by the same arithmetic.
     *
     * @param multiplier factor for this call, or null for one at the published
     *                   rate. Null leaves every amount exactly as it was.
     */
    @Transactional(readOnly = true)
    public Optional<FrozenMarkup> resolveFrozenMarkup(Long pricingVersionId, UUID apiToolId,
                                                       String modelId, BigDecimal quantity,
                                                       BigDecimal multiplier) {
        Optional<PlatformCredentialPricingVersion> version = versionRepo.findById(pricingVersionId);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        Optional<PricingVersionEntry> entry = resolveEntry(pricingVersionId, apiToolId, modelId);
        BigDecimal rate;
        if (quantity != null) {
            // Measured: the row converts the platform measurement into its own
            // published unit and multiplies. Same method the direct path uses.
            rate = policy.resolveEffectivePrice(version.get(), entry, quantity, multiplier);
        } else {
            warnIfUnitPriced(entry, apiToolId);
            // The factor still applies to a call nobody could measure: it is a
            // statement about WHAT was asked for, not about how big it was, and
            // a flat-priced model that sells a resolution has no quantity to
            // begin with.
            rate = policy.resolveEffectivePrice(version.get(), entry, null, multiplier);
        }
        return Optional.of(new FrozenMarkup(
                version.get().getId(),
                version.get().getPlatformCredentialId(),
                version.get().getVersion(),
                rate,
                entry.map(PricingVersionEntry::getPriceUnit).orElse("call"),
                // A MEASURED per-unit row has already been multiplied, so it is
                // no longer "the price of one unit" and must not be reported as
                // such: the relay refuses on exactly that signal, and would
                // refuse the very call this overload exists to allow.
                quantity != null
                        ? BigDecimal.ZERO
                        : entry.map(PricingVersionEntry::getUnitCredits).orElse(BigDecimal.ZERO),
                entry.isPresent()
        ));
    }

    /** Per-model row when one is named and published, else the endpoint-wide row. */
    private Optional<PricingVersionEntry> resolveEntry(Long pricingVersionId, UUID apiToolId,
                                                        String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            Optional<PricingVersionEntry> perModel = entryRepo
                    .findByPricingVersionIdAndApiToolIdAndModelId(pricingVersionId, apiToolId, modelId);
            if (perModel.isPresent()) return perModel;
        }
        return entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(pricingVersionId, apiToolId);
    }

    /**
     * Snapshot of the pricing a run is bound to.
     *
     * @param pricingVersionId    id of the {@code platform_credential_pricing_version} row
     * @param credentialId        owning platform credential
     * @param version             monotonic version number under the credential
     * @param effectiveMarkup     per-call markup for the requested tool
     */
    /**
     * A frozen per-call amount, plus the SHAPE of the row it came from.
     *
     * <p>{@code priceUnit} and {@code unitCredits} travel with the amount
     * because this path carries no quantity: a per-unit row resolved here is the
     * price of ONE unit, and a caller handed only the number cannot tell that
     * apart from a flat price an administrator chose. Without them a per-second
     * video relayed from a self-hosted install is charged as one second.
     */
    public record FrozenMarkup(Long pricingVersionId,
                                Long credentialId,
                                Integer version,
                                BigDecimal effectiveMarkup,
                                String priceUnit,
                                BigDecimal unitCredits,
                                /**
                                 * Whether a price was published for THIS endpoint, or the amount
                                 * fell back to the credential-wide default. Without it the two are
                                 * indistinguishable downstream: a default reports {@code "call"}
                                 * and zero unit credits, which reads exactly like a flat price the
                                 * owner chose deliberately.
                                 */
                                boolean pricedByPublishedRow) {
    }
}
