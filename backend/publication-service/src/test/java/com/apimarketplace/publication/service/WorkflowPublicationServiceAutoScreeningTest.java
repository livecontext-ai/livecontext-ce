package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.screening.ImageScreeningDecisionEntity;
import com.apimarketplace.publication.screening.ImageScreeningDecisionRepository;
import com.apimarketplace.publication.screening.ImageScreeningService;
import com.apimarketplace.publication.screening.ItemDataResourceExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V274 audit-gap closer regression. Pre-fix, only the wizard path
 * persisted decision rows - MCP / scripted / S2S publishes silently
 * shipped without any audit trail, leaving a forensic blind spot if a
 * takedown notice ever landed on a non-wizard publication.
 *
 * <p>The post-fix {@code captureAndStoreShowcaseSnapshot} hook walks the
 * captured snapshot's interfaceRenders and writes one SKIPPED row per
 * flagged media URL when {@code viaScreeningWizard=false}.
 *
 * <p>This test exercises the private {@code autoScreenSnapshot} method
 * directly (via reflection) because the wider
 * {@code captureAndStoreShowcaseSnapshot} flow needs the orchestrator
 * client + repository wiring that the 13-arg WorkflowPublicationService
 * constructor doesn't expose for partial mock. The reflection avoids
 * building a 30-mock test fixture for an audit-trail invariant.
 */
@DisplayName("WorkflowPublicationService - V274 auto-screening on non-wizard publishes")
class WorkflowPublicationServiceAutoScreeningTest {

    private ImageScreeningDecisionRepository decisionRepository;
    private WorkflowPublicationService service;

    @BeforeEach
    void setUp() throws Exception {
        decisionRepository = mock(ImageScreeningDecisionRepository.class);
        ImageScreeningService screeningService =
                new ImageScreeningService(new ItemDataResourceExtractor(new ObjectMapper()));

        // Construct with all 13 mandatory deps as nulls; only the screening
        // beans matter for this test. The autoScreenSnapshot method only
        // touches publicationRepository (no-op), publication entity setters,
        // and the screening pair.
        service = new WorkflowPublicationService(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null);

        injectField("imageScreeningService", screeningService);
        injectField("imageScreeningDecisionRepository", decisionRepository);
    }

    @Test
    @DisplayName("autoScreenSnapshot writes one SKIPPED row per flagged media URL - every publish has audit-trail coverage")
    void autoScreenWritesSkippedRowsForFlagged() throws Exception {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setSnapshotVersion(1);

        Map<String, Object> defaultRender = new LinkedHashMap<>();
        defaultRender.put("htmlTemplate",
                "<img src=\"https://a0.muscache.com/photo.jpg\">"
                + "<video src=\"/api/files/proxy-signed?id=intro\"></video>");
        defaultRender.put("cssTemplate", "");
        defaultRender.put("jsTemplate", "");

        Map<String, Object> ifaceRender = Map.of("defaultRender", defaultRender);
        Map<String, Object> snapshot = Map.of(
                "interfaceRenders", Map.of("iface_a", ifaceRender));

        invokeAutoScreen(pub, snapshot, "tenant-7");

        verify(decisionRepository, times(2)).save(any(ImageScreeningDecisionEntity.class));
    }

