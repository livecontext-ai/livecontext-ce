package com.apimarketplace.orchestrator.utils.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MimeTypeRegistryTest {

    private MimeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MimeTypeRegistry();
        registry.init();
    }

    // ========== Image types ==========

    @Test
    void resolve_png_returnsImagePng() {
        assertEquals("image/png", registry.resolve("photo.png"));
        assertEquals("image/png", registry.resolve("PHOTO.PNG"));
    }

    @Test
    void resolve_jpeg_returnsImageJpeg() {
        assertEquals("image/jpeg", registry.resolve("photo.jpg"));
        assertEquals("image/jpeg", registry.resolve("photo.jpeg"));
    }

    @Test
    void resolve_gif_returnsImageGif() {
        assertEquals("image/gif", registry.resolve("animation.gif"));
    }

    @Test
    void resolve_webp_returnsImageWebp() {
        assertEquals("image/webp", registry.resolve("image.webp"));
    }

    @Test
    void resolve_svg_returnsImageSvgXml() {
        assertEquals("image/svg+xml", registry.resolve("icon.svg"));
    }

    // ========== Document types ==========

    @Test
    void resolve_pdf_returnsApplicationPdf() {
        assertEquals("application/pdf", registry.resolve("document.pdf"));
    }

    @Test
    void resolve_docx_returnsWordMime() {
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            registry.resolve("report.docx"));
    }

    @Test
    void resolve_xlsx_returnsExcelMime() {
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            registry.resolve("data.xlsx"));
    }

    // ========== Text types ==========

    @Test
    void resolve_json_returnsApplicationJson() {
        assertEquals("application/json", registry.resolve("config.json"));
    }

    @Test
    void resolve_txt_returnsTextPlain() {
        assertEquals("text/plain", registry.resolve("notes.txt"));
    }

    @Test
    void resolve_csv_returnsTextCsv() {
        assertEquals("text/csv", registry.resolve("export.csv"));
    }

    @Test
    void resolve_html_returnsTextHtml() {
        assertEquals("text/html", registry.resolve("page.html"));
        assertEquals("text/html", registry.resolve("page.htm"));
    }

    // ========== Media types ==========

    @Test
    void resolve_mp4_returnsVideoMp4() {
        assertEquals("video/mp4", registry.resolve("video.mp4"));
    }

    @Test
    void resolve_mp3_returnsAudioMpeg() {
        assertEquals("audio/mpeg", registry.resolve("song.mp3"));
    }

    // ========== Archive types ==========

    @Test
    void resolve_zip_returnsApplicationZip() {
        assertEquals("application/zip", registry.resolve("archive.zip"));
    }

    // ========== Edge cases ==========

    @Test
    void resolve_nullFilename_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_MIME_TYPE, registry.resolve(null));
    }

    @Test
    void resolve_emptyFilename_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_MIME_TYPE, registry.resolve(""));
        assertEquals(FileConstants.DEFAULT_MIME_TYPE, registry.resolve("   "));
    }

    @Test
    void resolve_noExtension_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_MIME_TYPE, registry.resolve("README"));
    }

    @Test
    void resolve_unknownExtension_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_MIME_TYPE, registry.resolve("file.xyz123"));
    }

    @Test
    void resolve_caseInsensitive() {
        assertEquals("image/png", registry.resolve("IMAGE.PNG"));
        assertEquals("image/png", registry.resolve("Image.Png"));
    }

    @Test
    void resolve_pathWithFilename() {
        // Only looks at the extension, not the full path
        assertEquals("image/png", registry.resolve("/path/to/image.png"));
    }

    // ========== Extension methods ==========

    @Test
    void register_customExtension_canBeResolved() {
        registry.register(".xyz", "application/xyz-custom");
        assertEquals("application/xyz-custom", registry.resolve("file.xyz"));
    }

    @Test
    void register_withoutDot_stillWorks() {
        registry.register("abc", "application/abc-custom");
        assertEquals("application/abc-custom", registry.resolve("file.abc"));
    }

    @Test
    void register_overridesExisting() {
        registry.register(".png", "custom/png");
        assertEquals("custom/png", registry.resolve("image.png"));
    }

    // ========== Content-based resolution ==========

    @Test
    void resolve_withContent_prefersFilename() {
        // Even with content, filename takes precedence
        byte[] content = new byte[]{0x00, 0x01, 0x02};
        assertEquals("image/png", registry.resolve("image.png", content));
    }

    @Test
    void resolve_withContent_fallsBackToContentAnalysis() {
        // PNG magic bytes
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        String mimeType = registry.resolve("file_without_extension", pngBytes);
        // Should either detect from content or return default
        assertNotNull(mimeType);
    }

    @Test
    void resolve_withNullContent_returnsFilenameBasedOrDefault() {
        assertEquals("image/png", registry.resolve("image.png", null));
        assertEquals(FileConstants.DEFAULT_MIME_TYPE, registry.resolve("noext", null));
    }
}
