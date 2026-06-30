package com.apimarketplace.publication.controller;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.screening.ImageScreeningDecisionEntity;
import com.apimarketplace.publication.screening.ImageScreeningDecisionRepository;
import com.apimarketplace.publication.screening.ImageScreeningReport;
import com.apimarketplace.publication.screening.ImageScreeningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * REST surface for Wave 2a part 2 - pre-publish image screening.
 *
 * <p>Two endpoints:
 * <ol>
 *   <li>{@code POST /api/publications/pre-publish-scan} - given an
 *       interface ID, scan its templates and return the list of
 *       flagged external image URLs. No DB writes. The wizard calls
 *       this before committing to the publish flow so the publisher
 *       can decide actions per flagged image.</li>
 *   <li>{@code POST /api/publications/screening-decisions} - persist the
 *       publisher's decisions to the audit log. Append-only; one row per
 *       flagged URL. Required for the LCEN / DSA safe-harbor evidence
 *       trail (the {@code KEPT_ATTESTED} branch is what stands between
 *       LiveContext and a takedown lawsuit when a publisher swore they
 *       had rights).</li>
 * </ol>
 *
 * <p>This controller does NOT modify publications and does NOT block any
 * publish - that is by design per the v3 audit on Wave 2a ("never
 * auto-block" - auto-blocking converts LiveContext from "hébergeur" to
 * "éditeur" and forfeits the LCEN safe harbor).
 */
@RestController
@RequestMapping("/api/publications")
public class PublicationScreeningController {

    private static final Logger log = LoggerFactory.getLogger(PublicationScreeningController.class);

    /**
     * Version of the §8 attestation wording shown to the publisher when
     * they click "I have rights & publish". Bump when the CGU clause
     * changes so a row written today can be cross-referenced to the
     * exact wording the publisher saw at the time.
     */
    public static final String CURRENT_ATTESTATION_TEXT_VERSION = "tos-v1-2026-05-21";

    private final ImageScreeningService screeningService;
    private final ImageScreeningDecisionRepository decisionRepository;
    private final InterfaceClient interfaceClient;
    private final CreditConsumptionClient creditConsumptionClient;
    private final OrchestratorInternalClient orchestratorInternalClient;
    private final RestTemplate orchestratorRestTemplate;
    private final String orchestratorUrl;

    public PublicationScreeningController(ImageScreeningService screeningService,
                                          ImageScreeningDecisionRepository decisionRepository,
                                          InterfaceClient interfaceClient,
                                          CreditConsumptionClient creditConsumptionClient,
                                          OrchestratorInternalClient orchestratorInternalClient,
                                          @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        this.screeningService = screeningService;
        this.decisionRepository = decisionRepository;
        this.interfaceClient = interfaceClient;
        this.creditConsumptionClient = creditConsumptionClient;
        this.orchestratorInternalClient = orchestratorInternalClient;
        this.orchestratorUrl = orchestratorUrl;

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.orchestratorRestTemplate = new RestTemplate(factory);
    }

