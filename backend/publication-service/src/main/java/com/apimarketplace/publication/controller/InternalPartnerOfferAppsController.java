package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.service.PartnerOfferAppsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal: the applications a partner may give with an offer (called by auth-service, which owns
 * the offers). {@code POST /api/internal/publications/partner-offer-apps} with
 * {@code {"publisherId": "42", "ids": ["<uuid>", ...]}} answers {@code {"apps": [...]}}: the
 * offerable ones among {@code ids}, in the order asked, each as the few fields a marketplace card
 * draws (title, description, publisher, integration icons, showcase run). Ids that are malformed or
 * not offerable are left out; the caller compares.
 */
@RestController
public class InternalPartnerOfferAppsController {

    private final PartnerOfferAppsService service;

    public InternalPartnerOfferAppsController(PartnerOfferAppsService service) {
        this.service = service;
    }

    @PostMapping("/api/internal/publications/partner-offer-apps")
    public ResponseEntity<Map<String, Object>> offerable(@RequestBody(required = false) Map<String, Object> body) {
        Object publisher = body == null ? null : body.get("publisherId");
        List<UUID> ids = new ArrayList<>();
        if (body != null && body.get("ids") instanceof List<?> raw) {
            for (Object id : raw) {
                if (!(id instanceof String s)) continue;
                try {
                    ids.add(UUID.fromString(s.trim()));
                } catch (IllegalArgumentException ignored) {
                    // Not an id: it cannot be offerable, and the caller sees it missing.
                }
            }
        }
        List<Map<String, Object>> apps = service.offerable(publisher == null ? null : publisher.toString(), ids)
                .stream().map(InternalPartnerOfferAppsController::card).toList();
        return ResponseEntity.ok(Map.of("apps", apps));
    }

    /** What the offer page's card needs, and nothing the public marketplace card does not already show. */
    static Map<String, Object> card(WorkflowPublicationEntity pub) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pub.getId().toString());
        m.put("title", pub.getTitle());
        m.put("description", pub.getDescription());
        m.put("publisherId", pub.getPublisherId());
        m.put("publisherName", pub.getPublisherName());
        m.put("nodeIcons", pub.getNodeIcons() == null ? List.of() : pub.getNodeIcons());
        m.put("visibility", pub.getVisibility() == null ? null : pub.getVisibility().name());
        // The plan-gated capabilities the app uses (VECTOR_SEARCH...): an offer on a plan without
        // them could never install it.
        m.put("features", pub.getCeExclusiveFeatures());
        // How many interfaces an install creates: they count against the client's plan quota.
        m.put("interfaceCount", pub.getInterfaceCount() == null ? 0 : pub.getInterfaceCount());
        // The live screen is served to a visitor without an account for a PUBLIC app only (the
        // anonymous showcase render refuses the rest): an unlisted app's card draws its landing
        // snapshot instead, which an unlisted app may show to anyone holding its link.
        boolean liveScreen = pub.getVisibility() == WorkflowPublicationEntity.PublicationVisibility.PUBLIC;
        m.put("showcaseRunId", liveScreen ? pub.getShowcaseRunId() : null);
        m.put("showcaseInterfaceId", liveScreen && pub.getShowcaseInterfaceId() != null ? pub.getShowcaseInterfaceId().toString() : null);
        return m;
    }
}
