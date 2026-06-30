package com.apimarketplace.publication.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImageScreeningService - every media URL is surfaced regardless of host")
class ImageScreeningServiceTest {

    private final ImageScreeningService service =
            new ImageScreeningService(new ItemDataResourceExtractor(new ObjectMapper()));

    @Test
    @DisplayName("Empty templates produce a clean report (isClean() == true)")
    void cleanReportForEmptyTemplates() {
        ImageScreeningReport report = service.scan(null, null, null);

        assertThat(report.isClean()).isTrue();
        assertThat(report.flagged()).isEmpty();
    }

    @Test
    @DisplayName("Templates without media references stay clean")
    void cleanReportForNoMedia() {
        String html = "<div><p>Hello</p><a href=\"/about\">about</a></div>";

        ImageScreeningReport report = service.scan(html, null, null);

        assertThat(report.isClean()).isTrue();
    }

    @Test
    @DisplayName("Every <img src> is flagged - host irrelevant, publisher reviews each")
    void everyImgFlaggedRegardlessOfHost() {
        String html = "<img src=\"https://cdn.livecontext.ai/01.jpg\">"
                + "<img src=\"https://images.unsplash.com/photo-1\">"
                + "<img src=\"https://a0.muscache.com/photo.jpg\">"
                + "<img src=\"/api/files/proxy-signed?id=xyz\">";

        ImageScreeningReport report = service.scan(html, null, null);

        assertThat(report.isClean()).isFalse();
        assertThat(report.flagged()).extracting(ImageScreeningReport.FlaggedImage::url)
                .containsExactly(
                        "https://cdn.livecontext.ai/01.jpg",
                        "https://images.unsplash.com/photo-1",
                        "https://a0.muscache.com/photo.jpg",
                        "/api/files/proxy-signed?id=xyz");
    }

    @Test
    @DisplayName("<video src> + <audio src> + <video poster> all surface for review")
    void videoAudioSurfaced() {
        String html = "<video src=\"https://cdn.example.com/intro.mp4\" poster=\"https://cdn.example.com/poster.jpg\"></video>"
                + "<audio src=\"/api/files/proxy-signed?id=track\"></audio>";

        ImageScreeningReport report = service.scan(html, null, null);

        assertThat(report.flagged()).hasSize(3);
        assertThat(report.flagged()).extracting(ImageScreeningReport.FlaggedImage::url)
                .containsExactlyInAnyOrder(
                        "https://cdn.example.com/intro.mp4",
                        "https://cdn.example.com/poster.jpg",
                        "/api/files/proxy-signed?id=track");
    }

    @Test
    @DisplayName("<a download href=\"…media.jpg\"> downloadable links are flagged too")
    void anchorDownloadFlagged() {
        String html = "<a href=\"/api/files/proxy-signed?id=doc.pdf\" download>Get PDF</a>"
                + "<a href=\"https://example.com/photo.jpg\" download=\"photo\">Download</a>";

        ImageScreeningReport report = service.scan(html, null, null);

        assertThat(report.flagged()).extracting(ImageScreeningReport.FlaggedImage::url)
                .containsExactlyInAnyOrder(
                        "/api/files/proxy-signed?id=doc.pdf",
                        "https://example.com/photo.jpg");
    }

    @Test
    @DisplayName("CSS background-image url(…) flagged with CSS source")
    void cssTemplateFlaggedAsCss() {
        String css = ".hero { background-image: url('/api/files/proxy-signed?id=bg.jpg'); }";

        ImageScreeningReport report = service.scan(null, css, null);

        assertThat(report.flagged()).hasSize(1);
        assertThat(report.flagged().get(0).source()).isEqualTo(ImageScreeningReport.Source.CSS);
    }

    @Test
    @DisplayName("JS hardcoded media URL literal flagged with JS source")
    void jsTemplateFlaggedAsJs() {
        String js = "const HERO = '/static/video/intro.mp4';";

        ImageScreeningReport report = service.scan(null, null, js);

        // /static/… is not a hardcoded https:// literal - JS extractor only
        // catches absolute network URLs to avoid noise. Use a network URL.
        assertThat(report.flagged()).isEmpty();

        String jsExternal = "const HERO = 'https://x.com/photo.jpg';";
        ImageScreeningReport report2 = service.scan(null, null, jsExternal);
        assertThat(report2.flagged()).hasSize(1);
        assertThat(report2.flagged().get(0).source()).isEqualTo(ImageScreeningReport.Source.JS);
    }

