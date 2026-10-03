package com.apimarketplace.publication.service;

import com.apimarketplace.publication.controller.InternalPartnerOfferAppsController;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("The applications a partner may give with an offer")
class PartnerOfferAppsServiceTest {

    private static final String PARTNER = "42";

    private WorkflowPublicationRepository repository;
    private PartnerOfferAppsService service;
    private final List<WorkflowPublicationEntity> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowPublicationRepository.class);
        service = new PartnerOfferAppsService(repository);
        stored.clear();
        when(repository.findAllById(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return stored.stream().filter(p -> ids.contains(p.getId())).toList();
        });
    }

    private WorkflowPublicationEntity app(Consumer<WorkflowPublicationEntity> tweak) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setTitle("Invoice chaser");
        pub.setPublisherId(PARTNER);
        pub.setPublisherName("Northwind Studio");
        pub.setPublicationType(PublicationType.WORKFLOW);
        pub.setDisplayMode(DisplayMode.APPLICATION);
        pub.setStatus(PublicationStatus.ACTIVE);
        pub.setVisibility(PublicationVisibility.PUBLIC);
        tweak.accept(pub);
        stored.add(pub);
        return pub;
    }

    private WorkflowPublicationEntity app() {
        return app(p -> {});
    }

    @Test
    @DisplayName("the partner's own active applications, public or unlisted, come back in the order asked")
    void offerableInOrder() {
        WorkflowPublicationEntity unlisted = app(p -> p.setVisibility(PublicationVisibility.UNLISTED));
        WorkflowPublicationEntity listed = app();

        assertThat(service.offerable(PARTNER, List.of(unlisted.getId(), listed.getId())))
                .containsExactly(unlisted, listed);
        assertThat(service.offerable(PARTNER, List.of(listed.getId(), unlisted.getId())))
                .containsExactly(listed, unlisted);
    }

    @Test
    @DisplayName("anything a client holding the link could not see or install is left out")
    void notOfferable() {
        WorkflowPublicationEntity good = app();
        List<UUID> bad = List.of(
                app(p -> p.setPublisherId("7")).getId(),                               // someone else's
                app(p -> p.setVisibility(PublicationVisibility.PRIVATE)).getId(),      // no preview without an account
                app(p -> p.setStatus(PublicationStatus.INACTIVE)).getId(),
                app(p -> p.setStatus(PublicationStatus.PENDING_REVIEW)).getId(),
                app(p -> p.setStatus(PublicationStatus.REJECTED)).getId(),
                app(p -> p.setDisplayMode(DisplayMode.WORKFLOW)).getId(),              // a template, not an app
                app(p -> p.setPublicationType(PublicationType.AGENT)).getId(),
                app(p -> p.setCeExclusive(true)).getId(),                              // the cloud cannot install it
                UUID.randomUUID());                                                    // unknown

        List<UUID> asked = new ArrayList<>(bad);
        asked.add(good.getId());
        assertThat(service.offerable(PARTNER, asked)).containsExactly(good);
    }

    @Test
    @DisplayName("each app once, at most ten, and nothing without a publisher or ids")
    void boundsAndBlanks() {
        WorkflowPublicationEntity one = app();
        assertThat(service.offerable(PARTNER, List.of(one.getId(), one.getId()))).containsExactly(one);

        List<UUID> twelve = IntStream.range(0, 12).mapToObj(i -> app().getId()).toList();
        assertThat(service.offerable(PARTNER, twelve)).hasSize(PartnerOfferAppsService.MAX_APPS)
                .extracting(WorkflowPublicationEntity::getId).containsExactlyElementsOf(twelve.subList(0, 10));

        assertThat(service.offerable(" ", List.of(one.getId()))).isEmpty();
        assertThat(service.offerable(null, List.of(one.getId()))).isEmpty();
        assertThat(service.offerable(PARTNER, List.of())).isEmpty();
        assertThat(service.offerable(PARTNER, null)).isEmpty();
    }

    @Test
    @DisplayName("the endpoint skips malformed ids and draws each app with the card's fields only")
    void endpoint() {
        WorkflowPublicationEntity pub = app(p -> {
            p.setShowcaseRunId("run-1");
            p.setShowcaseInterfaceId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
            p.setNodeIcons(List.of(Map.of("slug", "gmail")));
            p.setCeExclusiveFeatures(List.of("VECTOR_SEARCH"));
            p.setInterfaceCount(3);
        });
        var controller = new InternalPartnerOfferAppsController(service);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apps = (List<Map<String, Object>>) controller
                .offerable(Map.of("publisherId", PARTNER, "ids", List.of("not-a-uuid", 12, pub.getId().toString())))
                .getBody().get("apps");

        assertThat(apps).singleElement().satisfies(card -> {
            assertThat(card).containsOnlyKeys("id", "title", "description", "publisherId", "publisherName",
                    "nodeIcons", "visibility", "showcaseRunId", "showcaseInterfaceId", "features", "interfaceCount");
            assertThat(card).containsEntry("id", pub.getId().toString()).containsEntry("publisherId", PARTNER)
                    .containsEntry("publisherName", "Northwind Studio").containsEntry("visibility", "PUBLIC")
                    .containsEntry("showcaseRunId", "run-1")
                    .containsEntry("showcaseInterfaceId", "00000000-0000-0000-0000-0000000000aa")
                    .containsEntry("nodeIcons", List.of(Map.of("slug", "gmail")))
                    // What auth-service checks against the offer's plan.
                    .containsEntry("features", List.of("VECTOR_SEARCH"))
                    // What auth-service counts against the plan's interface quota.
                    .containsEntry("interfaceCount", 3);
        });
    }

    @Test
    @DisplayName("regression: an unlisted app's card carries no showcase run, so it draws the landing snapshot a visitor may read, not a live screen refused to them")
    void unlistedCardHasNoLiveScreen() {
        WorkflowPublicationEntity unlisted = app(p -> {
            p.setVisibility(PublicationVisibility.UNLISTED);
            p.setShowcaseRunId("run-1");
            p.setShowcaseInterfaceId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        });

        @SuppressWarnings("unchecked")
        Map<String, Object> card = ((List<Map<String, Object>>) new InternalPartnerOfferAppsController(service)
                .offerable(Map.of("publisherId", PARTNER, "ids", List.of(unlisted.getId().toString())))
                .getBody().get("apps")).get(0);

        assertThat(card).containsEntry("visibility", "UNLISTED").containsEntry("showcaseRunId", null)
                .containsEntry("showcaseInterfaceId", null);
    }

    @Test
    @DisplayName("an empty or missing body answers no apps without reading the store")
    void emptyBody() {
        var controller = new InternalPartnerOfferAppsController(service);

        assertThat(controller.offerable(null).getBody()).containsEntry("apps", List.of());
        assertThat(controller.offerable(Map.of("publisherId", PARTNER)).getBody()).containsEntry("apps", List.of());
        verify(repository, never()).findAllById(anyCollection());
    }
}
