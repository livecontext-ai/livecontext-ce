package com.apimarketplace.orchestrator.utils.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileNameExtractorTest {

    // ========== fromUrl tests ==========

    @Test
    void fromUrl_simpleUrl_extractsFilename() {
        assertEquals("report.pdf", FileNameExtractor.fromUrl("https://example.com/report.pdf"));
    }

    @Test
    void fromUrl_urlWithPath_extractsFilename() {
        assertEquals("document.docx",
            FileNameExtractor.fromUrl("https://example.com/path/to/document.docx"));
    }

    @Test
    void fromUrl_urlWithQueryParams_stripsParams() {
        assertEquals("image.png",
            FileNameExtractor.fromUrl("https://example.com/image.png?token=abc123&size=large"));
    }

    @Test
    void fromUrl_urlEncodedFilename_decodes() {
        assertEquals("my report.pdf",
            FileNameExtractor.fromUrl("https://example.com/my%20report.pdf"));
    }

    @Test
    void fromUrl_urlWithFragment_handlesCorrectly() {
        assertEquals("doc.html",
            FileNameExtractor.fromUrl("https://example.com/doc.html#section1"));
    }

    @Test
    void fromUrl_trailingSlash_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_FILENAME,
            FileNameExtractor.fromUrl("https://example.com/folder/"));
    }

    @Test
    void fromUrl_rootPath_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_FILENAME,
            FileNameExtractor.fromUrl("https://example.com/"));
    }

    @Test
    void fromUrl_nullUrl_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_FILENAME, FileNameExtractor.fromUrl(null));
    }

    @Test
    void fromUrl_emptyUrl_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_FILENAME, FileNameExtractor.fromUrl(""));
        assertEquals(FileConstants.DEFAULT_FILENAME, FileNameExtractor.fromUrl("   "));
    }

    @Test
    void fromUrl_invalidUrl_returnsDefault() {
        assertEquals(FileConstants.DEFAULT_FILENAME,
            FileNameExtractor.fromUrl("not a valid url"));
    }

    @Test
    void fromUrl_httpUrl_works() {
        assertEquals("file.txt",
            FileNameExtractor.fromUrl("http://example.com/file.txt"));
    }

    @Test
    void fromUrl_complexPath_extractsCorrectly() {
        assertEquals("final_report_v2.pdf",
            FileNameExtractor.fromUrl("https://cdn.example.com/uploads/2024/01/final_report_v2.pdf"));
    }

}
