package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.SharedLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Public (unauthenticated) endpoint for resolving share tokens.
 * Gateway routes /share/** → /api/public/share/**
 */
@RestController
@RequestMapping("/api/public/share")
public class PublicShareResolverController {

    private static final Logger log = LoggerFactory.getLogger(PublicShareResolverController.class);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^sl_[a-f0-9]{32}$");

    private final SharedLinkService sharedLinkService;

    public PublicShareResolverController(SharedLinkService sharedLinkService) {
        this.sharedLinkService = sharedLinkService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> resolve(@PathVariable String token) {
        // Validate token format to avoid unnecessary DB queries and log injection
        if (!TOKEN_PATTERN.matcher(token).matches()) {
            return ResponseEntity.notFound().build();
        }

        try {
            return sharedLinkService.resolve(token)
                    .map(resolution -> ResponseEntity.ok((Object) resolution))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error resolving share token: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to resolve share token"));
        }
    }
}
