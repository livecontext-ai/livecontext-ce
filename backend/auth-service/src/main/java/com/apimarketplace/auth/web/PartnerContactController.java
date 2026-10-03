package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.PartnerContactService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/billing/partner/my-partner}: the partner the signed-in client came through, for
 * their messages ({@code partner: null} when there is none, so the list asks without a 404).
 * Cloud only: 503 when credits are unlimited (CE), like the rest of the partner program.
 */
@RestController
public class PartnerContactController {

    private final PartnerContactService service;
    private final boolean unlimited;

    public PartnerContactController(PartnerContactService service, @Value("${credit.unlimited:false}") boolean unlimited) {
        this.service = service;
        this.unlimited = unlimited;
    }

    @GetMapping("/api/billing/partner/my-partner")
    public ResponseEntity<Map<String, Object>> myPartner(@RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (unlimited) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("success", false, "error", "not_available_in_ce"));
        Long id = parseUserId(userId);
        if (id == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "error", "unauthorized"));
        Map<String, Object> out = new HashMap<>();
        out.put("partner", service.myPartner(id).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", String.valueOf(p.userId()));
            m.put("name", p.name());
            m.put("handle", p.handle());
            m.put("tier", p.partnerTier());
            return m;
        }).orElse(null));
        return ResponseEntity.ok(out);
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
