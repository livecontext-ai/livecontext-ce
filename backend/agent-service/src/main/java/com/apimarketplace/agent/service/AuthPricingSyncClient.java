package com.apimarketplace.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin HTTP client for pushing pricing changes from the agent-service catalog
 * (the source of truth) into {@code auth.model_pricing} (billing mirror).
 *
 * <p>Unit contract (shared with {@code auth.model_pricing}): both sides store
 * the provider list price as <b>USD per 1M tokens</b> - no conversion.
 *
 * <p>Called from two paths:
 * <ul>
 *   <li>{@link ModelCatalogService#saveOverride} - single-row admin UI edits (synchronous).</li>
 *   <li>{@code CatalogBundleApplier#apply} - bundle activations, invoked in
 *       {@code TransactionSynchronization.afterCommit()} so only successfully
 *       committed bundle rows hit auth-service.</li>
 * </ul>
 *
 * <p>Since V130 bridge rows carry the underlying cloud model's list price and
 * {@code CreditService.consumeForChat} bills them verbatim - there is no
 * "skip bridges" short-circuit here. {@code providerKind} is propagated so
 * the billing mirror keeps the catalog-origin discriminator for reporting.
 *
 * <p>Fire-and-forget: exceptions are logged and swallowed. A failed sync leaves
 * the billing mirror stale vs. the catalog SSoT; {@code ModelPricingService} has
 * a 1.0/4.0 USD/1M fallback so cost calculation still proceeds.
 */
@Slf4j
@Component
public class AuthPricingSyncClient {

    private final RestTemplate restTemplate;
    private final String authServiceUrl;

    public AuthPricingSyncClient(RestTemplate restTemplate,
                                 @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl) {
        this.restTemplate = restTemplate;
        this.authServiceUrl = authServiceUrl;
    }

    public void sync(String provider, String modelId, BigDecimal priceInput, BigDecimal priceOutput,
                     String providerKind) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("provider", provider);
            body.put("model", modelId);
            body.put("inputRate", priceInput != null ? priceInput : BigDecimal.ZERO);
            body.put("outputRate", priceOutput != null ? priceOutput : BigDecimal.ZERO);
            if (providerKind != null && !providerKind.isBlank()) {
                body.put("providerKind", providerKind);
            }

            restTemplate.postForEntity(
                    authServiceUrl + "/api/internal/auth/model-pricing/sync",
                    body, Void.class);
            log.info("Synced pricing to auth-service for {}/{}: input={} output={} kind={} (USD/1M)",
                    provider, modelId, priceInput, priceOutput, providerKind);
        } catch (Exception e) {
            log.warn("Failed to sync pricing to auth-service for {}/{}: {}",
                    provider, modelId, e.getMessage());
        }
    }
}
