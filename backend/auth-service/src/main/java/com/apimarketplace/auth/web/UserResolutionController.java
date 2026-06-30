package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.service.ApiKeyService;
import com.apimarketplace.auth.service.UserResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Contrôleur pour la resolution des utilisateurs depuis le gateway
 * Fournit les endpoints necessaires pour l'authentification centralisee
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserResolutionController {

    private static final Logger log = LoggerFactory.getLogger(UserResolutionController.class);

    @Autowired
    private UserResolutionService userResolutionService;

    @Autowired
    private ApiKeyService apiKeyService;

    /**
     * Resolves a user by their providerId (Keycloak sub UUID)
     * Called by the gateway to obtain user information
     *
     * @param providerId The provider identifier (Keycloak sub)
     * @return UserResolutionResponse with all user information
     */
    @GetMapping("/resolve")
    public ResponseEntity<UserResolutionResponse> resolveUser(@RequestParam("providerId") String providerId, HttpServletRequest request) {
        log.info("🔍 Resolution utilisateur demandee pour providerId: {}", providerId);

        if (providerId == null || providerId.trim().isEmpty()) {
            log.warn("❌ ProviderId manquant ou vide");
            return ResponseEntity.badRequest().build();
        }

        // Extract the Keycloak JWT from Authorization header
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String keycloakJwt = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            keycloakJwt = authHeader.substring(7);
        }

        if (keycloakJwt == null) {
            log.warn("❌ JWT missing from Authorization header");
            return ResponseEntity.badRequest().build();
        }

        try {
            UserResolutionResponse userInfo = userResolutionService.resolveUser(providerId, keycloakJwt);
            
            if (userInfo == null) {
                log.warn("❌ Utilisateur non trouve pour providerId: {}", providerId);
                return ResponseEntity.notFound().build();
            }

            log.info("✅ Resolution utilisateur reussie pour providerId: {} -> userId: {}",
                    providerId, userInfo.getUserId());
            
            return ResponseEntity.ok(userInfo);

        } catch (Exception e) {
            log.error("❌ Erreur lors de la resolution de l'utilisateur pour providerId: {}", providerId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Verifie si un utilisateur peut faire une requete
     * Endpoint pour verifier les quotas et le statut
     * 
     * @param providerId L'identifiant du provider
     * @return true si l'utilisateur peut faire une requete
     */
    @GetMapping("/can-request")
    public ResponseEntity<Boolean> canUserMakeRequest(@RequestParam("providerId") String providerId) {
        log.info("🔍 Verification des permissions pour providerId: {}", providerId);

        if (providerId == null || providerId.trim().isEmpty()) {
            log.warn("❌ ProviderId manquant ou vide");
            return ResponseEntity.badRequest().build();
        }

        try {
            boolean canRequest = userResolutionService.canUserMakeRequest(providerId);
            
            log.info("✅ Verification des permissions pour providerId: {} -> {}",
                    providerId, canRequest ? "AUTORISe" : "REFUSe");
            
            return ResponseEntity.ok(canRequest);

        } catch (Exception e) {
            log.error("❌ Erreur lors de la verification des permissions pour providerId: {}", providerId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Met a jour le userVersion d'un utilisateur
     * Endpoint pour invalider le cache du gateway
     * 
     * @param providerId L'identifiant du provider
     * @return Le nouveau userVersion
     */
    @PostMapping("/invalidate-cache")
    public ResponseEntity<Long> invalidateUserCache(@RequestParam("providerId") String providerId) {
        log.info("🔄 Invalidation du cache pour providerId: {}", providerId);

        if (providerId == null || providerId.trim().isEmpty()) {
            log.warn("❌ ProviderId manquant ou vide");
            return ResponseEntity.badRequest().build();
        }

        try {
            Long newVersion = userResolutionService.updateUserVersion(providerId);
            
            if (newVersion == null) {
                log.warn("❌ echec de la mise a jour du userVersion pour providerId: {}", providerId);
                return ResponseEntity.notFound().build();
            }

            log.info("✅ Cache invalide pour providerId: {} -> userVersion: {}",
                    providerId, newVersion);
            
            return ResponseEntity.ok(newVersion);

        } catch (Exception e) {
            log.error("❌ Erreur lors de l'invalidation du cache pour providerId: {}", providerId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Resolves a user by plaintext API key.
     * Called by the gateway for API key authentication.
     *
     * @param apiKey The plaintext API key
     * @return UserResolutionResponse with user information
     */
    @GetMapping("/resolve-by-api-key")
    public ResponseEntity<UserResolutionResponse> resolveByApiKey(@RequestParam("apiKey") String apiKey) {
        log.info("Resolving user by API key");

        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("API key missing or empty");
            return ResponseEntity.badRequest().build();
        }

        try {
            UserResolutionResponse userInfo = apiKeyService.resolveByPlaintextKey(apiKey);

            if (userInfo == null) {
                log.debug("No user found for provided API key");
                return ResponseEntity.notFound().build();
            }

            log.info("User resolved by API key: userId={}", userInfo.getUserId());
            return ResponseEntity.ok(userInfo);

        } catch (Exception e) {
            log.error("Error resolving user by API key", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Endpoint de sante pour verifier que le service fonctionne
     * 
     * @return Status du service
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        log.info("🏥 Verification de la sante du service de resolution utilisateur");
        return ResponseEntity.ok("UserResolutionService is healthy");
    }
}