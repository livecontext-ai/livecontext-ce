package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The applications a partner may give with an offer (auth-service's partner offers): their own
 * applications that anyone holding the offer link may see and install. That is an ACTIVE
 * application published PUBLIC or UNLISTED (the visibilities whose card a visitor without an
 * account can see: a public app with its live screen, an unlisted one with its landing snapshot),
 * by the partner, and runnable on this deployment. PRIVATE apps are left out: the offer page could
 * not draw them, and an unlisted app is exactly "anyone with the link". Shareable visibilities also
 * guarantee no custom API rides along (the publish gate refuses one on a PUBLIC or UNLISTED app),
 * so the client can run what they receive.
 */
@Service
public class PartnerOfferAppsService {

    /** How many applications one offer carries: a client's starter kit, not a catalogue. */
    public static final int MAX_APPS = 10;

    private final WorkflowPublicationRepository publications;

    public PartnerOfferAppsService(WorkflowPublicationRepository publications) {
        this.publications = publications;
    }

    /**
     * The applications among {@code ids} the publisher may give with an offer, in the order asked,
     * each once. Anything else (unknown, someone else's, private, inactive, not an application) is
     * dropped silently: the caller compares what came back with what it asked for.
     */
    @Transactional(readOnly = true)
    public List<WorkflowPublicationEntity> offerable(String publisherId, List<UUID> ids) {
        if (publisherId == null || publisherId.isBlank() || ids == null || ids.isEmpty()) return List.of();
        List<UUID> wanted = new ArrayList<>(new LinkedHashSet<>(ids));
        if (wanted.size() > MAX_APPS) wanted = wanted.subList(0, MAX_APPS);
        Map<UUID, WorkflowPublicationEntity> found = publications.findAllById(wanted).stream()
                .collect(Collectors.toMap(WorkflowPublicationEntity::getId, Function.identity(), (a, b) -> a));
        List<WorkflowPublicationEntity> out = new ArrayList<>();
        for (UUID id : wanted) {
            WorkflowPublicationEntity pub = found.get(id);
            if (pub != null && isOfferable(pub, publisherId.trim())) out.add(pub);
        }
        return out;
    }

    static boolean isOfferable(WorkflowPublicationEntity pub, String publisherId) {
        return publisherId.equals(pub.getPublisherId())
                && pub.getPublicationType() == PublicationType.WORKFLOW
                && pub.getDisplayMode() == DisplayMode.APPLICATION
                && pub.getStatus() == PublicationStatus.ACTIVE
                && (pub.getVisibility() == PublicationVisibility.PUBLIC || pub.getVisibility() == PublicationVisibility.UNLISTED)
                // Managed cloud refuses to install a self-hosted-only app: never promise one.
                && !pub.isCeExclusive();
    }
}
