package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the FileRef copy logic in {@code walkAndCopyFileRefs} /
 * {@code copyFileRefsInRunState}, exercised through the {@code publishWorkflow}
 * entry point. Covers cross-tenant copy, same-tenant copy, idempotency,
 * graceful degradation, and sourceTenantId omission.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("walkAndCopyFileRefs: showcase snapshot file copy")
class WorkflowPublicationServiceFileRefCopyTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationSnapshotVersionRepository snapshotVersionRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private PublicationReviewRepository reviewRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private AuthClient authClient;

    private WorkflowPublicationService service;

    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INTERFACE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PUBLISHER_TENANT = "8";
    private static final String FILE_OWNER_TENANT = "1";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository,
                snapshotVersionRepository,
                receiptRepository,
                reviewRepository,
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                breakdownService,
                new ObjectMapper(),
                snapshotCloneService,
                entitlementGuard,
                authClient);
        // Server-side publisher identity snapshot - every (re)publish path
        // calls AuthClient.getPublisherProfile. Lenient so non-publish tests
        // don't trip strict-stubbing.
        lenient().when(authClient.getPublisherProfile(any()))
                .thenReturn(new PublisherProfileDto(PUBLISHER_TENANT, "Test Publisher", "test@publisher.com", "test-avatar-uuid"));
    }

    // ========================================================================
    // Cross-tenant copy (the primary bug fix)
    // ========================================================================

    @Test
    @DisplayName("cross-tenant FileRef in runState is copied to _publications/ namespace")
    void crossTenantFileRefInRunStateIsCopied() {
        String foreignPath = FILE_OWNER_TENANT + "/general/catalog-binary/image.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/image.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(foreignPath);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());

        Map<String, Object> req = captor.getValue();
        assertThat(req).containsEntry("sourcePath", foreignPath);
        assertThat(req).containsEntry("tenantId", "_publications");
        assertThat(req).doesNotContainKey("sourceTenantId");
    }

    @Test
    @DisplayName("cross-tenant FileRef path is rewritten to new _publications/ path in snapshot")
    void crossTenantFileRefPathIsRewrittenInSnapshot() {
        String foreignPath = FILE_OWNER_TENANT + "/general/catalog-binary/image.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/image.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(foreignPath);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        // The snapshot stored on the entity should have the rewritten path
        Map<String, Object> stored = pub.getShowcaseSnapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) stored.get("runState");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRef = (Map<String, Object>) runState.get("profilePic");
        assertThat(fileRef.get("path")).isEqualTo(newPath);
    }

    // ========================================================================
    // Opaque `id` rewrite (the opaque-URL cutover bug fix)
    //
    // The opaque by-id file URL is built from the FileRef's `id`, not its
    // `path`. The re-uploaded file is a NEW storage row in the _publications
    // tenant, so the snapshot MUST adopt the new id; a FileRef left with the
    // SOURCE tenant's id renders 403/404 cross-tenant for the authenticated
    // snapshot preview.
    // ========================================================================

    @Test
    @DisplayName("FileRef opaque id is rewritten to the new storage-row id returned by copyFile")
    void crossTenantFileRefIdIsRewrittenToNewStorageRow() {
        String foreignPath = FILE_OWNER_TENANT + "/general/catalog-binary/image.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/image.jpg";
        String newId = "9aaaaaaa-0000-0000-0000-000000000001";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(foreignPath);
        // Stamp the source storage-row id on the incoming FileRef - it MUST be replaced.
        @SuppressWarnings("unchecked")
        Map<String, Object> incomingRef =
                (Map<String, Object>) ((Map<String, Object>) snapshot.get("runState")).get("profilePic");
        incomingRef.put("id", "1bbbbbbb-0000-0000-0000-000000000099");

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath, "newId", newId));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) pub.getShowcaseSnapshot().get("runState");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRef = (Map<String, Object>) runState.get("profilePic");
        assertThat(fileRef.get("path")).isEqualTo(newPath);
        assertThat(fileRef.get("id")).isEqualTo(newId);
    }

    @Test
    @DisplayName("stale source id is dropped when copyFile returns no newId")
    void staleSourceIdIsDroppedWhenCopyReturnsNoNewId() {
        String foreignPath = FILE_OWNER_TENANT + "/general/catalog-binary/image.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/image.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(foreignPath);
        @SuppressWarnings("unchecked")
        Map<String, Object> incomingRef =
                (Map<String, Object>) ((Map<String, Object>) snapshot.get("runState")).get("profilePic");
        incomingRef.put("id", "1bbbbbbb-0000-0000-0000-000000000099");

        stubPublishWorkflow(snapshot);
        // Legacy orchestrator response: newPath only, no newId.
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) pub.getShowcaseSnapshot().get("runState");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRef = (Map<String, Object>) runState.get("profilePic");
        assertThat(fileRef.get("path")).isEqualTo(newPath);
        // The stale source id would 403/404 cross-tenant - it must be gone, not kept.
        assertThat(fileRef).doesNotContainKey("id");
    }

    // ========================================================================
    // Same-tenant copy (regression check)
    // ========================================================================

    @Test
    @DisplayName("same-tenant FileRef is still copied (no regression)")
    void sameTenantFileRefIsCopied() {
        String sameTenantPath = PUBLISHER_TENANT + "/workflow/run/photo.png";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-xyz/photo.png";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(sameTenantPath);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        verify(orchestratorClient, atLeastOnce()).copyFile(any(), any());
    }

    // ========================================================================
    // Already-copied FileRef skipped (idempotency)
    // ========================================================================

    @Test
    @DisplayName("FileRef already in _publications/ namespace is not re-copied")
    void alreadyCopiedFileRefIsSkipped() {
        String alreadyCopiedPath = "_publications/" + PUBLICATION_ID + "/snapshot/file.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(alreadyCopiedPath);

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    // ========================================================================
    // Copy failure graceful degradation
    // ========================================================================

    @Test
    @DisplayName("copy failure preserves original path and does not abort publish")
    void copyFailurePreservesOriginalPath() {
        String originalPath = FILE_OWNER_TENANT + "/general/image.jpg";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(originalPath);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenThrow(new RuntimeException("S3 timeout"));

        // Should not throw - publish completes despite file copy failure
        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        // Original path preserved (not nulled, not replaced)
        Map<String, Object> stored = pub.getShowcaseSnapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> runState = (Map<String, Object>) stored.get("runState");
        @SuppressWarnings("unchecked")
        Map<String, Object> fileRef = (Map<String, Object>) runState.get("profilePic");
        assertThat(fileRef.get("path")).isEqualTo(originalPath);
    }

    // ========================================================================
    // sourceTenantId omission
    // ========================================================================

    @Test
    @DisplayName("copy request does not include sourceTenantId (inferred server-side)")
    void copyRequestOmitsSourceTenantId() {
        String path = FILE_OWNER_TENANT + "/general/photo.png";
        Map<String, Object> snapshot = snapshotWithRunStateFileRef(path);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", "_publications/x/photo.png"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());

        for (Map<String, Object> req : captor.getAllValues()) {
            assertThat(req).doesNotContainKey("sourceTenantId");
        }
    }

    // ========================================================================
    // interfaceRenders subtree walking
    // ========================================================================

    @Test
    @DisplayName("FileRef inside interfaceRenders items[].data is copied")
    void fileRefInInterfaceRenderItemsIsCopied() {
        String filePath = FILE_OWNER_TENANT + "/general/render-img.png";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-def/render-img.png";

        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", filePath);
        fileRef.put("name", "render-img.png");
        fileRef.put("mimeType", "image/png");

        Map<String, Object> data = new HashMap<>();
        data.put("image", fileRef);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<img src='{{image}}'>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);

        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", filePath);

        // Verify path was rewritten in the snapshot
        assertThat(fileRef.get("path")).isEqualTo(newPath);
    }

    @Test
    @DisplayName("FileRef inside interfaceRenders defaultRender items[].data is copied")
    void fileRefInDefaultRenderItemsIsCopied() {
        String filePath = FILE_OWNER_TENANT + "/general/default-render-img.png";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-default/default-render-img.png";

        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", filePath);
        fileRef.put("name", "default-render-img.png");
        fileRef.put("mimeType", "image/png");

        Map<String, Object> data = new HashMap<>();
        data.put("image", fileRef);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> defaultRender = new HashMap<>();
        defaultRender.put("items", List.of(item));
        defaultRender.put("htmlTemplate", "<img src='{{image}}'>");

        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("defaultRender", defaultRender);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", filePath);
        assertThat(fileRef.get("path")).isEqualTo(newPath);
    }

    @Test
    @DisplayName("AI screening replacement image is copied to _publications namespace and stored in snapshot")
    void aiReplacementImageIsCopiedAndStoredInSnapshot() {
        String externalUrl = "https://images.example.com/hotel.jpg";
        String generatedPath = PUBLISHER_TENANT + "/ai-generated/replacement.png";
        String publicationPath = "_publications/" + PUBLICATION_ID + "/snapshot/ai-replace/replacement.png";
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", Map.of("status", "COMPLETED"));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", publicationPath));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, true,
                Map.of(externalUrl, generatedPath));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).copyFile(captor.capture(), any());
        assertThat(captor.getValue())
                .containsEntry("sourcePath", generatedPath)
                .containsEntry("tenantId", "_publications")
                .containsEntry("workflowId", PUBLICATION_ID.toString())
                .containsEntry("runId", "snapshot")
                .containsEntry("fileName", "replacement.png")
                .containsEntry("mimeType", "image/png");

        @SuppressWarnings("unchecked")
        Map<String, String> replacements = (Map<String, String>) pub.getShowcaseSnapshot().get("imageReplacements");
        assertThat(replacements).containsEntry(externalUrl, publicationPath);
    }

    // ========================================================================
    // Multiple FileRefs in same snapshot
    // ========================================================================

    @Test
    @DisplayName("multiple FileRefs from different tenants are all copied")
    void multipleFileRefsFromDifferentTenantsAreCopied() {
        String path1 = FILE_OWNER_TENANT + "/general/img1.jpg";
        String path2 = "42/general/img2.jpg";

        Map<String, Object> fileRef1 = new HashMap<>();
        fileRef1.put("_type", "file");
        fileRef1.put("path", path1);
        fileRef1.put("name", "img1.jpg");

        Map<String, Object> fileRef2 = new HashMap<>();
        fileRef2.put("_type", "file");
        fileRef2.put("path", path2);
        fileRef2.put("name", "img2.jpg");

        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("file1", fileRef1);
        runState.put("file2", fileRef2);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", runState);

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/img1.jpg"))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/img2.jpg"));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        verify(orchestratorClient, times(2)).copyFile(any(), any());
    }

    // ========================================================================
    // Proxy URL → FileRef normalization
    // ========================================================================

    @Test
    @DisplayName("proxy URL string in interfaceRenders data is normalized to FileRef and copied")
    void proxyUrlInInterfaceRenderDataIsNormalizedAndCopied() {
        // Simulates the Instagram Profile Scraper scenario:
        // profilePic is stored as "/api/files/proxy?key=1%2F...%2Ffile.jpg&disposition=inline"
        String proxyUrl = "/api/files/proxy?key=1%2Fd1c0e41a%2Frun_123%2Fcore%3Adownload_avatar%2Fphoto.jpg&disposition=inline";
        String decodedKey = "1/d1c0e41a/run_123/core:download_avatar/photo.jpg";
        String newPath = "_publications/" + PUBLICATION_ID + "/snapshot/runout-abc/photo.jpg";

        Map<String, Object> data = new HashMap<>();
        data.put("profilePic", proxyUrl);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<img src='{{profilePic}}'>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any())).thenReturn(Map.of("newPath", newPath));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        // Verify the proxy URL was first normalized to FileRef, then copyFile was invoked
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getValue()).containsEntry("sourcePath", decodedKey);
    }

    @Test
    @DisplayName("JSON-encoded string field with embedded proxy URLs is normalized")
    void jsonEncodedStringWithProxyUrlsIsNormalized() {
        // postsJson field: a JSON array stored as a String, containing proxy URLs
        String postsJson = "[{\"image\":\"/api/files/proxy?key=1%2Frun%2Fstep%2Fpost1.jpg&disposition=inline\",\"caption\":\"Hello\"},"
                + "{\"image\":\"/api/files/proxy?key=1%2Frun%2Fstep%2Fpost2.jpg&disposition=inline\",\"caption\":\"World\"}]";
        String newPath1 = "_publications/" + PUBLICATION_ID + "/snapshot/runout-1/post1.jpg";
        String newPath2 = "_publications/" + PUBLICATION_ID + "/snapshot/runout-2/post2.jpg";

        Map<String, Object> data = new HashMap<>();
        data.put("postsJson", postsJson);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<div>{{postsJson}}</div>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", newPath1))
                .thenReturn(Map.of("newPath", newPath2));

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        // Both embedded proxy URLs should trigger file copies
        verify(orchestratorClient, times(2)).copyFile(any(), any());
    }

    @Test
    @DisplayName("non-proxy URL strings are NOT modified during normalization")
    void nonProxyUrlStringsAreNotModified() {
        Map<String, Object> data = new HashMap<>();
        data.put("username", "@instagram_user");
        data.put("bio", "Hello world");
        data.put("url", "https://example.com/profile");
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<p>{{username}}</p>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        // No file copy should be triggered - no proxy URLs and no FileRefs
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    @Test
    @DisplayName("proxy URL without key param is not normalized (graceful)")
    void proxyUrlWithoutKeyParamIsIgnored() {
        String badProxyUrl = "/api/files/proxy?disposition=inline";

        Map<String, Object> data = new HashMap<>();
        data.put("image", badProxyUrl);
        Map<String, Object> item = new HashMap<>();
        item.put("data", data);

        Map<String, Object> epochRender = new HashMap<>();
        epochRender.put("items", List.of(item));
        epochRender.put("htmlTemplate", "<img src='{{image}}'>");

        Map<String, Object> byEpoch = new HashMap<>();
        byEpoch.put("0", epochRender);
        Map<String, Object> ifaceEntry = new HashMap<>();
        ifaceEntry.put("byEpoch", byEpoch);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", Map.of("status", "COMPLETED"));
        snapshot.put("interfaceRenders", Map.of(INTERFACE_ID.toString(), ifaceEntry));

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        // Malformed proxy URL → no normalization → no copy
        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    // ========================================================================
    // Non-FileRef maps are not copied
    // ========================================================================

    @Test
    @DisplayName("map without _type=file is NOT treated as FileRef")
    void nonFileRefMapIsIgnored() {
        Map<String, Object> regularMap = new HashMap<>();
        regularMap.put("type", "file");  // wrong key: "type" not "_type"
        regularMap.put("path", FILE_OWNER_TENANT + "/general/not-a-file.txt");

        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("someData", regularMap);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", runState);

        stubPublishWorkflow(snapshot);

        service.publishWorkflow(
                WORKFLOW_ID, PUBLISHER_TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        verify(orchestratorClient, never()).copyFile(any(), any());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void stubPublishWorkflow(Map<String, Object> capturedSnapshot) {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", PUBLISHER_TENANT);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(),
                "interfaces", List.of(),
                "cores", List.of(),
                "edges", List.of())));

        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, PUBLISHER_TENANT, null))
                .thenReturn(workflowData);
        when(orchestratorClient.validateShowcaseRun("run-1", PUBLISHER_TENANT, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity pub = invocation.getArgument(0);
                    if (pub.getId() == null) {
                        pub.setId(PUBLICATION_ID);
                    }
                    return pub;
                });
        when(snapshotVersionRepository.getMaxVersion(PUBLICATION_ID)).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, PUBLISHER_TENANT)).thenReturn(1);
        when(orchestratorClient.captureShowcaseSnapshot("run-1", PUBLISHER_TENANT, null, null))
                .thenReturn(capturedSnapshot);
    }

    private Map<String, Object> snapshotWithRunStateFileRef(String filePath) {
        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", filePath);
        fileRef.put("name", extractFileName(filePath));
        fileRef.put("mimeType", "image/jpeg");

        Map<String, Object> runState = new HashMap<>();
        runState.put("status", "COMPLETED");
        runState.put("profilePic", fileRef);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("runState", runState);
        return snapshot;
    }

    private static String extractFileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : path;
    }
}
