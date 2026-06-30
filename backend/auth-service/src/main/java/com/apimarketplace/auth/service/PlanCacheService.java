package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.PriceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PlanCacheService {

    private static final Logger log = LoggerFactory.getLogger(PlanCacheService.class);

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PriceRepository priceRepository;

    // Cache of plans by code
    private final Map<String, Plan> planCache = new HashMap<>();
    
    // Cache of prices by planCode and cadence
    private final Map<String, Price> priceCache = new HashMap<>();

    @PostConstruct
    public void initializeCache() {
        log.info("Initializing plans and prices cache...");
        loadPlansAndPricesFromDatabase();
        log.info("Plans cache initialized with {} plans and {} prices", planCache.size(), priceCache.size());
    }

    /**
     * Refreshes the cache every hour
     */
    @Scheduled(fixedRate = 3600000) // 1 hour = 3600000 ms
    @SchedulerLock(name = "plan_cache_refresh", lockAtMostFor = "PT5M")
    public void refreshCache() {
        log.info("Automatic refresh of plans and prices cache...");
        loadPlansAndPricesFromDatabase();
        log.info("Plans and prices cache refreshed with {} plans and {} prices", planCache.size(), priceCache.size());
    }

    private void loadPlansAndPricesFromDatabase() {
        try {
            // Load all plans
            List<Plan> plans = planRepository.findAll();
            planCache.clear();
            
            for (Plan plan : plans) {
                planCache.put(plan.getCode(), plan);
                log.debug("Plan cached: {} -> {}", plan.getCode(), plan.getName());
            }

            // Load all prices with their plans (eager relationship)
            List<Price> prices = priceRepository.findAllWithPlans();
            priceCache.clear();
            
            for (Price price : prices) {
                String key = price.getPlan().getCode() + "_" + price.getCadence();
                priceCache.put(key, price);
                log.debug("Price cached: {} -> {}", key, price.getProviderPriceId());
            }
        } catch (Exception e) {
            log.error("Error loading plans and prices from database", e);
        }
    }

    /**
     * Retrieves a plan by its code
     */
    public Optional<Plan> getPlan(String planCode) {
        Plan plan = planCache.get(planCode);
        if (plan != null) {
            log.debug("Plan found in cache: {}", planCode);
            return Optional.of(plan);
        }
        
        log.warn("Plan not found in cache: {}", planCode);
        return Optional.empty();
    }

    /**
     * Retrieves a price by planCode and cadence
     */
    public Optional<Price> getPrice(String planCode, String cadence) {
        String key = planCode + "_" + cadence;
        Price price = priceCache.get(key);
        
        if (price != null) {
            log.debug("Price found in cache: {} -> {}", key, price.getProviderPriceId());
            return Optional.of(price);
        }
        
        log.warn("Price not found in cache: {}", key);
        return Optional.empty();
    }

    /**
     * Retrieves all cached plans
     */
    public Map<String, Plan> getAllPlans() {
        return new HashMap<>(planCache);
    }

    /**
     * Retrieves all cached prices
     */
    public Map<String, Price> getAllPrices() {
        return new HashMap<>(priceCache);
    }

    /**
     * Forces cache reload from database
     */
    public void forceRefreshCache() {
        log.info("Forced reload of plans and prices cache...");
        loadPlansAndPricesFromDatabase();
        log.info("Plans and prices cache reloaded with {} plans and {} prices", planCache.size(), priceCache.size());
    }

    /**
     * Retrieves the hierarchy order of a plan (uses id as order)
     */
    public Optional<Long> getPlanOrder(String planCode) {
        Plan plan = planCache.get(planCode);
        if (plan != null) {
            return Optional.of(plan.getId());
        }
        return Optional.empty();
    }
}
