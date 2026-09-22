package com.apimarketplace.catalog.web.cloud;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.CeLinkEntitlementsResult;
import com.apimarketplace.catalog.domain.dto.CeCatalogRelayRequest;
import com.apimarketplace.catalog.service.relay.CeCatalogRelayService;

import com.apimarketplace.common.web.BillingContextHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cloud-side catalog relay for linked CE installs - mirrors the CE→cloud LLM
 * and web-search relays ({@code /api/ce-llm/*}, {@code /api/ce-websearch/*}):
 * validates that the calling cloud user owns an ACTIVE link to the given
 * install id AND holds an ACTIVE PAID subscription, then executes the catalog
 * tool locally with the cloud's own PLATFORM credential and bills the
 * per-call markup on the linked cloud account
 * (see {@link CeCatalogRelayService} for the reserve/commit/release lifecycle).
 *
 * <p>Every gate is fail-closed. An upstream API error is a valid relayed
 * result (200 + {@code success=false}), NOT a relay error - the CE surfaces it
 * to its user exactly like a local execution failure.
 *
 * <p>Gated off in the CE monolith ({@code ce-catalog-relay.enabled=false});
 * cloud default is on ({@code matchIfMissing=true}).
 */
@Slf4j
@RestController
@RequestMapping("/api/ce-catalog")
@ConditionalOnProperty(name = "ce-catalog-relay.enabled", havingValue = "true", matchIfMissing = true)
public class CloudCatalogRelayController {

    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private final AuthClient authClient;
    private final CeCatalogRelayService relayService;

    public CloudCatalogRelayController(AuthClient authClient,
                                       CeCatalogRelayService relayService) {
        this.authClient = authClient;
        this.relayService = relayService;
    }

    @PostMapping("/tools/{apiSlug}/{toolSlug}/execute")
    public ResponseEntity<?> execute(
            @RequestHeader(value = "X-User-ID", required = false) Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @PathVariable String apiSlug,
            @PathVariable String toolSlug,
            @RequestBody(required = false) CeCatalogRelayRequest request) {
        ResponseEntity<Map<String, Object>> authFailure = authorize(cloudUserId, installId);
        if (authFailure != null) {
            return authFailure;
        }
        CeLinkEntitlementsResult entitlements =
                authClient.ceLinkEntitlements(String.valueOf(cloudUserId), installId);
        if (!entitlements.hasSubscription()) {
            log.info("CE catalog relay refused (no subscription) cloudUser={} install={} tool={}/{} plan={}",
                    cloudUserId, installId, apiSlug, toolSlug, entitlements.planCode());
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "SUBSCRIPTION_REQUIRED"));
        }
        if (!relayService.tryAcquire(installId)) {
            log.info("CE catalog relay rate-limited cloudUser={} install={} tool={}/{}",
                    cloudUserId, installId, apiSlug, toolSlug);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "RATE_LIMITED"));
        }
        if (request == null
                || apiSlug == null || apiSlug.isBlank()
                || toolSlug == null || toolSlug.isBlank()
                || relayService.parametersTooLarge(request.getParameters())) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_RELAY_REQUEST"));
        }

        CeCatalogRelayService.RelayResult result =
                relayService.execute(cloudUserId, installId, apiSlug, toolSlug, request);
        // Audit line: identities + outcome + billed amount only - NEVER parameter values.
        log.info("CE catalog relay cloudUser={} install={} tool={}/{} outcome={} billedCredits={}",
                cloudUserId, installId, apiSlug, toolSlug, result.status(),
                result.billedCredits() != null ? result.billedCredits().toPlainString() : "0");
        return switch (result.status()) {
            case TOOL_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "TOOL_NOT_FOUND"));
            case OAUTH_NOT_RELAYABLE -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "OAUTH_NOT_RELAYABLE"));
            case PLATFORM_NOT_AVAILABLE -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "PLATFORM_NOT_AVAILABLE"));
            case INSUFFICIENT_CREDITS -> ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "INSUFFICIENT_CREDITS", "delinquent", result.delinquent()));
            // Returned as-is even when success=false: an upstream API error is a
            // valid relayed result, not a relay error.
            case OK -> ResponseEntity.ok(result.response());
        };
    }

    /**
     * Read-only availability probe: is this integration executable through the
     * relay, and at what markup? Gated on authentication + link ownership only;
     * the subscription state is INCLUDED in the body ({@code subscriptionActive})
     * so an unsubscribed CE can render its upsell instead of a hard 402.
     */
    @GetMapping("/platform-info/{integrationName}")
    public ResponseEntity<Map<String, Object>> platformInfo(
            @RequestHeader(value = "X-User-ID", required = false) Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @PathVariable String integrationName,
            @RequestParam(value = "apiToolId", required = false) UUID apiToolId,
            // V428: a generation is priced per model and per call size. Without
            // these the probe can only resolve an endpoint-wide row, which a
            // seeded generation never has, so it answered "not sold" for a
            // model the relay then executed and charged.
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "quantity", required = false) BigDecimal quantity,
            // What the call's own CHOICES do to the published rate. The EXECUTING
            // path measures this itself out of the body it is sent and charges
            // it; a probe that could not be told about it answered with the
            // unmodified rate, so the install quoted one amount and was billed
            // another for the same request, and neither end showed the
            // disagreement. Trusted no further than any other probe input: this
            // door only reads, and an install that understated it would misquote
            // a price to itself.
            @RequestParam(value = "priceMultiplier", required = false)
                    BigDecimal priceMultiplier) {
        ResponseEntity<Map<String, Object>> authFailure = authorize(cloudUserId, installId);
        if (authFailure != null) {
            return authFailure;
        }
        // Same per-install rate window as execute: the probe is cheap but unmetered,
        // so an unthrottled CE could hammer it without ever paying for an execution.
        if (!relayService.tryAcquire(installId)) {
            log.info("CE catalog relay platform-info rate-limited cloudUser={} install={} integration={}",
                    cloudUserId, installId, integrationName);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "RATE_LIMITED"));
        }
        CeLinkEntitlementsResult entitlements =
                authClient.ceLinkEntitlements(String.valueOf(cloudUserId), installId);
        CeCatalogRelayService.PlatformInfo info = relayService.platformInfo(
                integrationName, apiToolId, modelId, quantity, sanitizeMultiplier(priceMultiplier));
        log.info("CE catalog relay platform-info cloudUser={} install={} integration={} available={} relayEligible={} subscriptionActive={}",
                cloudUserId, installId, integrationName, info.available(), info.relayEligible(),
                entitlements.hasSubscription());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("integrationName", info.integrationName());
        body.put("available", info.available());
        body.put("platformCredentialId", info.platformCredentialId());
        body.put("hasPricing", info.hasPricing());
        body.put("markupCredits", info.markupCredits());
        body.put("subscriptionActive", entitlements.hasSubscription());
        body.put("relayEligible", info.relayEligible());
        // The factor this amount was quoted WITH, echoed as every other quote door echoes it.
        //
        // This was the one that did not, and the surfaces gate their whole explanation on the
        // echo: the badge beside the price, the "includes Resolution x2" sentence, the note in the
        // parameters menu. So on a self-hosted install linked to the cloud the factor WAS applied
        // to the amount and WAS charged, and the reader watched the price change when they picked
        // 1080p with nothing on screen saying why - which is the exact failure this feature was
        // built to remove, surviving on the one edition that cannot read the cloud's logs.
        //
        // Sanitised on the way in, so what is echoed is what was used, not what was asked.
        BigDecimal quotedWith = sanitizeMultiplier(priceMultiplier);
        if (quotedWith != null && quotedWith.compareTo(BigDecimal.ONE) != 0) {
            body.put("priceMultiplier", quotedWith);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Shared link-ownership check, mirroring the web-search relay: a populated
     * error response short-circuits; {@code null} means the caller owns an
     * active link to the install and the request may proceed.
     */
    @Nullable
    private ResponseEntity<Map<String, Object>> authorize(Long cloudUserId, String installId) {
        if (cloudUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "AUTHENTICATION_REQUIRED"));
        }
        if (!authClient.userOwnsActiveCeLink(String.valueOf(cloudUserId), installId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "CE_LINK_NOT_ACTIVE"));
        }
        return null;
    }

    /**
     * A price factor this probe is willing to quote with.
     *
     * <p>Dropped rather than refused when it is absurd, the same rule the cloud's
     * own quote endpoint applies. This door only READS: a malformed factor must
     * leave the install showing the published rate, which is the true price of a
     * call carrying no surcharge, instead of turning a price panel into an error
     * the reader cannot act on. The amount actually charged is measured again by
     * the executing leg out of the body it sends, so nothing arriving here can
     * buy anything or change anything that is billed.
     *
     * <p>Unsanitised, a non-positive value travelled to the auth leg, which
     * refuses it with a 400 - an error on a read, for a value the executing path
     * would simply have ignored - and a value in the millions travelled all the
     * way through to be quoted, clamped only by a maximum the published row may
     * not have. The ceiling is the descriptor parser's own, and the parser
     * enforces it on every modifier of a model TOGETHER, so a factor above it
     * cannot have come from any seed this platform accepts.
     */
    @Nullable
    private static BigDecimal sanitizeMultiplier(@Nullable BigDecimal raw) {
        return BillingContextHeaders.sanitizeGenerationMultiplier(raw);
    }
}
