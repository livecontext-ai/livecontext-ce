package com.apimarketplace.storage.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the chat/DM image bug: a key / fileName with spaces/accents (e.g. a
 * screenshot "Capture d'écran ...png") was double-encoded - {@code toUriString()} encoded
 * once (space->%20) then {@code RestTemplate} re-encoded the resulting String (%->%25) =>
 * %2520, so storage-service looked up the wrong key and 404'd => the image never rendered.
 * The fix builds a {@code java.net.URI} (already encoded) so RestTemplate does not re-encode.
 *
 * <p>Lives in conversation-service (it consumes storage-client for chat/DM attachments, and
 * storage-client has no test harness of its own). No Mockito - a tiny RestTemplate subclass
 * captures the URI handed to {@code exchange(URI, ...)}.
 */
class StorageClientKeyEncodingTest {

    private static final String KEY =
            "1/general/chat/cea2a13a_Capture d’écran 2026-06-16 114029.png";
    private static final String FILENAME = "Capture d’écran 2026-06-16 114029.png";

    /** Captures the URI passed to exchange(URI, ...) and returns an empty OK response. */
    static class CapturingRestTemplate extends RestTemplate {
        URI capturedUri;

        @Override
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method,
                                              HttpEntity<?> requestEntity, Class<T> responseType) {
            this.capturedUri = url;
            return ResponseEntity.ok(null);
        }
    }

    private static void assertSingleEncodedContaining(URI uri, String decodedNeedle) {
        assertNotNull(uri, "exchange(URI,...) should have been called with a URI, not a String");
        String wire = uri.toASCIIString();
        assertTrue(wire.contains("%20"), "space must be encoded once as %20: " + wire);
        assertFalse(wire.contains("%2520"), "must NOT double-encode (%2520) - that is the 404 bug: " + wire);
        assertTrue(uri.getQuery().contains(decodedNeedle),
                "decoded query must round-trip to the original value: " + uri.getQuery());
    }

    @Test
    @DisplayName("download() single-encodes a key with spaces/accents - no %2520 double-encode")
    void downloadDoesNotDoubleEncodeKey() {
        CapturingRestTemplate rt = new CapturingRestTemplate();
        new StorageClient(rt, "http://storage:8082").download("1", KEY);
        assertSingleEncodedContaining(rt.capturedUri, KEY);
    }

    @Test
    @DisplayName("exists() single-encodes a key with special chars")
    void existsDoesNotDoubleEncodeKey() {
        CapturingRestTemplate rt = new CapturingRestTemplate();
        new StorageClient(rt, "http://storage:8082").exists("1", KEY);
        assertSingleEncodedContaining(rt.capturedUri, KEY);
    }

    @Test
    @DisplayName("delete() single-encodes a key with special chars")
    void deleteDoesNotDoubleEncodeKey() {
        CapturingRestTemplate rt = new CapturingRestTemplate();
        new StorageClient(rt, "http://storage:8082").delete("1", KEY);
        assertSingleEncodedContaining(rt.capturedUri, KEY);
    }

    @Test
    @DisplayName("generateDownloadUrl() single-encodes the key (presign)")
    void generateDownloadUrlDoesNotDoubleEncodeKey() {
        CapturingRestTemplate rt = new CapturingRestTemplate();
        new StorageClient(rt, "http://storage:8082").generateDownloadUrl("1", KEY, 15);
        assertSingleEncodedContaining(rt.capturedUri, KEY);
    }

    @Test
    @DisplayName("upload() single-encodes the fileName query param (the user-facing screenshot name)")
    void uploadDoesNotDoubleEncodeFileName() {
        CapturingRestTemplate rt = new CapturingRestTemplate();
        new StorageClient(rt, "http://storage:8082")
                .upload("1", "wf-1", "run-1", "step", FILENAME, "image/png", new byte[]{1, 2, 3});
        assertSingleEncodedContaining(rt.capturedUri, FILENAME);
    }
}
