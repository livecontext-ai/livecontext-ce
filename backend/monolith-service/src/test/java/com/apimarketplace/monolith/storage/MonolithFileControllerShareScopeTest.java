package com.apimarketplace.monolith.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.common.web.SharedApplicationScopeClient;
import com.apimarketplace.storage.service.file.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CE door of the share-link file binding: /api/files/by-id/{id}/raw under a share token serves
 * only the shared application's files, like the cloud storage-service door.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonolithFileController - share-link binding on /by-id/{id}/raw (CE)")
class MonolithFileControllerShareScopeTest {

    private static final String OWNER = "42";
    private static final String ORG = "org-9";
    private static final UUID PUB = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private FileStorageService fileStorageService;
    @Mock private PublicFileUrlBuilder publicFileUrlBuilder;
    @Mock private StorageService storageService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private SharedApplicationScopeClient scopeClient;

    private final UUID fileId = UUID.randomUUID();
    private MonolithFileController controller;

    @BeforeEach
    void setUp() {
        controller = new MonolithFileController(fileStorageService, publicFileUrlBuilder, storageService, orgAccessGuard,
                new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner(""),
                new com.apimarketplace.storage.util.MimeTypeRegistry(),
                new com.apimarketplace.storage.service.file.StorageStreamingMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        controller.sharedApplicationScopeClient = scopeClient;
        StorageEntity e = new StorageEntity();
        e.setId(fileId);
        e.setTenantId(OWNER);
        e.setFileName("note.txt");
        e.setMimeType("text/plain");
        e.setDataText("owner data");
        when(storageService.getEntityByIdForScope(fileId, OWNER, ORG)).thenReturn(Optional.of(e));
        when(orgAccessGuard.canAccess(ORG, OWNER, "file", fileId.toString(), null)).thenReturn(true);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void shareRequest(String type) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Share-Context", "true");
        req.addHeader("X-Share-Resource-Type", type);
        req.addHeader("X-Share-Resource-Token", PUB.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("share link: an owner file outside the shared application is a 404")
    void foreignFileIs404() {
        shareRequest("APPLICATION");
        when(scopeClient.fileBelongsToApplication(PUB, OWNER, ORG, null, null, fileId)).thenReturn(false);

        ResponseEntity<byte[]> r = controller.rawById(fileId, "inline", OWNER, ORG, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).isNull();
    }

    @Test
    @DisplayName("share link: a file of the shared application is served")
    void applicationFileIsServed() {
        shareRequest("APPLICATION");
        when(scopeClient.fileBelongsToApplication(PUB, OWNER, ORG, null, null, fileId)).thenReturn(true);

        ResponseEntity<byte[]> r = controller.rawById(fileId, "inline", OWNER, ORG, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("non-APPLICATION share: 404 without asking orchestrator")
    void nonApplicationShareIs404() {
        shareRequest("CHAT");

        assertThat(controller.rawById(fileId, "inline", OWNER, ORG, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(scopeClient);
    }

    @Test
    @DisplayName("no share context: the owner's read is unchanged")
    void ownerUnchanged() {
        assertThat(controller.rawById(fileId, "inline", OWNER, ORG, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verifyNoInteractions(scopeClient);
    }
}
