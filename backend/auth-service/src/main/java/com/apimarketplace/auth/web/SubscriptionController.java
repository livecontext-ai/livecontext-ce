package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/subscriptions")
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class SubscriptionController {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionController.class);

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    /**
     * Extrait l'ID utilisateur depuis les headers du gateway
     * @param request La requete HTTP
     * @return L'ID utilisateur ou null si invalide
     */
    private Long extractUserId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-ID");
        if (userIdHeader == null) {
            logger.warn("Header X-User-ID manquant dans la requete");
            return null;
        }

        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            logger.error("Format invalide pour X-User-ID: {}", userIdHeader);
            return null;
        }
    }

    /**
     * GET /api/subscriptions/current - Recupere l'abonnement actuel de l'utilisateur
     */
    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> getCurrentSubscription(HttpServletRequest request) {
        try {
            Long userId = extractUserId(request);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Utilisateur non authentifie"));
            }

            logger.info("Recuperation de l'abonnement actuel pour userId={}", userId);

            Optional<Subscription> subscriptionOpt = subscriptionRepository.findActiveByUserId(userId);

            if (subscriptionOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "No active subscription"));
            }

            Subscription subscription = subscriptionOpt.get();
            Plan plan = subscription.getPlan();

            Map<String, Object> response = new HashMap<>();
            response.put("id", subscription.getId());
            response.put("status", subscription.getStatus());
            response.put("planId", plan.getCode());
            response.put("planName", plan.getName());
            response.put("currentPeriodStart", subscription.getCurrentPeriodStart());
            response.put("currentPeriodEnd", subscription.getCurrentPeriodEnd());
            response.put("cancelAtPeriodEnd", subscription.getCancelAtPeriodEnd());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting current subscription", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
        }
    }
}
