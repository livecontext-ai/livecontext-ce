package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cloud-side endpoint for CE instances to acquire publications via OAuth-linked accounts.
 *
 * - Free publications (creditsPerUse=0): GET /snapshot returns data directly (no auth)
 * - Paid publications (creditsPerUse>0): POST /acquire-with-auth requires Bearer token
 *   from the CE-linked cloud account. Credits are deducted from the linked user.
 *
 * Only active on the cloud EE instance (marketplace.ce-download.enabled=true).
 */
@RestController
@RequestMapping("/api/ce-marketplace")
@ConditionalOnProperty(name = "marketplace.ce-download.enabled", havingValue = "true")
public class CeDownloadController {

    private static final Logger logger = LoggerFactory.getLogger(CeDownloadController.class);

    private final WorkflowPublicationRepository publicationRepository;
    private final PublicationReceiptRepository receiptRepository;
    private final CreditConsumptionClient creditClient;
    private final AuthClient authClient;

    public CeDownloadController(WorkflowPublicationRepository publicationRepository,
                                 PublicationReceiptRepository receiptRepository,
                                 CreditConsumptionClient creditClient,
                                 AuthClient authClient) {
        this.publicationRepository = publicationRepository;
        this.receiptRepository = receiptRepository;
        this.creditClient = creditClient;
        this.authClient = authClient;
    }

    /**
     * GET /api/ce-marketplace/{publicationId}/snapshot
     *
     * Returns the planSnapshot + metadata for a publication.
     * Free publications: returns snapshot directly.
     * Paid publications: returns 402 with creditsPerUse and acquireEndpoint.
     */
    @GetMapping("/{publicationId}/snapshot")
    public ResponseEntity<Map<String, Object>> getSnapshot(@PathVariable UUID publicationId) {

        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId).orElse(null);
        if (publication == null) {
            return ResponseEntity.notFound().build();
        }

        if (publication.getStatus() != PublicationStatus.ACTIVE) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "Publication is not active"));
        }
        if (publication.getVisibility() == PublicationVisibility.PRIVATE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Publication is private"));
        }

        // Paid publications: return 402 directing to acquire-with-auth endpoint
        if (publication.getCreditsPerUse() > 0) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of(
                            "error", "This publication requires payment",
                            "creditsPerUse", publication.getCreditsPerUse(),
                            "acquireEndpoint", "/api/ce-marketplace/" + publicationId + "/acquire-with-auth"
                    ));
        }

        // Free: return snapshot directly
        Map<String, Object> response = buildSnapshotResponse(publication);

        logger.info("CE snapshot download for free publication {}", publicationId);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/ce-marketplace/{publicationId}/acquire-with-auth
     *
     * Cloud-side endpoint for CE OAuth-linked accounts. The CE backend sends the
     * cloud access token (obtained from stored refresh token) as a Bearer token.
     * The gateway validates the JWT and injects X-User-ID.
     *
     * Flow:
     * 1. Validate publication exists, is active, is public/unlisted
     * 2. Check receipt: if user already purchased → return snapshot (free re-download)
     * 3. If creditsPerUse > 0 → call auth-service to deduct credits
     * 4. Save receipt on cloud side
     * 5. Return snapshot + metadata
     */
    @PostMapping("/{publicationId}/acquire-with-auth")
    public ResponseEntity<Map<String, Object>> acquireWithAuth(
            @PathVariable UUID publicationId,
            @RequestHeader("X-User-ID") Long userId) {

        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId).orElse(null);
        if (publication == null) {
            return ResponseEntity.notFound().build();
        }

        if (publication.getStatus() != PublicationStatus.ACTIVE) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "Publication is not active"));
        }
        if (publication.getVisibility() == PublicationVisibility.PRIVATE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Publication is private"));
        }

        // Post-V261 - resolve the CE customer's cloud-side default-personal
        // org so the receipt row carries a non-null organization_id (V261 NOT
        // NULL constraint on publication.publication_receipts). The CE→cloud
        // call only carries X-User-ID; the cloud auth-service resolves the
        // user's default-personal membership server-side.
        String cloudOrgId = authClient.getDefaultOrganizationIdForUser(userId.toString());
        if (cloudOrgId == null || cloudOrgId.isBlank()) {
            logger.warn("CE acquire: cannot resolve default org for cloud user {} - refusing receipt persist", userId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "cloud account has no default organization - sign in to the cloud portal first"));
        }

        boolean alreadyPurchased = receiptRepository.existsByOrganizationIdAndPublicationId(
                cloudOrgId, publicationId);
        if (alreadyPurchased) {
            logger.info("CE re-download: user {} (cloudOrg={}) already owns publication {}", userId, cloudOrgId, publicationId);
            Map<String, Object> response = buildSnapshotResponse(publication);
            response.put("alreadyOwned", true);
            return ResponseEntity.ok(response);
        }

        // Deduct credits if paid
        int creditsPerUse = publication.getCreditsPerUse();
        if (creditsPerUse > 0) {
            ResponseEntity<Map<String, Object>> creditResult = deductCredits(userId, publicationId, creditsPerUse);
            if (creditResult != null) {
                return creditResult; // error response (402, 500)
            }
        }

        // Save receipt on cloud side - stamp the resolved cloud org.
        PublicationReceiptEntity receipt = new PublicationReceiptEntity(
                userId.toString(), publicationId, creditsPerUse);
        receipt.setOrganizationId(cloudOrgId);
        receiptRepository.save(receipt);

        // Return snapshot
        Map<String, Object> response = buildSnapshotResponse(publication);
        response.put("creditsPaid", creditsPerUse);

        logger.info("CE acquire-with-auth: user {} acquired publication {} for {} credits",
                userId, publicationId, creditsPerUse);

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> buildSnapshotResponse(WorkflowPublicationEntity publication) {
        Map<String, Object> response = new HashMap<>();
        response.put("planSnapshot", publication.getPlanSnapshot());
        response.put("title", publication.getTitle());
        response.put("description", publication.getDescription());
        response.put("nodeIcons", publication.getNodeIcons());
        response.put("creditsPerUse", publication.getCreditsPerUse());
        response.put("publisherName", publication.getPublisherName());
        response.put("publicationId", publication.getId().toString());
        // Publication type + agent snapshot so a CE acquiring NON-workflow
        // publications (AGENT / TABLE / INTERFACE / SKILL) can clone the right
        // payload. Workflows + resources clone from planSnapshot (above); only
        // AGENT publications carry a separate agentSnapshot. The CE side
        // dispatches its clone on publicationType.
        response.put("publicationType",
                publication.getPublicationType() != null ? publication.getPublicationType().name() : "WORKFLOW");
        Map<String, Object> agentSnapshot = publication.getAgentSnapshot();
        if (agentSnapshot != null) {
            response.put("agentSnapshot", agentSnapshot);
        }
        return response;
    }

    /**
     * Deducts credits via the centralized CreditConsumptionClient.
     * Returns an error ResponseEntity if deduction fails, or null if successful.
     */
    private ResponseEntity<Map<String, Object>> deductCredits(Long userId, UUID publicationId, int creditsPerUse) {
        Map<String, Object> result = creditClient.consumeFixedCredits(
                userId.toString(), publicationId.toString(), creditsPerUse);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return null; // success
        }

        String error = String.valueOf(result.getOrDefault("error", "Credit deduction failed"));
        if (error.contains("402") || error.contains("Insufficient")) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "Insufficient credits", "required", creditsPerUse));
        }

        logger.error("Credit deduction failed for publication {}: {}", publicationId, error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Purchase processing failed"));
    }
}
