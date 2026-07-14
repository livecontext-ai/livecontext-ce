package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.avatar.AvatarGenerationService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * One-shot AI avatar generation for agents.
 *
 * <p>Deliberately agent-independent (no {agentId} in the path): the create-agent modal
 * generates an avatar BEFORE the agent row exists. The endpoint returns sanitized SVG
 * markup only; persistence happens through the regular avatar upload when the user
 * accepts the preview.
 */
@RestController
@RequestMapping("/api/agents/avatar")
public class AvatarGenerationController {

    private static final Logger logger = LoggerFactory.getLogger(AvatarGenerationController.class);

    private final AvatarGenerationService avatarGenerationService;
    private final TenantResolver tenantResolver;

    public AvatarGenerationController(AvatarGenerationService avatarGenerationService,
                                      TenantResolver tenantResolver) {
        this.avatarGenerationService = avatarGenerationService;
        this.tenantResolver = tenantResolver;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateAvatar(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);

        String prompt = stringOf(request.get("prompt"));
        String provider = stringOf(request.get("provider"));
        String model = stringOf(request.get("model"));

        try {
            // Org scope: credential/link resolution downstream reads the active
            // workspace from the request-bound TenantResolver context - same
            // contract as the json-completion endpoint.
            String orgId = tenantResolver.resolveOrgId(httpRequest);
            java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>();
            TenantResolver.runWithOrgScope(orgId, () ->
                    result.set(avatarGenerationService.generate(prompt, provider, model, tenantId)));
            return ResponseEntity.ok(Map.of("svg", result.get()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "avatar_generation_invalid",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("AI avatar generation failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "avatar_generation_failed",
                    "message", "Avatar generation failed - try again or pick a preset"
            ));
        }
    }

    private static String stringOf(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
