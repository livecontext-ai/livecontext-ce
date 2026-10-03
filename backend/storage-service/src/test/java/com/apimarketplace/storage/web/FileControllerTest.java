package com.apimarketplace.storage.web;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.storage.domain.FileRef;
import com.apimarketplace.storage.service.file.DownloadStream;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.service.file.StorageStreamingMetrics;
import com.apimarketplace.storage.util.MimeTypeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the opaque-URL serving surface of {@link FileController} after the cutover:
 * the {@code /by-id/{id}/raw} streaming endpoint (org-scoped, addressed by the storage-row UUID -
 * the s3 key never appears in the request or response) and the generic upload, which returns the
 * opaque {@code id} + {@code url} so the frontend renders without ever holding a key. The legacy
 * key-based serve ({@code /proxy?key=}), the key→id {@code /resolve} bridge, and the presigned
 * {@code /download-url} / {@code /download} endpoints were removed in the opaque cutover.
 */
@DisplayName("FileController - opaque by-id serve + generic upload")
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock FileStorageService fileStorageService;
    @Mock MimeTypeRegistry mimeTypeRegistry;
    @Mock TenantResolver tenantResolver;
    @Mock HttpServletRequest request;
    @Mock com.apimarketplace.common.storage.service.StorageService storageIndex;
    @Mock OrgAccessGuard orgAccessGuard;

    /** No MeterRegistry → counters are no-ops, gauge still increments via AtomicLong. */
    private final StorageStreamingMetrics streamingMetrics = new StorageStreamingMetrics(null);
    private final PublicFileUrlBuilder urlBuilder = new PublicFileUrlBuilder("https://livecontext.ai");

    private FileController controller;

    private static final String OWN_TENANT = "58";
    private static final String OTHER_TENANT = "999";

    @BeforeEach
    void setUp() {
        com.apimarketplace.common.storage.signing.ShowcaseUrlSigner signer =
                new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("");
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meter =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        controller = new FileController(fileStorageService, mimeTypeRegistry, tenantResolver,
                streamingMetrics, signer, urlBuilder, meter);
        controller.storageIndex = storageIndex;
        controller.orgAccessGuard = orgAccessGuard;
        lenient().when(tenantResolver.resolve(request)).thenReturn(OWN_TENANT);
        lenient().when(tenantResolver.resolveOrgId(request)).thenReturn(null);
        lenient().when(tenantResolver.resolveOrgRole(request)).thenReturn(null);
        lenient().when(orgAccessGuard.canAccess(any(), any(), eq("file"), any(), any())).thenReturn(true);
    }

    /**
     * An interface iframe cannot send the session header, so the frontend inlined every file as a
     * base64 data: URI - six 20 MB videos killed the page (RangeError: Invalid string length).
     * Media now streams from a short-lived signed link minted here, which must be handed out to
     * exactly the callers {@code /raw} would serve, and to nobody else.
     */
    @Nested
    @DisplayName("Signed link for streaming (/by-id/{id}/signed-url)")
    class SignedUrlById {

        private final com.apimarketplace.common.storage.signing.ShowcaseUrlSigner signer =
                new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("test-secret");
        private FileController signing;

        @BeforeEach
        void signingController() {
            signing = new FileController(fileStorageService, mimeTypeRegistry, tenantResolver,
                    streamingMetrics, signer, urlBuilder,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            signing.storageIndex = storageIndex;
            signing.orgAccessGuard = orgAccessGuard;
        }

        private StorageEntity video(UUID id) {
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setStorageType("S3_FILE");
            e.setS3Key(OTHER_TENANT + "/general/general/6894721f_ep01.mp4");
            e.setFileName("ep01.mp4");
            e.setMimeType("video/mp4");
            return e;
        }

        @Test
        @DisplayName("a readable file gets a proxy-signed link the verifier accepts, for that key only, expiring in 4 hours, the showcase default")
        void readableFileGetsVerifiableLink() {
            UUID id = UUID.randomUUID();
            StorageEntity e = video(id);
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(storageIndex.getEntityByIdForScope(id, OWN_TENANT, "org-7")).thenReturn(Optional.of(e));
            long before = java.time.Instant.now().getEpochSecond();

            ResponseEntity<Map<String, Object>> r = signing.signedUrlById(id, "inline", request);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            String url = (String) r.getBody().get("url");
            assertThat(url).startsWith("/api/files/proxy-signed?");
            com.apimarketplace.common.storage.url.FileProxyUrls.ProxyUrl parsed =
                    com.apimarketplace.common.storage.url.FileProxyUrls.parse(url);
            assertThat(parsed.key()).isEqualTo(e.getS3Key());
            assertThat(parsed.disposition()).isEqualTo("inline");
            assertThat(parsed.exp()).isEqualTo(((Number) r.getBody().get("expires_at")).longValue())
                    .isBetween(before + 14300, before + 14500);
            // The link is exactly what proxySignedDownload will accept - and nothing broader.
            long now = java.time.Instant.now().getEpochSecond();
            assertThat(signer.verify(parsed.key(), parsed.exp(), "inline", parsed.sig(), now)).isTrue();
            assertThat(signer.verify(OTHER_TENANT + "/general/general/other.mp4", parsed.exp(), "inline", parsed.sig(), now)).isFalse();
            assertThat(signer.verify(parsed.key(), parsed.exp(), "attachment", parsed.sig(), now)).isFalse();
        }

        @Test
        @DisplayName("disposition=attachment is signed as attachment")
        void attachmentDispositionIsSigned() {
            UUID id = UUID.randomUUID();
            when(storageIndex.getEntityById(id, OWN_TENANT)).thenReturn(Optional.of(video(id)));

            ResponseEntity<Map<String, Object>> r = signing.signedUrlById(id, "attachment", request);

            com.apimarketplace.common.storage.url.FileProxyUrls.ProxyUrl parsed =
                    com.apimarketplace.common.storage.url.FileProxyUrls.parse((String) r.getBody().get("url"));
            assertThat(parsed.disposition()).isEqualTo("attachment");
            assertThat(signer.verify(parsed.key(), parsed.exp(), "attachment", parsed.sig(),
                    java.time.Instant.now().getEpochSecond())).isTrue();
        }

        @Test
        @DisplayName("404, and nothing signed, when the file is not in the caller's org and is not theirs")
        void unreadableFileIs404() {
            UUID id = UUID.randomUUID();
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(storageIndex.getEntityByIdForScope(id, OWN_TENANT, "org-7")).thenReturn(Optional.empty());
            when(storageIndex.getEntityById(id, OWN_TENANT)).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> r = signing.signedUrlById(id, "inline", request);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody()).isNull();
        }

        @Test
        @DisplayName("404 when the file is restricted for this org member, exactly as /raw refuses it")
        void restrictedFileIs404() {
            UUID id = UUID.randomUUID();
            StorageEntity e = video(id);
            e.setOrganizationId("org-7");
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
            when(storageIndex.getEntityByIdForScope(id, OWN_TENANT, "org-7")).thenReturn(Optional.of(e));
            when(orgAccessGuard.canAccess("org-7", OWN_TENANT, "file", id.toString(), "MEMBER")).thenReturn(false);

            assertThat(signing.signedUrlById(id, "inline", request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(signing.rawById(id, "inline", request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("404 for an inline row: there is no stored object for a signed link to stream")
        void inlineRowIs404() {
            UUID id = UUID.randomUUID();
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setStorageType("TEXT");
            e.setFileName("note.txt");
            e.setMimeType("text/plain");
            e.setDataText("hello");
            when(storageIndex.getEntityById(id, OWN_TENANT)).thenReturn(Optional.of(e));

            assertThat(signing.signedUrlById(id, "inline", request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("503 when this installation has no signing secret (the frontend falls back on it)")
        void noSecretIs503() {
            UUID id = UUID.randomUUID();
            when(storageIndex.getEntityById(id, OWN_TENANT)).thenReturn(Optional.of(video(id)));

            // `controller` is built with a blank secret in setUp.
            assertThat(controller.signedUrlById(id, "inline", request).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("Anonymous avatar serve (/avatar/{id})")
    class AnonymousAvatarServe {

        private StorageEntity avatarEntity(UUID id) {
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setTenantId(OWN_TENANT);
            e.setStorageType("S3_FILE");
            e.setS3Key(OWN_TENANT + "/general/avatar/ab12_avatar.svg");
            e.setFileName("avatar.svg");
            e.setMimeType("image/svg+xml");
            return e;
        }

        @Test
        @DisplayName("serves an eligible avatar with the security headers the design leans on (CSP no-script, nosniff, public cache)")
        void servesAvatarWithSecurityHeaders() throws IOException {
            UUID id = UUID.randomUUID();
            StorageEntity e = avatarEntity(id);
            when(storageIndex.getPublicAvatarEntity(id)).thenReturn(Optional.of(e));
            byte[] payload = "<svg/>".getBytes();
            DownloadStream ds = new DownloadStream(new ByteArrayInputStream(payload), payload.length, "image/svg+xml");
            when(fileStorageService.openStream(e.getS3Key())).thenReturn(Optional.of(ds));

            ResponseEntity<StreamingResponseBody> r = controller.avatarById(id);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getHeaders().getFirst("Content-Security-Policy"))
                    .isEqualTo(com.apimarketplace.common.web.SafeFileServeHeaders.CONTENT_SECURITY_POLICY);
            assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getHeaders().getFirst("Cache-Control")).isEqualTo("public, max-age=86400");
            assertThat(r.getHeaders().getFirst("Content-Type")).isEqualTo("image/svg+xml");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            r.getBody().writeTo(out);
            assertThat(out.toByteArray()).isEqualTo(payload);
        }

        @Test
        @DisplayName("non-eligible id 404s WITHOUT touching object storage (single gate in getPublicAvatarEntity)")
        void nonEligibleId404sWithoutStorageRead() {
            UUID id = UUID.randomUUID();
            when(storageIndex.getPublicAvatarEntity(id)).thenReturn(Optional.empty());

            ResponseEntity<StreamingResponseBody> r = controller.avatarById(id);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(fileStorageService, never()).openStream(anyString());
        }

        @Test
        @DisplayName("no tenant resolution, no org guard - the endpoint is anonymous by design")
        void anonymousByDesign() {
            UUID id = UUID.randomUUID();
            when(storageIndex.getPublicAvatarEntity(id)).thenReturn(Optional.empty());

            controller.avatarById(id);

            verify(tenantResolver, never()).resolve(any(HttpServletRequest.class));
            verify(orgAccessGuard, never()).canAccess(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Opaque by-id serve")
    class OpaqueById {

        @Test
        @DisplayName("/by-id streams an S3 file resolved org-scoped by UUID (the key is never in the request)")
        void rawByIdStreamsS3File() throws IOException {
            UUID id = UUID.randomUUID();
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setStorageType("S3_FILE");
            e.setS3Key(OTHER_TENANT + "/wf/run/step/x.xml");
            e.setFileName("x.xml");
            e.setMimeType("application/xml");
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(storageIndex.getEntityByIdForScope(id, OWN_TENANT, "org-7")).thenReturn(Optional.of(e));
            byte[] payload = "<rss/>".getBytes();
            DownloadStream ds = new DownloadStream(new ByteArrayInputStream(payload), payload.length, "application/xml");
            when(fileStorageService.openStream(e.getS3Key())).thenReturn(Optional.of(ds));

            ResponseEntity<StreamingResponseBody> r = controller.rawById(id, "inline", request);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            r.getBody().writeTo(out);
            assertThat(out.toByteArray()).isEqualTo(payload);
        }

        @Test
        @DisplayName("/by-id serves an inline TEXT row's content (no s3 key, no object storage)")
        void rawByIdServesInlineText() throws IOException {
            UUID id = UUID.randomUUID();
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setStorageType("TEXT");
            e.setFileName("note.txt");
            e.setMimeType("text/plain");
            e.setDataText("hello");
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(storageIndex.getEntityByIdForScope(id, OWN_TENANT, "org-7")).thenReturn(Optional.of(e));

            ResponseEntity<StreamingResponseBody> r = controller.rawById(id, "inline", request);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            r.getBody().writeTo(out);
            assertThat(out.toString()).isEqualTo("hello");
        }

        @Test
        @DisplayName("LC-020: /by-id never renders an uploaded text/html file inline - attachment + nosniff + sandbox CSP")
        void rawByIdForcesHtmlToAttachment() {
            UUID id = UUID.randomUUID();
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setStorageType("S3_FILE");
            e.setS3Key(OWN_TENANT + "/general/x/payload.html");
            e.setFileName("payload.html");
            e.setMimeType("text/html");
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(storageIndex.getEntityByIdForScope(id, OWN_TENANT, "org-7")).thenReturn(Optional.of(e));
            byte[] payload = "<script>alert(1)</script>".getBytes();
            when(fileStorageService.openStream(e.getS3Key())).thenReturn(Optional.of(
                    new DownloadStream(new ByteArrayInputStream(payload), payload.length, "text/html")));

            ResponseEntity<StreamingResponseBody> r = controller.rawById(id, "inline", request);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getHeaders().getFirst("Content-Disposition")).startsWith("attachment;");
            assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        }

        @Test
        @DisplayName("LC-020: the inline-row branch (no s3 key) applies the same rule; an image still renders inline")
        void rawByIdInlineRowBranchAndImagesStayInline() throws IOException {
            UUID htmlId = UUID.randomUUID();
            StorageEntity html = new StorageEntity();
            html.setId(htmlId);
            html.setStorageType("TEXT");
            html.setFileName("page.html");
            html.setMimeType("text/html");
            html.setDataText("<script>alert(1)</script>");
            UUID pngId = UUID.randomUUID();
            StorageEntity png = new StorageEntity();
            png.setId(pngId);
            png.setStorageType("S3_FILE");
            png.setS3Key(OWN_TENANT + "/general/x/pic.png");
            png.setFileName("pic.png");
            png.setMimeType("image/png");
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(storageIndex.getEntityByIdForScope(htmlId, OWN_TENANT, "org-7")).thenReturn(Optional.of(html));
            when(storageIndex.getEntityByIdForScope(pngId, OWN_TENANT, "org-7")).thenReturn(Optional.of(png));
            when(fileStorageService.openStream(png.getS3Key())).thenReturn(Optional.of(
                    new DownloadStream(new ByteArrayInputStream(new byte[]{1}), 1, "image/png")));

            ResponseEntity<StreamingResponseBody> htmlR = controller.rawById(htmlId, "inline", request);
            ResponseEntity<StreamingResponseBody> pngR = controller.rawById(pngId, "inline", request);

            assertThat(htmlR.getHeaders().getFirst("Content-Disposition")).startsWith("attachment;");
            assertThat(htmlR.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(htmlR.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
            assertThat(pngR.getHeaders().getFirst("Content-Disposition")).startsWith("inline;");
            assertThat(pngR.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        }

        @Test
        @DisplayName("/by-id → 404 when the id is not in the caller's active org (org-scoped, no leak)")
        void rawByIdNotFoundCrossOrg() {
            UUID id = UUID.randomUUID();
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(storageIndex.getEntityByIdForScope(id, OWN_TENANT, "org-7")).thenReturn(Optional.empty());
            when(storageIndex.getEntityById(id, OWN_TENANT)).thenReturn(Optional.empty());

            assertThat(controller.rawById(id, "inline", request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("/by-id returns 404 when the file is restricted for the org member")
        void rawByIdRestrictedFileReturnsNotFound() {
            UUID id = UUID.randomUUID();
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setStorageType("S3_FILE");
            e.setS3Key(OTHER_TENANT + "/wf/run/step/hidden.xml");
            e.setFileName("hidden.xml");
            e.setMimeType("application/xml");
            e.setOrganizationId("org-7");
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-7");
            when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
            when(storageIndex.getEntityByIdForScope(id, OWN_TENANT, "org-7")).thenReturn(Optional.of(e));
            when(orgAccessGuard.canAccess("org-7", OWN_TENANT, "file", id.toString(), "MEMBER")).thenReturn(false);

            ResponseEntity<StreamingResponseBody> r = controller.rawById(id, "inline", request);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(fileStorageService, org.mockito.Mockito.never()).openStream(anyString());
        }

        @Test
        @DisplayName("/by-id → 404 with no active org AND not the owner (owner fast-path also misses)")
        void rawByIdNoOrgNotOwnerNotFound() {
            UUID id = UUID.randomUUID();
            when(tenantResolver.resolveOrgId(request)).thenReturn(null);
            when(storageIndex.getEntityById(id, OWN_TENANT)).thenReturn(Optional.empty());

            assertThat(controller.rawById(id, "inline", request).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("/by-id owner fast-path: serves the caller's OWN file even with no active org (cross-workspace <img>)")
        void rawByIdOwnerFastPathServesOwnFile() throws IOException {
            UUID id = UUID.randomUUID();
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setStorageType("S3_FILE");
            e.setS3Key(OWN_TENANT + "/wf/run/step/own.png");
            e.setFileName("own.png");
            e.setMimeType("image/png");
            when(tenantResolver.resolveOrgId(request)).thenReturn(null);            // no active org header
            when(storageIndex.getEntityById(id, OWN_TENANT)).thenReturn(Optional.of(e)); // owner-scoped hit
            byte[] payload = new byte[]{1, 2, 3};
            DownloadStream ds = new DownloadStream(new ByteArrayInputStream(payload), payload.length, "image/png");
            when(fileStorageService.openStream(e.getS3Key())).thenReturn(Optional.of(ds));

            ResponseEntity<StreamingResponseBody> r = controller.rawById(id, "inline", request);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            r.getBody().writeTo(out);
            assertThat(out.toByteArray()).isEqualTo(payload);
        }

        @Test
        @DisplayName("/by-id never consults the access guard when neither the file nor the request carries an org (CE / personal scope serves unchecked, by design)")
        void rawByIdNoOrgScopeBypassesAccessGuard() throws IOException {
            UUID id = UUID.randomUUID();
            StorageEntity e = new StorageEntity();
            e.setId(id);
            e.setStorageType("S3_FILE");
            e.setS3Key(OWN_TENANT + "/wf/run/step/local.png");
            e.setFileName("local.png");
            e.setMimeType("image/png");
            // entity has no organization_id; request carries no org header (default mock)
            when(storageIndex.getEntityById(id, OWN_TENANT)).thenReturn(Optional.of(e));
            byte[] payload = new byte[]{4, 5, 6};
            DownloadStream ds = new DownloadStream(new ByteArrayInputStream(payload), payload.length, "image/png");
            when(fileStorageService.openStream(e.getS3Key())).thenReturn(Optional.of(ds));

            ResponseEntity<StreamingResponseBody> r = controller.rawById(id, "inline", request);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            r.getBody().writeTo(out);
            assertThat(out.toByteArray()).isEqualTo(payload);
            // No org scope anywhere → the member-restriction guard is intentionally not consulted.
            verify(orgAccessGuard, org.mockito.Mockito.never()).canAccess(any(), any(), eq("file"), any(), any());
        }
    }

    @Nested
    @DisplayName("Generic upload - opaque response")
    class GenericUpload {

        @Test
        @DisplayName("Binds the request workspace, returns the opaque id + by-id url (no s3 key in the url)")
        void genericUploadReturnsOpaqueIdAndUrl() throws IOException {
            String storageId = UUID.randomUUID().toString();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "quota.txt", "text/plain", new byte[]{1, 2, 3});
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-42");
            when(tenantResolver.resolveOrgRole(request)).thenReturn("OWNER");
            when(fileStorageService.uploadGeneric(
                    eq(OWN_TENANT), eq("e2e-quota"), eq("quota.txt"), eq("text/plain"),
                    any(InputStream.class), eq(3L)))
                    .thenAnswer(invocation -> {
                        assertThat(TenantResolver.currentRequestOrganizationId()).isEqualTo("org-42");
                        assertThat(TenantResolver.currentRequestOrganizationRole()).isEqualTo("OWNER");
                        return FileRef.of("58/general/e2e-quota/quota.txt", "quota.txt", "text/plain", 3L, storageId);
                    });

            ResponseEntity<?> response = controller.genericUpload(file, "e2e-quota", request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("id", storageId);
            assertThat((String) body.get("url"))
                    .as("the response url is opaque (by-id) and never contains the s3 key / tenant id")
                    .isEqualTo("https://livecontext.ai/api/proxy/files/by-id/" + storageId + "/raw?disposition=inline")
                    .doesNotContain("58/general");
            assertThat(TenantResolver.currentRequestOrganizationId())
                    .as("Workspace binding must not leak after upload").isNull();
            verify(fileStorageService).uploadGeneric(
                    eq(OWN_TENANT), eq("e2e-quota"), eq("quota.txt"), eq("text/plain"),
                    any(InputStream.class), eq(3L));
        }

        @Test
        @DisplayName("threads a non-null parentFolderId form field to the folder-aware (7-arg) uploadGeneric so the file lands in the current folder (V313)")
        void genericUploadIntoFolderThreadsParentFolderId() throws IOException {
            UUID folderId = UUID.randomUUID();
            String storageId = UUID.randomUUID().toString();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "doc.pdf", "application/pdf", new byte[]{1, 2});
            when(request.getParameter("parentFolderId")).thenReturn(folderId.toString());
            when(fileStorageService.uploadGeneric(
                    eq(OWN_TENANT), eq("files"), eq("doc.pdf"), eq("application/pdf"),
                    any(InputStream.class), eq(2L), eq(folderId)))
                    .thenReturn(FileRef.of("58/general/files/doc.pdf", "doc.pdf", "application/pdf", 2L, storageId));

            ResponseEntity<?> response = controller.genericUpload(file, "files", request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // The folder-aware 7-arg overload must be used (not the root 6-arg) when the form carries a folder id.
            verify(fileStorageService).uploadGeneric(
                    eq(OWN_TENANT), eq("files"), eq("doc.pdf"), eq("application/pdf"),
                    any(InputStream.class), eq(2L), eq(folderId));
            verify(fileStorageService, never()).uploadGeneric(
                    eq(OWN_TENANT), eq("files"), eq("doc.pdf"), eq("application/pdf"),
                    any(InputStream.class), eq(2L));
        }

        @Test
        @DisplayName("500 when the upload produced no storage id - never falls back to a key-leaking url")
        void genericUploadWithoutIdFailsLoud() throws IOException {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "noid.txt", "text/plain", new byte[]{9});
            when(fileStorageService.uploadGeneric(
                    eq(OWN_TENANT), eq("general"), eq("noid.txt"), eq("text/plain"),
                    any(InputStream.class), eq(1L)))
                    .thenReturn(FileRef.of("58/general/general/noid.txt", "noid.txt", "text/plain", 1L));

            ResponseEntity<?> response = controller.genericUpload(file, "general", request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("DELETE /api/files - org VIEWER read-only rule")
    class DeleteViewerGate {

        @Test
        @DisplayName("an org VIEWER is refused 403 and the object is never deleted")
        void viewerCannotDelete() {
            // Regression: this endpoint's ONLY check was the key prefix, so a VIEWER could
            // delete any file they had uploaded themselves. The platform-wide rule is that a
            // VIEWER is read-only, and the bulk-delete sibling in StorageExplorerController
            // has always enforced it. Pre-fix this returned 204 and called delete().
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
            when(tenantResolver.resolveOrgRole(request)).thenReturn("VIEWER");

            ResponseEntity<Void> response = controller.deleteFile(OWN_TENANT + "/general/a.png", request);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verify(fileStorageService, never()).delete(anyString());
        }

        @Test
        @DisplayName("a padded ' VIEWER ' is refused too - the guard trims")
        void paddedViewerCannotDelete() {
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
            when(tenantResolver.resolveOrgRole(request)).thenReturn("  VIEWER  ");

            ResponseEntity<Void> response = controller.deleteFile(OWN_TENANT + "/general/a.png", request);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verify(fileStorageService, never()).delete(anyString());
        }

        @Test
        @DisplayName("a MEMBER still deletes their own file - the gate blocks VIEWERs, not everyone")
        void memberCanStillDelete() {
            when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
            when(tenantResolver.resolveOrgRole(request)).thenReturn("MEMBER");
            when(fileStorageService.delete(anyString())).thenReturn(true);

            ResponseEntity<Void> response = controller.deleteFile(OWN_TENANT + "/general/a.png", request);

            assertThat(response.getStatusCode().value()).isEqualTo(204);
        }

        @Test
        @DisplayName("the key-prefix check still refuses another tenant's file")
        void foreignKeyStillRefused() {
            ResponseEntity<Void> response = controller.deleteFile(OTHER_TENANT + "/general/a.png", request);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            verify(fileStorageService, never()).delete(anyString());
        }
    }

}
