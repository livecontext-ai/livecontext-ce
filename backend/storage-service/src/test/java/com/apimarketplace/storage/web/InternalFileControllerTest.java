package com.apimarketplace.storage.web;

import com.apimarketplace.storage.service.file.DownloadStream;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.service.file.StorageStreamingMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Streaming-pipeline regression tests for
 * {@link InternalFileController#download}. Used by orchestrator-service
 * (storage-client) for inter-service file fetches; the storage-client
 * still consumes the response as bytes via
 * {@code RestTemplate.exchange(..., byte[].class)}, but the wire is now
 * streamed → server-side heap stays flat.
 *
 * <p>Locks the post-2026-05-04-OOM contract:
 * <ul>
 *   <li>{@code openStream(key)} (not {@code download(key)}) drives the response</li>
 *   <li>Body bytes equal the upstream stream verbatim</li>
 *   <li>{@code Content-Type} preserved from S3 metadata when known, falls back
 *       to {@code application/octet-stream}</li>
 *   <li>{@code Content-Length} forwarded when upstream advertises one</li>
 *   <li>The upstream {@code InputStream} is closed after writing</li>
 * </ul>
 */
@DisplayName("InternalFileController.download - streaming pipeline")
@ExtendWith(MockitoExtension.class)
class InternalFileControllerTest {

    @Mock FileStorageService fileStorageService;

    private final StorageStreamingMetrics streamingMetrics = new StorageStreamingMetrics(null);
    private InternalFileController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalFileController(fileStorageService, streamingMetrics);
    }

    @Test
    @DisplayName("stream available + contentType set → 200 + body bytes match + Content-Type forwarded + Content-Length forwarded")
    void streamReturnsContentWithMetadata() throws IOException {
        String key = "1/general/catalog-binary/file.png";
        byte[] payload = new byte[]{'P', 'N', 'G', 1, 2, 3};
        CloseTrackingStream upstream = new CloseTrackingStream(payload);
        DownloadStream ds = new DownloadStream(upstream, payload.length, "image/png");
        when(fileStorageService.openStream(key)).thenReturn(Optional.of(ds));

        ResponseEntity<StreamingResponseBody> response = controller.download(key, "1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("image/png");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH))
                .isEqualTo(String.valueOf(payload.length));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(payload);
        assertThat(upstream.closed).as("upstream must be closed after streaming").isTrue();
    }

    @Test
    @DisplayName("openStream returns empty → 404")
    void notFoundReturns404() {
        String key = "1/general/catalog-binary/missing.bin";
        when(fileStorageService.openStream(key)).thenReturn(Optional.empty());

        ResponseEntity<StreamingResponseBody> response = controller.download(key, "1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Missing/blank contentType → falls back to application/octet-stream (back-compat with legacy behavior)")
    void missingContentTypeFallsBackToOctetStream() throws IOException {
        String key = "1/general/catalog-binary/raw.bin";
        DownloadStream ds = new DownloadStream(
                new ByteArrayInputStream(new byte[]{1, 2}), 2L, /* contentType */ null);
        when(fileStorageService.openStream(key)).thenReturn(Optional.of(ds));

        ResponseEntity<StreamingResponseBody> response = controller.download(key, "1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("Blank contentType → falls back to application/octet-stream")
    void blankContentTypeFallsBack() throws IOException {
        String key = "1/general/catalog-binary/raw.bin";
        DownloadStream ds = new DownloadStream(
                new ByteArrayInputStream(new byte[]{1}), 1L, "");
        when(fileStorageService.openStream(key)).thenReturn(Optional.of(ds));

        ResponseEntity<StreamingResponseBody> response = controller.download(key, "1");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("contentLength <= 0 → Content-Length header omitted (chunked encoding fallback)")
    void contentLengthOmittedWhenUnknown() {
        String key = "1/general/catalog-binary/multipart.bin";
        DownloadStream ds = new DownloadStream(
                new ByteArrayInputStream(new byte[]{1, 2}), -1L, "application/octet-stream");
        when(fileStorageService.openStream(key)).thenReturn(Optional.of(ds));

        ResponseEntity<StreamingResponseBody> response = controller.download(key, "1");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)).isNull();
    }

    @Test
    @DisplayName("Client disconnect mid-stream → upstream closed (S3 connection released)")
    void clientDisconnectClosesUpstream() throws IOException {
        String key = "1/general/catalog-binary/x.png";
        byte[] payload = new byte[]{1, 2, 3, 4};
        CloseTrackingStream upstream = new CloseTrackingStream(payload);
        DownloadStream ds = new DownloadStream(upstream, payload.length, "image/png");
        when(fileStorageService.openStream(key)).thenReturn(Optional.of(ds));

        ResponseEntity<StreamingResponseBody> response = controller.download(key, "1");

        OutputStream brokenOut = new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("Broken pipe (simulated)");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe (simulated)");
            }
        };

        assertThatThrownBy(() -> response.getBody().writeTo(brokenOut))
                .isInstanceOf(IOException.class);
        assertThat(upstream.closed)
                .as("upstream S3 stream must be closed on client-disconnect IOException")
                .isTrue();
    }

    @Test
    @DisplayName("Streaming body handles 10 MB upstream - byte-exact replay, upstream closed")
    void streamingHandles10MbWithoutBuffering() throws IOException {
        String key = "1/general/catalog-binary/bigfile.bin";
        byte[] payload = new byte[10 * 1024 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);
        CloseTrackingStream upstream = new CloseTrackingStream(payload);
        DownloadStream ds = new DownloadStream(upstream, payload.length, "application/octet-stream");
        when(fileStorageService.openStream(key)).thenReturn(Optional.of(ds));

        ResponseEntity<StreamingResponseBody> response = controller.download(key, "1");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.size()).isEqualTo(payload.length);
        byte[] result = out.toByteArray();
        assertThat(result[0]).isEqualTo(payload[0]);
        assertThat(result[payload.length / 2]).isEqualTo(payload[payload.length / 2]);
        assertThat(result[payload.length - 1]).isEqualTo(payload[payload.length - 1]);
        assertThat(upstream.closed).isTrue();
    }

    /** {@link InputStream} wrapper that records whether {@link #close()} ran. */
    private static final class CloseTrackingStream extends InputStream {
        private final ByteArrayInputStream delegate;
        boolean closed = false;

        CloseTrackingStream(byte[] payload) {
            this.delegate = new ByteArrayInputStream(payload);
        }

        @Override public int read() { return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) { return delegate.read(b, off, len); }
        @Override public void close() throws IOException { closed = true; delegate.close(); }
    }
}