    @Test
    @DisplayName("autoScreenSnapshot also writes rows for images in items[].data (scraped URL + downloaded FileRef), not just the template")
    void autoScreenCoversItemDataImages() throws Exception {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setSnapshotVersion(1);

        Map<String, Object> defaultRender = new LinkedHashMap<>();
        defaultRender.put("htmlTemplate", "<img src=\"{{profilePicUrl}}\">"); // placeholder only
        defaultRender.put("cssTemplate", "");
        defaultRender.put("jsTemplate", "");
        Map<String, Object> fileRef = Map.of(
                "_type", "file", "path", "_publications/p/snapshot/abc_472_n.jpg", "mimeType", "image/jpeg");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profilePicUrl", fileRef);
        data.put("displayUrl", "https://scontent.cdninstagram.com/v/t51/x_n.jpg?stp=y");
        defaultRender.put("items", List.of(Map.of("data", data)));

        Map<String, Object> snapshot = Map.of(
                "interfaceRenders", Map.of("iface_a", Map.of("defaultRender", defaultRender)));

        org.mockito.ArgumentCaptor<ImageScreeningDecisionEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ImageScreeningDecisionEntity.class);
        when(decisionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        invokeAutoScreen(pub, snapshot, "tenant-7");

        // Two data images flagged (FileRef path + scraped URL); the {{placeholder}}
        // template img has host "unknown" and is also written - assert the two
        // DATA-source rows are present.
        assertThat(captor.getAllValues())
                .filteredOn(r -> r.getImageSource() == ImageScreeningDecisionEntity.ImageSource.DATA)
                .hasSize(2);
    }

    @Test
    @DisplayName("autoScreenSnapshot no-ops when the snapshot has no interface renders")
    void autoScreenNoOpOnEmptyRenders() throws Exception {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        Map<String, Object> snapshot = Map.of("interfaceRenders", Map.of());

        invokeAutoScreen(pub, snapshot, "tenant-7");

        verify(decisionRepository, never()).save(any());
    }

    @Test
    @DisplayName("autoScreenSnapshot no-ops when the screening beans are null (test-fixture path)")
    void autoScreenNoOpOnNullBeans() throws Exception {
        injectField("imageScreeningService", null);
        injectField("imageScreeningDecisionRepository", null);

        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        Map<String, Object> defaultRender = Map.of("htmlTemplate",
                "<img src=\"https://a0.muscache.com/x.jpg\">");
        Map<String, Object> snapshot = Map.of(
                "interfaceRenders", Map.of("iface", Map.of("defaultRender", defaultRender)));

        // Must not throw.
        invokeAutoScreen(pub, snapshot, "tenant-7");

        verify(decisionRepository, never()).save(any());
    }

    @Test
    @DisplayName("autoScreenSnapshot writes ORG owner_id when publication is ORG-owned; null otherwise")
    void autoScreenWritesOrgWhenOrgOwned() throws Exception {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setOwnerType(WorkflowPublicationEntity.OwnerType.ORG);
        pub.setOwnerId("org-99");

        Map<String, Object> defaultRender = Map.of("htmlTemplate",
                "<img src=\"https://x.com/y.jpg\">");
        Map<String, Object> snapshot = Map.of(
                "interfaceRenders", Map.of("iface", Map.of("defaultRender", defaultRender)));

        org.mockito.ArgumentCaptor<ImageScreeningDecisionEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ImageScreeningDecisionEntity.class);
        when(decisionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        invokeAutoScreen(pub, snapshot, "tenant-7");

        List<ImageScreeningDecisionEntity> saved = captor.getAllValues();
        assertThat(saved).hasSize(1);
        ImageScreeningDecisionEntity row = saved.get(0);
        assertThat(row.getOrganizationId())
                .as("ORG-owned publication forwards owner_id into the audit row for org-scoped takedown queries")
                .isEqualTo("org-99");
        assertThat(row.getDecidedBy()).isEqualTo("tenant-7");
        assertThat(row.getDecision()).isEqualTo(ImageScreeningDecisionEntity.Decision.SKIPPED);
        assertThat(row.getAttestationText())
                .as("Auto-scan never claims rights on the publisher's behalf - attestation_text MUST be null")
                .isNull();
        assertThat(row.getImageUrlHash()).hasSize(64);
    }

    private void invokeAutoScreen(WorkflowPublicationEntity pub,
                                   Map<String, Object> snapshot,
                                   String tenantId) throws Exception {
        Method m = WorkflowPublicationService.class.getDeclaredMethod(
                "autoScreenSnapshot",
                WorkflowPublicationEntity.class, Map.class, String.class);
        m.setAccessible(true);
        m.invoke(service, pub, snapshot, tenantId);
    }

    private void injectField(String name, Object value) throws Exception {
        java.lang.reflect.Field f = WorkflowPublicationService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }
}