    @Test
    @DisplayName("Same URL across HTML + CSS deduplicates - first source (HTML) wins")
    void dedupePrefersFirstSource() {
        String html = "<img src=\"https://a0.muscache.com/x.jpg\">";
        String css = ".bg { background: url(https://a0.muscache.com/x.jpg); }";

        ImageScreeningReport report = service.scan(html, css, null);

        assertThat(report.flagged()).hasSize(1);
        assertThat(report.flagged().get(0).source()).isEqualTo(ImageScreeningReport.Source.HTML);
    }

    @Test
    @DisplayName("data: URI is treated as inline publisher bytes - never flagged")
    void dataUriNeverFlagged() {
        String html = "<img src=\"data:image/png;base64,iVBORw0KGgo=\">";

        ImageScreeningReport report = service.scan(html, null, null);

        assertThat(report.isClean()).isTrue();
    }

    @Test
    @DisplayName("Scan order: HTML before CSS before JS")
    void scanOrderHtmlCssJs() {
        String html = "<img src=\"/a.jpg\">";
        String css = ".b { background: url('/b.jpg'); }";
        String js = "var c = 'https://x.com/c.jpg';";

        ImageScreeningReport report = service.scan(html, css, js);

        assertThat(report.flagged()).extracting(ImageScreeningReport.FlaggedImage::source)
                .containsExactly(
                        ImageScreeningReport.Source.HTML,
                        ImageScreeningReport.Source.CSS,
                        ImageScreeningReport.Source.JS);
    }

    // ===================== Wave 2b - items[].data scanning =====================

    @Test
    @DisplayName("Scraped CDN URL in items[].data is flagged with DATA source (template only has the placeholder)")
    void scrapedUrlInItemDataFlaggedAsData() {
        String html = "<img src=\"{{profilePicUrl}}\">"; // template carries only the placeholder
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of(
                "username", "acme",
                "displayUrl", "https://scontent-atl3-2.cdninstagram.com/v/t51/472_n.jpg?stp=dst-jpg")));

        ImageScreeningReport report = service.scan(html, null, null, items);

        // The placeholder src is flagged HTML (host unknown), the real image is DATA.
        assertThat(report.flagged()).extracting(ImageScreeningReport.FlaggedImage::url)
                .contains("https://scontent-atl3-2.cdninstagram.com/v/t51/472_n.jpg?stp=dst-jpg");
        assertThat(report.flagged())
                .filteredOn(f -> f.url().contains("cdninstagram"))
                .allMatch(f -> f.source() == ImageScreeningReport.Source.DATA);
    }

    @Test
    @DisplayName("Downloaded FileRef in items[].data is flagged by its path with DATA source")
    void downloadedFileRefFlaggedAsData() {
        Map<String, Object> fileRef = Map.of(
                "_type", "file",
                "path", "5/wf/run/step/472007201_n.jpg",
                "name", "472007201_n.jpg",
                "mimeType", "image/jpeg");
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of("profilePicUrl", fileRef)));

        ImageScreeningReport report = service.scan(null, null, null, items);

        assertThat(report.flagged()).hasSize(1);
        assertThat(report.flagged().get(0).url()).isEqualTo("5/wf/run/step/472007201_n.jpg");
        assertThat(report.flagged().get(0).source()).isEqualTo(ImageScreeningReport.Source.DATA);
    }

    @Test
    @DisplayName("Plain link fields in data (post permalink, bio website) are NOT flagged")
    void plainLinkFieldsInDataNotFlagged() {
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of(
                "url", "https://www.instagram.com/p/Cabc123/",
                "externalUrl", "https://hrbl.me/acme")));

        ImageScreeningReport report = service.scan(null, null, null, items);

        assertThat(report.isClean()).isTrue();
    }

    @Test
    @DisplayName("A template image and the same image in data deduplicate to one entry")
    void templateAndDataDedup() {
        String html = "<img src=\"https://cdn.example.com/x.jpg\">";
        List<Map<String, Object>> items = List.of(Map.of("data", Map.of(
                "imageUrl", "https://cdn.example.com/x.jpg")));

        ImageScreeningReport report = service.scan(html, null, null, items);

        assertThat(report.flagged()).hasSize(1);
        // First source wins - HTML before DATA.
        assertThat(report.flagged().get(0).source()).isEqualTo(ImageScreeningReport.Source.HTML);
    }

    @Test
    @DisplayName("Null items list is a no-op - template-only scan still works")
    void nullItemsIsNoOp() {
        ImageScreeningReport report = service.scan("<img src=\"https://x.com/a.jpg\">", null, null, null);

        assertThat(report.flagged()).extracting(ImageScreeningReport.FlaggedImage::url)
                .containsExactly("https://x.com/a.jpg");
    }
}
