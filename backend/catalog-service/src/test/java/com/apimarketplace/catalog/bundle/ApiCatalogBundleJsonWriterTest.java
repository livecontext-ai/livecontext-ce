package com.apimarketplace.catalog.bundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-compatibility guard for the streamed bundle envelope.
 *
 * <p>The download used to return an {@link ApiCatalogSignedBundle} record and
 * let Jackson serialise it, which required building the whole base64 payload as
 * a String first. The writer streams that base64 instead. Already-deployed CE
 * instances parse the response with the old reader, so the bytes on the wire
 * must not move: these tests pin the JSON against what Jackson produces from
 * the record it replaced.
 */
@DisplayName("ApiCatalogBundleJsonWriter - streams the envelope without changing the wire format")
class ApiCatalogBundleJsonWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ApiCatalogBundleService.RawBundle raw(byte[] payload) {
        return new ApiCatalogBundleService.RawBundle(
                1788700000000L, 1, "d1e2f3", "sig==", "key-1", "cloud",
                977, 32661, 128_000L, payload.length,
                () -> new java.io.ByteArrayInputStream(payload));
    }

    @Test
    @DisplayName("a stream that runs out early FAILS instead of emitting a short, well-formed payload")
    void aStreamShorterThanItsDeclaredLengthFails() {
        // The reads happen after the response is committed, so this is the last
        // line of defence: writeBinary is given the length the row measured, and
        // refuses to finish the field if the stream cannot deliver it. Drop that
        // argument (the read-to-EOF form) and the endpoint would serve a
        // truncated payload as a complete, correctly-framed, ETag-stamped 200.
        byte[] declared = new byte[5_000];
        new Random(31).nextBytes(declared);
        ApiCatalogBundleService.RawBundle short_ = new ApiCatalogBundleService.RawBundle(
                1788700000000L, 1, "d1e2f3", "sig==", "key-1", "cloud",
                977, 32661, 128_000L, declared.length,
                () -> new java.io.ByteArrayInputStream(java.util.Arrays.copyOf(declared, 100)));

        assertThatThrownBy(() -> write(short_))
                .as("a payload shorter than the row said must not reach a client as a valid bundle")
                .hasMessageContaining("Too few bytes");
    }

    private static String write(ApiCatalogBundleService.RawBundle bundle) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiCatalogBundleJsonWriter.write(bundle, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "payload of {0} bytes")
    // 9000 divides by 3 (no padding), 9001 and 9002 exercise the "=" and "=="
    // tails: byte-identity has to hold for every padding case, not just the
    // one that happens to need none.
    @ValueSource(ints = {9_000, 9_001, 9_002})
    @DisplayName("Produces byte-identical JSON to serialising the record it replaced")
    void matchesTheRecordSerialisationExactly(int size) throws Exception {
        byte[] payload = new byte[size];
        new Random(42).nextBytes(payload);
        ApiCatalogBundleService.RawBundle bundle = raw(payload);

        String streamed = write(bundle);

        String legacy = MAPPER.writeValueAsString(new ApiCatalogSignedBundle(
                bundle.version(), bundle.schemaVersion(), bundle.checksum(), bundle.signature(),
                bundle.signingKeyId(), bundle.issuer(), bundle.apiCount(), bundle.toolCount(),
                bundle.rawBytesSize(), Base64.getEncoder().encodeToString(payload)));
        assertThat(streamed).isEqualTo(legacy);
    }

    @Test
    @DisplayName("Round-trips back into the record an old CE reader expects")
    void roundTripsIntoTheSignedBundleRecord() throws Exception {
        byte[] payload = "the gzipped canonical catalog".getBytes(StandardCharsets.UTF_8);

        ApiCatalogSignedBundle parsed = MAPPER.readValue(write(raw(payload)), ApiCatalogSignedBundle.class);

        assertThat(parsed.version()).isEqualTo(1788700000000L);
        assertThat(parsed.checksum()).isEqualTo("d1e2f3");
        assertThat(parsed.signature()).isEqualTo("sig==");
        assertThat(parsed.signingKeyId()).isEqualTo("key-1");
        assertThat(parsed.issuer()).isEqualTo("cloud");
        assertThat(parsed.apiCount()).isEqualTo(977);
        assertThat(parsed.toolCount()).isEqualTo(32661);
        assertThat(parsed.rawBytesSize()).isEqualTo(128_000L);
        assertThat(Base64.getDecoder().decode(parsed.payloadBase64())).isEqualTo(payload);
    }

    @Test
    @DisplayName("Base64 carries no line breaks, so a strict decoder accepts it verbatim")
    void emitsUnbrokenBase64() throws Exception {
        // java.util.Base64.getDecoder() rejects line feeds; a MIME-chunked
        // encoder would silently break every CE that decodes with it.
        byte[] payload = new byte[4_096];
        new Random(7).nextBytes(payload);

        ApiCatalogSignedBundle parsed = MAPPER.readValue(write(raw(payload)), ApiCatalogSignedBundle.class);

        assertThat(parsed.payloadBase64()).doesNotContain("\n").doesNotContain("\r");
        assertThat(Base64.getDecoder().decode(parsed.payloadBase64())).isEqualTo(payload);
    }

    @Test
    @DisplayName("Encodes incrementally: a payload far larger than any buffer still round-trips")
    void handlesAMultiChunkPayload() throws Exception {
        byte[] payload = new byte[3 * 1024 * 1024];
        new Random(1).nextBytes(payload);

        ApiCatalogSignedBundle parsed = MAPPER.readValue(write(raw(payload)), ApiCatalogSignedBundle.class);

        assertThat(Base64.getDecoder().decode(parsed.payloadBase64())).isEqualTo(payload);
    }

    @Test
    @DisplayName("A client that disconnects mid-write propagates the IOException and does NOT close the container's stream")
    void midWriteFailurePropagatesWithoutClosingTheTarget() {
        // The servlet container owns the response stream, which is why the
        // generator runs with AUTO_CLOSE_TARGET disabled. If the writer closed
        // it on the way out of a failed write, the container would be left
        // finishing a response on a stream someone else already closed.
        AtomicBoolean closed = new AtomicBoolean(false);
        OutputStream failing = new OutputStream() {
            private int written;
            @Override public void write(int b) throws IOException {
                if (++written > 64) throw new IOException("Broken pipe");
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                written += len;
                if (written > 64) throw new IOException("Broken pipe");
            }
            @Override public void close() {
                closed.set(true);
            }
        };
        byte[] payload = new byte[64_000];
        new Random(3).nextBytes(payload);

        assertThatThrownBy(() -> ApiCatalogBundleJsonWriter.write(raw(payload), failing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Broken pipe");
        assertThat(closed).isFalse();
    }

    @Test
    @DisplayName("A stream that answers a few bytes at a time still produces byte-identical base64")
    void shortReadingStreamProducesIdenticalBase64() throws Exception {
        // Every other test here feeds a ByteArrayInputStream, which always fills
        // the buffer. The real payload arrives from the database one slice at a
        // time, so the stream Jackson reads DOES return short. If writeBinary
        // mishandled that, the base64 would differ from what already-deployed CE
        // readers expect, and the signature over it would stop verifying.
        byte[] payload = new byte[5_000];
        new Random(99).nextBytes(payload);

        ApiCatalogBundleService.RawBundle bundle = new ApiCatalogBundleService.RawBundle(
                1788700000000L, 1, "d1e2f3", "sig==", "key-1", "cloud",
                977, 32661, 128_000L, payload.length,
                () -> new java.io.InputStream() {
                    private int pos;

                    @Override public int read() {
                        return pos < payload.length ? payload[pos++] & 0xFF : -1;
                    }

                    @Override public int read(byte[] b, int off, int len) {
                        if (pos >= payload.length) return -1;
                        // Never more than 7 bytes: the shape a sliced reader has,
                        // exaggerated so a buffering bug cannot hide.
                        int n = Math.min(Math.min(7, len), payload.length - pos);
                        System.arraycopy(payload, pos, b, off, n);
                        pos += n;
                        return n;
                    }
                });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiCatalogBundleJsonWriter.write(bundle, out);

        String legacy = MAPPER.writeValueAsString(new ApiCatalogSignedBundle(
                1788700000000L, 1, "d1e2f3", "sig==", "key-1", "cloud",
                977, 32661, 128_000L, Base64.getEncoder().encodeToString(payload)));
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(legacy);
    }
}
