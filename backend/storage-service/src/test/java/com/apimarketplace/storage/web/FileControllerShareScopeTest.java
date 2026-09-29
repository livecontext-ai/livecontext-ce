package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.common.web.SharedApplicationScopeClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.service.file.StorageStreamingMetrics;
import com.apimarketplace.storage.util.MimeTypeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A share token authenticates its holder AS THE OWNER. Before this binding, /api/files/by-id/{id}/raw
 * under a share link served ANY file of the owner's workspace.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileController - share-link binding on /by-id/{id}/raw")
class FileControllerShareScopeTest {

    private static final String OWNER = "58";
    private static final String ORG = "org-7";

    @Mock private FileStorageService fileStorageService;
    @Mock private MimeTypeRegistry mimeTypeRegistry;
    @Mock private StorageService storageIndex;
    @Mock private SharedApplicationScopeClient scopeClient;

    private FileController controller;
    private final UUID fileId = UUID.randomUUID();
    private final UUID sharedPublication = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new FileController(fileStorageService, mimeTypeRegistry, new TenantResolver(),
                new StorageStreamingMetrics(null),
                new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner(""),
                new PublicFileUrlBuilder("https://livecontext.ai"),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        controller.storageIndex = storageIndex;
        controller.sharedApplicationScopeClient = scopeClient;
    }

    private StorageEntity ownerFile(String runId, String workflowId, UUID sourcePublicationId) {
        StorageEntity e = new StorageEntity();
        e.setId(fileId);
        e.setTenantId(OWNER);
        e.setStorageType("TEXT");
        e.setFileName("secret.txt");
        e.setMimeType("text/plain");
        e.setDataText("owner data");
        e.setRunId(runId);
        e.setWorkflowId(workflowId);
        e.setSourcePublicationId(sourcePublicationId);
        return e;
    }

    private MockHttpServletRequest shareRequest(String type) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-ID", OWNER);
        req.addHeader("X-Organization-ID", ORG);
        req.addHeader("X-Share-Context", "true");
        req.addHeader("X-Share-Resource-Type", type);
        req.addHeader("X-Share-Resource-Token", sharedPublication.toString());
        return req;
    }

    @Test
    @DisplayName("share link: a file of another owner run is a 404")
    void shareForeignFileIs404() {
        when(storageIndex.getEntityByIdForScope(fileId, OWNER, ORG))
                .thenReturn(Optional.of(ownerFile("run_other", "wf-other", null)));
        when(scopeClient.fileBelongsToApplication(sharedPublication, OWNER, ORG, "run_other", "wf-other", fileId))
                .thenReturn(false);

        ResponseEntity<StreamingResponseBody> r = controller.rawById(fileId, "inline", shareRequest("APPLICATION"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).isNull();
    }

    @Test
    @DisplayName("share link: a file produced by the shared application's run is served")
    void shareApplicationRunFileIsServed() {
        when(storageIndex.getEntityByIdForScope(fileId, OWNER, ORG))
                .thenReturn(Optional.of(ownerFile("run_app", "wf-app", null)));
        when(scopeClient.fileBelongsToApplication(sharedPublication, OWNER, ORG, "run_app", "wf-app", fileId))
                .thenReturn(true);

        ResponseEntity<StreamingResponseBody> r = controller.rawById(fileId, "inline", shareRequest("APPLICATION"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("share link: an UNTAGGED file (catalog tool binary output) is checked by its id and served when referenced")
    void shareUntaggedFileCheckedById() {
        when(storageIndex.getEntityByIdForScope(fileId, OWNER, ORG))
                .thenReturn(Optional.of(ownerFile(null, null, null)));
        when(scopeClient.fileBelongsToApplication(sharedPublication, OWNER, ORG, null, null, fileId))
                .thenReturn(true);

        ResponseEntity<StreamingResponseBody> r = controller.rawById(fileId, "inline", shareRequest("APPLICATION"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("share link: a file tagged with the shared publication itself is served without a remote check")
    void sharePublicationTaggedFileIsServed() {
        when(storageIndex.getEntityByIdForScope(fileId, OWNER, ORG))
                .thenReturn(Optional.of(ownerFile(null, null, sharedPublication)));

        ResponseEntity<StreamingResponseBody> r = controller.rawById(fileId, "inline", shareRequest("APPLICATION"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(scopeClient);
    }

    @Test
    @DisplayName("non-APPLICATION share context is a 404 without a remote check")
    void nonApplicationShareIs404() {
        when(storageIndex.getEntityByIdForScope(fileId, OWNER, ORG))
                .thenReturn(Optional.of(ownerFile("run_app", "wf-app", sharedPublication)));

        ResponseEntity<StreamingResponseBody> r = controller.rawById(fileId, "inline", shareRequest("CHAT"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(scopeClient);
    }

    @Test
    @DisplayName("no share context: the owner still reads any of their files")
    void ownerReadUnchanged() {
        when(storageIndex.getEntityByIdForScope(fileId, OWNER, ORG))
                .thenReturn(Optional.of(ownerFile("run_other", null, null)));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-ID", OWNER);
        req.addHeader("X-Organization-ID", ORG);

        ResponseEntity<StreamingResponseBody> r = controller.rawById(fileId, "inline", req);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(scopeClient);
    }

    @Test
    @DisplayName("share link: no signed link is handed out for a file outside the shared application")
    void shareForeignFileGetsNoSignedLink() {
        FileController signing = new FileController(fileStorageService, mimeTypeRegistry, new TenantResolver(),
                new StorageStreamingMetrics(null),
                new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("test-secret"),
                new PublicFileUrlBuilder("https://livecontext.ai"),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        signing.storageIndex = storageIndex;
        signing.sharedApplicationScopeClient = scopeClient;
        StorageEntity file = ownerFile("run_other", "wf-other", null);
        file.setS3Key(OWNER + "/general/general/secret.mp4");
        when(storageIndex.getEntityByIdForScope(fileId, OWNER, ORG)).thenReturn(Optional.of(file));
        when(scopeClient.fileBelongsToApplication(sharedPublication, OWNER, ORG, "run_other", "wf-other", fileId))
                .thenReturn(false);

        var r = signing.signedUrlById(fileId, "inline", shareRequest("APPLICATION"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).isNull();
    }
}
