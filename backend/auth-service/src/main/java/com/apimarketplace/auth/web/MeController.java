package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.web.dto.UserProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * User profile endpoints. Quota / credit balance is exposed via {@code /api/credits/*}
 * (CreditService) - this controller only surfaces identity, plan code and tools.
 */
@RestController
@RequestMapping("/api/me")
@CrossOrigin(origins = "*")
public class MeController {

    private static final Logger logger = LoggerFactory.getLogger(MeController.class);

    private final SubscriptionRepository subscriptionRepository;
    private final RestTemplate restTemplate;
    private final String catalogServiceUrl;

    public MeController(SubscriptionRepository subscriptionRepository,
                        RestTemplate restTemplate,
                        @Value("${services.catalog-url:http://localhost:8081}") String catalogServiceUrl) {
        this.subscriptionRepository = subscriptionRepository;
        this.restTemplate = restTemplate;
        this.catalogServiceUrl = catalogServiceUrl;
    }

    /**
     * Loads the user's tools with their tool categories.
     */
    private List<Map<String, Object>> getUserToolsWithCategories(Long userId) {
        try {
            String monetizationStateUrl = catalogServiceUrl.replaceAll("/+$", "")
                    + "/api/apis/monetization/state";

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-User-ID", userId.toString());
            headers.set("Content-Type", "application/json");

            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                monetizationStateUrl,
                org.springframework.http.HttpMethod.GET,
                entity,
                Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tools = (List<Map<String, Object>>) responseBody.get("tools");
                return tools != null ? tools : new ArrayList<>();
            }

            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Erreur lors de la recuperation des outils pour l'utilisateur {}: {}", userId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * GET /api/me - Recupere le profil utilisateur avec le plan actif.
     *
     * <p>{@code @Transactional(readOnly = true)} : the response builder dereferences
     * {@code activeSubscription.getBillingCustomer().getUser()} and {@code .getPlan()}
     * (LAZY associations). Holding a read tx for the whole controller method keeps
     * the Hibernate session open during those dereferences, so the endpoint stays
     * correct even if {@code spring.jpa.open-in-view} gets disabled in the future.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<UserProfileResponse> getProfile(HttpServletRequest request) {
        try {
            // Recuperer l'ID utilisateur depuis les headers du gateway
            String userIdHeader = request.getHeader("X-User-ID");
            if (userIdHeader == null) {
                logger.warn("Header X-User-ID manquant dans la requete");
                return ResponseEntity.status(401).body(
                    UserProfileResponse.builder()
                        .error("Utilisateur non authentifie")
                        .build()
                );
            }

            Long userId;
            try {
                userId = Long.parseLong(userIdHeader);
            } catch (NumberFormatException e) {
                logger.error("Format invalide pour X-User-ID: {}", userIdHeader);
                return ResponseEntity.status(401).body(
                    UserProfileResponse.builder()
                        .error("Format utilisateur invalide")
                        .build()
                );
            }

            logger.info("Recuperation du profil pour userId={}", userId);

            // Recuperer les outils avec les tool_categories
            List<Map<String, Object>> userTools = getUserToolsWithCategories(userId);

            // Recuperer l'abonnement actif
            Subscription activeSubscription = subscriptionRepository.findActiveByUserId(userId).orElse(null);

            if (activeSubscription == null) {
                // Retourner un profil basique sans abonnement
                UserProfileResponse response = UserProfileResponse.builder()
                    .userId(userId)
                    .username("user-" + userId)
                    .email("user-" + userId + "@example.com")
                    .firstName("User")
                    .lastName("" + userId)
                    .avatarUrl(null)
                    .planCode("FREE")
                    .planName("Free Plan")
                    .subscriptionStatus("no_subscription")
                    .currentPeriodStart(null)
                    .currentPeriodEnd(null)
                    .cancelAtPeriodEnd(false)
                    .tools(userTools)
                    .build();

                logger.info("Profil basique recupere pour l'utilisateur {} (ID: {}) - Pas d'abonnement actif - {} outils",
                    "user-" + userId, userId, userTools.size());

                return ResponseEntity.ok(response);
            }

            // Construire la reponse
            UserProfileResponse response = UserProfileResponse.builder()
                .userId(userId)
                .username(activeSubscription.getBillingCustomer().getUser().getUsername())
                .email(activeSubscription.getBillingCustomer().getUser().getEmail())
                .firstName(activeSubscription.getBillingCustomer().getUser().getFirstName())
                .lastName(activeSubscription.getBillingCustomer().getUser().getLastName())
                .avatarUrl(activeSubscription.getBillingCustomer().getUser().getAvatarUrl())
                .planCode(activeSubscription.getPlan().getCode())
                .planName(activeSubscription.getPlan().getName())
                .subscriptionStatus(activeSubscription.getStatus())
                .currentPeriodStart(activeSubscription.getCurrentPeriodStart())
                .currentPeriodEnd(activeSubscription.getCurrentPeriodEnd())
                .cancelAtPeriodEnd(activeSubscription.getCancelAtPeriodEnd())
                .tools(userTools)
                .build();

            logger.info("Profil recupere pour l'utilisateur {} (ID: {}) - {} outils",
                activeSubscription.getBillingCustomer().getUser().getUsername(), userId, userTools.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Erreur lors de la recuperation du profil utilisateur", e);
            return ResponseEntity.status(500).body(
                UserProfileResponse.builder()
                    .error("Erreur lors de la recuperation du profil: " + e.getMessage())
                    .build()
            );
        }
    }

    /**
     * GET /api/me/subscription - Recupere uniquement l'abonnement actif.
     * See {@link #getProfile} for rationale on {@code @Transactional(readOnly = true)}.
     */
    @GetMapping("/subscription")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getSubscription(HttpServletRequest request) {
        try {
            String userIdHeader = request.getHeader("X-User-ID");
            if (userIdHeader == null) {
                logger.warn("Header X-User-ID manquant dans la requete");
                return ResponseEntity.status(401).body(Map.of("error", "Utilisateur non authentifie"));
            }

            Long userId;
            try {
                userId = Long.parseLong(userIdHeader);
            } catch (NumberFormatException e) {
                logger.error("Format invalide pour X-User-ID: {}", userIdHeader);
                return ResponseEntity.status(401).body(Map.of("error", "Format utilisateur invalide"));
            }

            logger.info("Recuperation de l'abonnement pour userId={}", userId);
            Subscription activeSubscription = subscriptionRepository.findActiveByUserId(userId).orElse(null);

            if (activeSubscription == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Aucun abonnement actif trouve"));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("id", activeSubscription.getId());
            response.put("status", activeSubscription.getStatus());
            response.put("planCode", activeSubscription.getPlan().getCode());
            response.put("planName", activeSubscription.getPlan().getName());
            response.put("currentPeriodStart", activeSubscription.getCurrentPeriodStart());
            response.put("currentPeriodEnd", activeSubscription.getCurrentPeriodEnd());
            response.put("cancelAtPeriodEnd", activeSubscription.getCancelAtPeriodEnd());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Erreur lors de la recuperation de l'abonnement", e);
            return ResponseEntity.status(500).body(Map.of("error", "Erreur lors de la recuperation de l'abonnement"));
        }
    }
}
