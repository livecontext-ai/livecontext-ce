package com.apimarketplace.publication.screening;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ItemDataResourceExtractor - surfaces images that live in items[].data")
class ItemDataResourceExtractorTest {

    private final ItemDataResourceExtractor extractor =
            new ItemDataResourceExtractor(new ObjectMapper());

    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return List.of(Map.of("data", data));
    }

    @Test
    @DisplayName("A scraped CDN URL with a media extension is extracted even with query params")
    void scrapedUrlWithQueryExtracted() {
        Set<String> out = extractor.extract(items(Map.of(
                "displayUrl", "https://scontent-atl3-2.cdninstagram.com/v/t51/472_n.jpg?stp=dst-jpg&_nc=1")));

        assertThat(out).containsExactly(
                "https://scontent-atl3-2.cdninstagram.com/v/t51/472_n.jpg?stp=dst-jpg&_nc=1");
    }

    @Test
    @DisplayName("An http URL under a media-named key is extracted even without a file extension")
    void mediaKeyWithoutExtensionExtracted() {
        Set<String> out = extractor.extract(items(Map.of(
                "avatarUrl", "https://img.example.com/proxy/abcdef")));

        assertThat(out).containsExactly("https://img.example.com/proxy/abcdef");
    }

    @Test
    @DisplayName("Plain link fields (permalink under url, bio website under externalUrl) are NOT extracted")
    void plainLinksNotExtracted() {
        Set<String> out = extractor.extract(items(Map.of(
                "url", "https://www.instagram.com/p/Cabc123/",
                "externalUrl", "https://hrbl.me/acme",
                "biography", "hello world")));

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("An image FileRef is extracted by its path; a non-media FileRef (PDF) is ignored")
    void imageFileRefExtractedPdfIgnored() {
        Map<String, Object> imageRef = Map.of(
                "_type", "file", "path", "5/wf/run/step/472_n.jpg", "mimeType", "image/jpeg");
        Map<String, Object> pdfRef = Map.of(
                "_type", "file", "path", "5/wf/run/step/report.pdf", "mimeType", "application/pdf");

        Set<String> out = extractor.extract(items(Map.of("avatar", imageRef, "attachment", pdfRef)));

        assertThat(out).containsExactly("5/wf/run/step/472_n.jpg");
    }

    @Test
    @DisplayName("A video FileRef (video/* mime) is flagged too - so a flagged video can be replaced")
    void videoFileRefExtracted() {
        Map<String, Object> videoRef = Map.of(
                "_type", "file", "path", "5/wf/run/step/clip.mp4", "mimeType", "video/mp4");

        Set<String> out = extractor.extract(items(Map.of("promoVideo", videoRef)));

        assertThat(out).containsExactly("5/wf/run/step/clip.mp4");
    }

    @Test
    @DisplayName("A FileRef with opaque octet-stream mime falls back to the path extension")
    void octetStreamFileRefUsesExtension() {
        Map<String, Object> ref = Map.of(
                "_type", "file", "path", "5/dl/photo.png", "mimeType", "application/octet-stream");

        Set<String> out = extractor.extract(items(Map.of("file", ref)));

        assertThat(out).containsExactly("5/dl/photo.png");
    }

    @Test
    @DisplayName("Images nested in an array of post objects are all surfaced (FileRef + displayUrl)")
    void nestedArrayOfPostsExtracted() {
        Map<String, Object> img0 = Map.of("_type", "file", "path", "5/dl/p0.jpg", "mimeType", "image/jpeg");
        Map<String, Object> post0 = Map.of(
                "image", img0,
                "displayUrl", "https://scontent.cdninstagram.com/p0_n.jpg",
                "url", "https://instagram.com/p/p0/");
        Map<String, Object> post1 = Map.of(
                "displayUrl", "https://scontent.cdninstagram.com/p1_n.jpg",
                "url", "https://instagram.com/p/p1/");

        Set<String> out = extractor.extract(items(Map.of("postsJson", List.of(post0, post1))));

        assertThat(out).containsExactlyInAnyOrder(
                "5/dl/p0.jpg",
                "https://scontent.cdninstagram.com/p0_n.jpg",
                "https://scontent.cdninstagram.com/p1_n.jpg");
    }

    @Test
    @DisplayName("FileRefs/URLs embedded inside a JSON-encoded string value are parsed and surfaced")
    void jsonEncodedStringParsed() {
        String postsJson = "[{\"displayUrl\":\"https://cdn.example.com/a.jpg\","
                + "\"image\":{\"_type\":\"file\",\"path\":\"5/dl/a.jpg\",\"mimeType\":\"image/jpeg\"}}]";

        Set<String> out = extractor.extract(items(Map.of("postsJson", postsJson)));

        assertThat(out).containsExactlyInAnyOrder(
                "https://cdn.example.com/a.jpg",
                "5/dl/a.jpg");
    }

    @Test
    @DisplayName("Media-key heuristic catches logoUrl but not the unrelated logoutUrl")
    void logoKeyDoesNotMatchLogout() {
        Set<String> out = extractor.extract(items(Map.of(
                "logoUrl", "https://cdn.example.com/brand-logo",      // media key → flagged
                "logoutUrl", "https://app.example.com/logout")));     // not an image key → ignored

        assertThat(out).containsExactly("https://cdn.example.com/brand-logo");
    }

    @Test
    @DisplayName("Null / empty items list yields an empty set")
    void nullItemsEmpty() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract(List.of())).isEmpty();
    }

    @Test
    @DisplayName("data: URIs and non-media http links are not extracted")
    void dataUriAndNonMediaIgnored() {
        Set<String> out = extractor.extract(items(Map.of(
                "thumbnail", "data:image/png;base64,iVBOR",
                "website", "https://example.com/landing")));

        assertThat(out).isEmpty();
    }
}
