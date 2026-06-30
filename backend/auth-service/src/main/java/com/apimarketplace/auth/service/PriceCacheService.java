package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.repository.PriceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.apimarketplace.auth.enums.PlanCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PriceCacheService {

    private static final Logger log = LoggerFactory.getLogger(PriceCacheService.class);

    @Autowired
    private PriceRepository priceRepository;

    // Cache of price_id by planCode and cadence
    private final Map<String, String> priceIdCache = new HashMap<>();

    @PostConstruct
    public void initializeCache() {
        log.info("Initializing Stripe price cache...");
        loadPricesFromDatabase();
        log.info("Price cache initialized with {} entries", priceIdCache.size());
    }

    /**
     * Refreshes the cache every hour
     */
    @Scheduled(fixedRate = 3600000) // 1 hour = 3600000 ms
    @SchedulerLock(name = "price_cache_refresh", lockAtMostFor = "PT5M")
    public void refreshCache() {
        log.info("Automatic refresh of Stripe price cache...");
        loadPricesFromDatabase();
        log.info("Price cache refreshed with {} entries", priceIdCache.size());
    }

    private void loadPricesFromDatabase() {
        try {
            // Use the method that eagerly loads relationships
            List<Price> prices = priceRepository.findAllWithPlans();
            priceIdCache.clear();
            
            for (Price price : prices) {
                if (price.getProviderPriceId() != null && !price.getProviderPriceId().trim().isEmpty()) {
                    String key = price.getPlan().getCode() + "_" + price.getCadence();
                    priceIdCache.put(key, price.getProviderPriceId());
                    log.debug("Cache: {} -> {}", key, price.getProviderPriceId());
                }
            }
        } catch (Exception e) {
            log.error("Error loading prices from database", e);
        }
    }

    /**
     * Retrieves the Stripe price_id for a given plan and cadence
     */
    public Optional<String> getPriceId(String planCode, String cadence) {
        String key = planCode + "_" + cadence;
        String priceId = priceIdCache.get(key);
        
        if (priceId != null) {
            log.debug("Price ID found in cache: {} -> {}", key, priceId);
            return Optional.of(priceId);
        }
        
        log.warn("Price ID not found in cache for: {}", key);
        return Optional.empty();
    }

    /**
     * Retrieves the Stripe price_id for a plan (uses 'monthly' by default)
     */
    public Optional<String> getPriceId(String planCode) {
        return getPriceId(planCode, "monthly");
    }

    /**
     * Forces cache reload from database
     */
    public void forceRefreshCache() {
        log.info("Forced reload of Stripe price cache...");
        loadPricesFromDatabase();
        log.info("Price cache reloaded with {} entries", priceIdCache.size());
    }

    /**
     * Returns all cached prices (for debugging)
     */
    public Map<String, String> getAllCachedPrices() {
        return new HashMap<>(priceIdCache);
    }

    /**
     * Returns the credit pack Stripe price ID. Team premium is represented by
     * plan-aware quantities in CreditTierConstants, not by a separate Stripe unit price.
     */
    public Optional<String> getCreditPriceId(String basePlanCode, String cadence) {
        return getPriceId("CREDIT_PACK", cadence);
    }

    /**
     * Checks if a Stripe price ID belongs to a credit pack (not a base plan).
     */
    public boolean isCreditPackPrice(String stripePriceId) {
        String planCode = resolvePlanCodeFromPriceId(stripePriceId);
        return PlanCode.isCreditPack(planCode);
    }

    /**
     * Resolves the plan code from a Stripe price ID by searching the cache.
     * Cache format: "PLANCODE_cadence" -> "price_xxx"
     * Uses lastIndexOf('_') so CREDIT_PACK_monthly -> CREDIT_PACK works correctly.
     */
    public String resolvePlanCodeFromPriceId(String stripePriceId) {
        if (stripePriceId == null) return null;
        for (Map.Entry<String, String> entry : priceIdCache.entrySet()) {
            if (stripePriceId.equals(entry.getValue())) {
                String key = entry.getKey();
                int idx = key.lastIndexOf('_');
                if (idx > 0) {
                    return key.substring(0, idx);
                }
            }
        }
        return null;
    }

    /**
     * Retrieves the billing cycle (cadence) for a given priceId
     */
    public Optional<String> getBillingCycleByPriceId(String priceId) {
        if (priceId == null) {
            return Optional.empty();
        }
        
        // Iterate through cache to find the key matching this priceId
        for (Map.Entry<String, String> entry : priceIdCache.entrySet()) {
            if (priceId.equals(entry.getValue())) {
                // Extract cadence from the key (format: "PLANCODE_CADENCE")
                String key = entry.getKey();
                String[] parts = key.split("_");
                if (parts.length >= 2) {
                    return Optional.of(parts[parts.length - 1]); // Last part = cadence
                }
            }
        }
        
        log.warn("Billing cycle not found for priceId: {}", priceId);
        return Optional.empty();
    }
}
