package com.apimarketplace.monolith;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Stub billing controller for CE mode.
 *
 * In CE, billing.provider=none so the real BillingController (Stripe) is not loaded.
 * The frontend still calls /api/billing/me, /api/billing/plans, /api/billing/scheduled-change
 * to determine plan status. This stub returns FREE plan defaults.
 */
@RestController
@RequestMapping("/api/billing")
@ConditionalOnProperty(name = "billing.provider", havingValue = "none")
public class CeBillingStubController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentBillingStatus() {
        return ResponseEntity.ok(Map.of(
            "subscription", Map.of(
                "status", "active",
                "planCode", "FREE",
                "planName", "Community Edition",
                "cadence", "monthly",
                "cancelAtPeriodEnd", false,
                "creditQuantity", 0,
                "creditTierIndex", 0
            ),
            "hasActiveSubscription", false,
            "canUpgrade", false
        ));
    }

    @GetMapping("/plans")
    public ResponseEntity<List<Object>> getPlans() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/scheduled-change")
    public ResponseEntity<Map<String, Object>> getScheduledChange() {
        return ResponseEntity.ok(Map.of());
    }
}