    @PostMapping("/pre-publish-scan")
    public ResponseEntity<?> prePublishScan(
            @RequestBody PrePublishScanRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        if (request == null || request.interfaceId == null || request.interfaceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "interfaceId is required"));
        }
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }

        UUID interfaceId;
        try {
            interfaceId = UUID.fromString(request.interfaceId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "interfaceId must be a UUID"));
        }

        InterfaceDto iface = interfaceClient.getInterface(interfaceId, userId);
        if (iface == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Interface not found"));
        }

        // Wave 2b - also scan the resolved render data (items[].data) of the
        // chosen showcase run so scraped CDN URLs / downloaded FileRefs are
        // surfaced, not just static template images. Best-effort: a render
        // failure leaves `items` empty and the scan degrades to template-only
        // (never blocks publish). runId is the source run picked in the wizard.
        List<Map<String, Object>> items = List.of();
        if (request.runId != null && !request.runId.isBlank()) {
            try {
                items = orchestratorInternalClient.renderInterfaceItemsForRun(
                        request.runId, interfaceId, request.epoch, userId, organizationId);
            } catch (Exception e) {
                log.warn("[PrePublishScan] item-data render failed for iface={} run={}: {}",
                        interfaceId, request.runId, e.getMessage());
            }
        }

        ImageScreeningReport report = screeningService.scan(
                iface.getHtmlTemplate(),
                iface.getCssTemplate(),
                iface.getJsTemplate(),
                items);

        Map<String, Object> response = new HashMap<>();
        response.put("clean", report.isClean());
        response.put("attestationTextVersion", CURRENT_ATTESTATION_TEXT_VERSION);
        response.put("flagged", report.flagged().stream().map(f -> Map.of(
                "url", f.url(),
                "source", f.source().name(),
                "urlHash", sha256Hex(f.url()),
                "host", hostOf(f.url())
        )).toList());
        response.put("aiReplacementCostPerImage", 3);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/screening-decisions")
    public ResponseEntity<?> postDecisions(
            @RequestBody DecisionsRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        if (request == null || request.publicationId == null || request.publicationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "publicationId is required"));
        }
        if (request.decisions == null || request.decisions.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "decisions must be a non-empty array"));
        }
        UUID publicationId;
        try {
            publicationId = UUID.fromString(request.publicationId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "publicationId must be a UUID"));
        }

        int persisted = 0;
        for (DecisionEntry d : request.decisions) {
            if (d == null || d.url == null || d.url.isBlank() || d.decision == null) continue;
            ImageScreeningDecisionEntity row = new ImageScreeningDecisionEntity();
            row.setPublicationId(publicationId);
            row.setSnapshotVersion(request.snapshotVersion != null ? request.snapshotVersion : 0);
            row.setDecidedBy(userId);
            row.setOrganizationId(organizationId);
            row.setImageUrlHash(sha256Hex(d.url));
            row.setImageUrlHost(hostOf(d.url));
            try {
                row.setImageSource(ImageScreeningDecisionEntity.ImageSource.valueOf(
                        d.source != null ? d.source.toUpperCase(Locale.ROOT) : "HTML"));
            } catch (IllegalArgumentException ex) {
                row.setImageSource(ImageScreeningDecisionEntity.ImageSource.HTML);
            }
            try {
                row.setDecision(ImageScreeningDecisionEntity.Decision.valueOf(
                        d.decision.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "decision must be one of REPLACED_STOCK, REPLACED_AI, REPLACED_UPLOAD, KEPT_ATTESTED, KEPT_OWN_UPLOAD, SKIPPED"));
            }
            row.setReplacementRef(d.replacementRef);
            row.setAttestationText(d.attestationText);
            row.setAttestationTextVersion(CURRENT_ATTESTATION_TEXT_VERSION);
            row.setUserAgent(userAgent);
            // ip_hash deliberately not populated - would need request IP +
            // GDPR-acceptable hashing strategy; deferred until needed.
            try {
                decisionRepository.save(row);
                persisted++;
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // uq_image_decision UNIQUE - caller resent the same decision; idempotent skip.
                log.debug("Skipped duplicate screening decision: pub={}, urlHash={}", publicationId, row.getImageUrlHash());
            }
        }
        return ResponseEntity.ok(Map.of("persisted", persisted));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JCE spec; unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String hostOf(String url) {
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            return host == null ? "unknown" : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException | NullPointerException e) {
            return "unknown";
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper UPSTREAM_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Unwrap the inner {@code message} field of an upstream error body so the
     * screening modal shows a clean sentence (e.g. "Stability AI platform
     * credential not configured") instead of the raw JSON envelope. Falls back
     * to the raw body when it isn't the expected shape.
     */
    private static String extractUpstreamMessage(String body) {
        if (body == null || body.isBlank()) return "Image generation failed";
        try {
            com.fasterxml.jackson.databind.JsonNode node = UPSTREAM_MAPPER.readTree(body);
            com.fasterxml.jackson.databind.JsonNode msg = node.get("message");
            if (msg != null && msg.isTextual() && !msg.asText().isBlank()) {
                return msg.asText();
            }
        } catch (Exception ignored) {
            // not JSON - return the raw body
        }
        return body;
    }

    @PostMapping("/screening/generate-replacement")
    public ResponseEntity<?> generateReplacement(
            @RequestBody GenerateReplacementRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        if (request == null || request.prompt == null || request.prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt is required"));
        }
        if (request.interfaceId == null || request.interfaceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "interfaceId is required"));
        }
        if (request.originalImageUrl == null || request.originalImageUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "originalImageUrl is required"));
        }

        if (organizationId == null || organizationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "organization_required",
                    "message", "Organization context is required for AI image generation"));
        }

        // Pre-flight credit check - reject before generating if user can't afford
        try {
            java.math.BigDecimal balance = creditConsumptionClient.fetchBalance(userId);
            if (balance != null && balance.compareTo(java.math.BigDecimal.valueOf(3)) < 0) {
                return ResponseEntity.status(402).body(Map.of(
                        "error", "insufficient_credits",
                        "message", "Insufficient credits for image generation",
                        "creditsCost", 3,
                        "creditsBalance", balance));
            }
        } catch (Exception e) {
            log.warn("Credit pre-flight check failed, proceeding anyway: {}", e.getMessage());
        }

        // Delegate to orchestrator-service for image generation + storage
        Map<String, Object> orchRequest = new HashMap<>();
        orchRequest.put("prompt", request.prompt);
        orchRequest.put("negativePrompt", request.negativePrompt);
        orchRequest.put("aspectRatio", request.aspectRatio);
        orchRequest.put("stylePreset", request.stylePreset);
        orchRequest.put("tenantId", userId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (organizationId != null) {
            headers.set("X-Organization-ID", organizationId);
        }
        headers.set("X-User-ID", userId);

        try {
            var response = orchestratorRestTemplate.exchange(
                    orchestratorUrl + "/api/internal/image-generation/screening-replace",
                    HttpMethod.POST,
                    new HttpEntity<>(orchRequest, headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            Map<String, Object> orchResult = response.getBody();
            if (orchResult == null || !orchResult.containsKey("storageKey")) {
                return ResponseEntity.status(502).body(Map.of("error", "generation_failed", "message", "No result from image generation"));
            }

            String storageKey = (String) orchResult.get("storageKey");

            // Debit credits (post-success only)
            String sourceId = "screening-replace:" + request.interfaceId + ":"
                    + sha256Hex(request.originalImageUrl) + ":" + sha256Hex(request.prompt);
            try {
                creditConsumptionClient.consumeCreditsAsync(userId, "IMAGE_GENERATION", sourceId,
                        "stability-ai", "stability-core", 1);
            } catch (Exception e) {
                log.error("BILLING DEFECT: Credit deduction failed for screening replacement (image stored, credits not deducted): userId={}, sourceId={}, error={}",
                        userId, sourceId, e.getMessage());
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "storageKey", storageKey,
                    "creditsCost", 3
            ));
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // Catch BOTH 4xx (HttpClientErrorException) AND 5xx
            // (HttpServerErrorException). The orchestrator returns 502 when the
            // Stability AI platform credential is not configured (common in
            // environments without an image-gen key) - previously the 5xx
            // escaped this catch and surfaced as a raw 500 to the publisher.
            // Now it degrades to a clean 502 with the upstream reason, so the
            // modal shows a real message and the publisher can upload instead.
            int status = e.getStatusCode().value();
            if (status == 422) {
                return ResponseEntity.status(422).body(Map.of("error", "content_filtered", "message", "Content filtered - try a different description"));
            }
            return ResponseEntity.status(502).body(Map.of("error", "generation_failed", "message", extractUpstreamMessage(e.getResponseBodyAsString())));
        } catch (org.springframework.web.client.ResourceAccessException e) {
            return ResponseEntity.status(502).body(Map.of("error", "generation_failed", "message", "Image generation service unavailable"));
        }
    }

    public static class GenerateReplacementRequest {
        public String interfaceId;
        public String originalImageUrl;
        public String prompt;
        public String negativePrompt;
        public String aspectRatio;
        public String stylePreset;
    }

    public static class PrePublishScanRequest {
        public String workflowId;
        public String runId;
        public String interfaceId;
        /** Optional pinned epoch - when set, scan only that epoch's render (matches what gets published). */
        public Integer epoch;
    }

    public static class DecisionsRequest {
        public String publicationId;
        public Integer snapshotVersion;
        public List<DecisionEntry> decisions;
    }

    public static class DecisionEntry {
        public String url;
        public String source;          // HTML / CSS / JS (case-insensitive)
        public String decision;        // REPLACED_STOCK / REPLACED_AI / KEPT_ATTESTED / KEPT_OWN_UPLOAD / SKIPPED
        public String replacementRef;  // optional: S3 key or stock id when REPLACED_*
        public String attestationText; // required when decision = KEPT_ATTESTED
    }
}
