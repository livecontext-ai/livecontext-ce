package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.BundleGenerationPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps every generation model sellable on a platform key: publishes the catalog's
 * starting price for each model its platform credential has NEVER priced.
 *
 * <p><b>The gap this closes (cloud).</b> The importer publishes starting prices through a
 * bootstrap that runs once per credential. A model added to a provider after that first
 * version, or a platform key an administrator creates after the import, therefore had no
 * price, and billing refuses an unpriced platform-key generation. The model was listed,
 * the key was configured, and every call was refused as not available.
 *
 * <p><b>It only ever adds.</b> The decision is auth-service's
 * ({@code PlatformCredentialPricingService.addNeverPricedPrices}): a row is published only
 * when no version of that credential ever held a price for the (endpoint, model) pair. A
 * live price is never touched, and a price an administrator removed stays removed.
 * Re-offering the whole catalog every tick is therefore free when nothing is missing, and
 * it is what prices a key that is pasted long after the import.
 *
 * <p><b>Cloud only.</b> A self-hosted install gets its prices from the shipped generation
 * seed and the signed catalog bundle, both stamped {@code bundle} so the cloud can keep
 * them current; an {@code admin} row minted here would freeze them. The CE profile turns
 * this off with {@code catalog.generation.price-gap-fill.enabled=false}.
 *
 * <p>Prices are read from the same descriptors the generation tool executes
 * ({@link GenerationRegistry}), so a model and the price it is sold at cannot disagree. A
 * descriptor with no price block parses as an explicit zero price, and is offered as one,
 * exactly as the importer publishes it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catalog.generation.price-gap-fill.enabled", havingValue = "true",
        matchIfMissing = true)
public class NeverPricedGenerationModelPublisher {

    /** Recorded as the author of the published pricing version. */
    static final String ORIGIN = "catalog-starting-price";

    private final GenerationRegistry registry;
    private final CredentialClient credentialClient;

    @Scheduled(initialDelayString = "${catalog.generation.price-gap-fill.initial-delay-ms:120000}",
            fixedDelayString = "${catalog.generation.price-gap-fill.interval-ms:900000}")
    public void tick() {
        try {
            publishOnce();
        } catch (Exception e) {
            // A missed tick is a delay, never an outage: the next one re-offers everything.
            log.warn("Generation starting-price gap fill failed: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** One pass. Returns auth-service's summary, or an empty map when nothing was offered. */
    Map<String, Object> publishOnce() {
        List<BundleGenerationPriceDto> rows = catalogStartingPrices();
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> detail = credentialClient.addNeverPricedGenerationPrices(rows, ORIGIN)
                .orElse(Map.of());
        Object published = detail.get("publishedCredentials");
        if (published instanceof Number n && n.intValue() > 0) {
            log.info("Generation starting prices published for never-priced models: {}", detail);
        } else {
            log.debug("Generation starting-price gap fill: nothing missing ({} row(s) offered)", rows.size());
        }
        return detail;
    }

    List<BundleGenerationPriceDto> catalogStartingPrices() {
        List<BundleGenerationPriceDto> rows = new ArrayList<>();
        for (GenerationRegistry.GenerationModel m : registry.list(null)) {
            GenerationSpec.Price price = m.seedPrice();
            if (m.platformCredentialName() == null || m.platformCredentialName().isBlank()
                    || m.apiToolId() == null || price == null) {
                continue;
            }
            rows.add(new BundleGenerationPriceDto(
                    m.platformCredentialName(), m.apiToolId().toString(), m.model().id(),
                    price.unit(), price.base(), price.perUnit(), price.min(), price.max()));
        }
        return rows;
    }
}
