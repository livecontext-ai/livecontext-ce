package com.apimarketplace.orchestrator.controllers.file;

import com.apimarketplace.common.web.SafeFileServeHeaders;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.trigger.PublicApplicationController;
import com.apimarketplace.orchestrator.trigger.PublicApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LC-016 / LC-020 siblings in orchestrator: the CE local-storage download route and the anonymous
 * share-token file route both served stored files inline with a name-derived type, so an uploaded
 * {@code .html} rendered as a first-party document.
 */
@DisplayName("Orchestrator stored-file serve paths: attachment for active types, nosniff + sandbox CSP")
class StoredFileServeHeadersTest {

    @Test
    @DisplayName("CE /api/files/download: an .html file downloads; a .png still renders inline")
    void localDownloadRoute() {
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.download("7/wf/run/step/x.html")).thenReturn(Optional.of("<script>".getBytes()));
        when(storage.download("7/wf/run/step/x.png")).thenReturn(Optional.of(new byte[]{1}));
        FileDownloadController controller = new FileDownloadController(storage);

        ResponseEntity<?> html = controller.download("7/wf/run/step/x.html", "7");
        ResponseEntity<?> png = controller.download("7/wf/run/step/x.png", "7");

        assertThat(html.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
        assertThat(html.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(html.getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo(SafeFileServeHeaders.CONTENT_SECURITY_POLICY);
        assertThat(png.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline;");
        assertThat(png.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("anonymous /app/public/{token}/file: an .html key downloads, an image stays inline")
    void publicApplicationFileRoute() {
        PublicApplicationService service = mock(PublicApplicationService.class);
        when(service.downloadFile("tok", "1/a/page.html")).thenReturn("<script>".getBytes());
        when(service.downloadFile("tok", "1/a/pic.jpg")).thenReturn(new byte[]{1});
        PublicApplicationController controller = new PublicApplicationController(service);

        ResponseEntity<?> html = controller.proxyFile("tok", "1/a/page.html");
        ResponseEntity<?> jpg = controller.proxyFile("tok", "1/a/pic.jpg");

        assertThat(html.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
        assertThat(html.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(html.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        assertThat(jpg.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline;");
    }
}
