package com.apimarketplace.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stream that lets a download cost one slice instead of the whole payload.
 *
 * <p>Its contract is unforgiving: the bytes are signed, so reassembling them
 * even one byte off produces a body that looks complete, carries a valid-looking
 * envelope, and fails signature verification on every install in the fleet. The
 * cases below pin reassembly, the end of the stream, and the one failure that
 * must never be silent - a slice coming back empty mid-read.
 */
@DisplayName("ChunkedPayloadInputStream - reassembles a sliced payload exactly")
class ChunkedPayloadInputStreamTest {

    private static final long VERSION = 42L;

    /** A reader backed by an in-memory payload, sliced the way Postgres would. */
    private static ApiCatalogBundleChunkReader readerOver(byte[] payload, AtomicInteger calls) {
        ApiCatalogBundleChunkReader reader = mock(ApiCatalogBundleChunkReader.class);
        when(reader.readChunk(eq(VERSION), anyLong(), anyInt())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            long offset = invocation.getArgument(1);
            int want = invocation.getArgument(2);
            int from = (int) Math.min(offset, payload.length);
            int to = (int) Math.min((long) from + want, payload.length);
            return Arrays.copyOfRange(payload, from, to);
        });
        return reader;
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    @Test
    @DisplayName("A payload spanning many slices comes back byte-identical")
    void reassemblesAcrossManySlices() throws IOException {
        byte[] payload = randomBytes(10_000, 1);
        AtomicInteger calls = new AtomicInteger();

        byte[] read;
        try (ChunkedPayloadInputStream in =
                     new ChunkedPayloadInputStream(readerOver(payload, calls), VERSION, payload.length, 1024)) {
            read = in.readAllBytes();
        }

        assertThat(read).isEqualTo(payload);
        assertThat(calls.get()).as("10000 bytes in 1024-byte slices").isEqualTo(10);
    }

    @Test
    @DisplayName("A payload smaller than one slice needs exactly one read")
    void singleSlice() throws IOException {
        byte[] payload = randomBytes(100, 2);
        AtomicInteger calls = new AtomicInteger();

        try (ChunkedPayloadInputStream in =
                     new ChunkedPayloadInputStream(readerOver(payload, calls), VERSION, payload.length, 1024)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Byte-at-a-time reading crosses slice boundaries without losing or repeating a byte")
    void singleByteReadsCrossBoundaries() throws IOException {
        byte[] payload = randomBytes(300, 3);

        byte[] read = new byte[payload.length];
        try (ChunkedPayloadInputStream in = new ChunkedPayloadInputStream(
                readerOver(payload, new AtomicInteger()), VERSION, payload.length, 64)) {
            for (int i = 0; i < payload.length; i++) {
                int b = in.read();
                assertThat(b).isNotEqualTo(-1);
                read[i] = (byte) b;
            }
            assertThat(in.read()).as("the stream must end after exactly the payload").isEqualTo(-1);
        }
        assertThat(read).isEqualTo(payload);
    }

    @Test
    @DisplayName("An empty payload ends immediately rather than reading anything")
    void emptyPayloadEndsAtOnce() throws IOException {
        AtomicInteger calls = new AtomicInteger();

        try (ChunkedPayloadInputStream in =
                     new ChunkedPayloadInputStream(readerOver(new byte[0], calls), VERSION, 0, 1024)) {
            assertThat(in.read()).isEqualTo(-1);
            assertThat(in.readAllBytes()).isEmpty();
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("A slice that comes back empty mid-read FAILS: a short body must never pass as a signed bundle")
    void vanishingPayloadFailsLoudly() {
        // The alternative is serving a truncated payload under a correct-looking
        // envelope, which every install would then fail to verify with no clue
        // where the corruption came from.
        ApiCatalogBundleChunkReader reader = mock(ApiCatalogBundleChunkReader.class);
        when(reader.readChunk(eq(VERSION), eq(0L), anyInt())).thenReturn(new byte[64]);
        when(reader.readChunk(eq(VERSION), eq(64L), anyInt())).thenReturn(new byte[0]);

        // close() only drops the held slice and cannot fail, so nothing here
        // needs to handle a failure on the way out.
        try (ChunkedPayloadInputStream in = new ChunkedPayloadInputStream(reader, VERSION, 200, 64)) {
            assertThatThrownBy(in::readAllBytes)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("changed or disappeared mid-read");
        }
    }

    @Test
    @DisplayName("the slice size stays under G1's humongous threshold, which is the whole point")
    void theSliceSizeStaysBelowTheHumongousThreshold() {
        // G1 sizes regions at heap/2048 clamped to [1 MB, 32 MB], so a production
        // pod's ~896 MB heap gives 1 MB regions and anything over half of one -
        // 512 KB - is allocated straight into old gen. Raising this constant back
        // to 1 MB would reinstate exactly the allocation this class exists to
        // remove, and nothing else in the suite would notice.
        assertThat(ApiCatalogBundleChunkReader.CHUNK_BYTES)
                .as("a slice at or above 512 KB is itself a humongous allocation")
                .isLessThan(512 * 1024)
                .isPositive();
    }

    @Test
    @DisplayName("a reader that returns a SHORT slice still yields every byte once, in order")
    void aShortSliceIsFollowedFromWhereItActuallyEnded() throws IOException {
        // Nothing obliges the database to hand back exactly what was asked for,
        // and the whole correctness argument rests on advancing by the RETURNED
        // length rather than the requested one. Until now every stub returned a
        // full slice, so that distinction was never exercised here - only
        // indirectly, on Postgres.
        byte[] payload = randomBytes(200, 21);
        ApiCatalogBundleChunkReader reader = mock(ApiCatalogBundleChunkReader.class);
        when(reader.readChunk(eq(VERSION), anyLong(), anyInt())).thenAnswer(call -> {
            long offset = call.getArgument(1);
            int asked = call.getArgument(2);
            // Deliberately short: half of what was asked, at least one byte.
            int given = Math.max(1, Math.min(asked, (int) (payload.length - offset)) / 2);
            byte[] slice = new byte[given];
            System.arraycopy(payload, (int) offset, slice, 0, given);
            return slice;
        });

        byte[] read;
        try (ChunkedPayloadInputStream in =
                     new ChunkedPayloadInputStream(reader, VERSION, payload.length, 64)) {
            read = in.readAllBytes();
        }

        assertThat(read)
                .as("advancing by the requested length would skip or repeat bytes here")
                .isEqualTo(payload);
    }

    @Test
    @DisplayName("close() drops the held slice, so a stream kept alive after the response holds no payload")
    void closeReleasesTheHeldSlice() throws Exception {
        byte[] payload = randomBytes(200, 9);
        ChunkedPayloadInputStream in = new ChunkedPayloadInputStream(
                readerOver(payload, new AtomicInteger()), VERSION, payload.length, 64);
        assertThat(in.read()).isNotEqualTo(-1);

        in.close();

        java.lang.reflect.Field held = ChunkedPayloadInputStream.class.getDeclaredField("chunk");
        held.setAccessible(true);
        assertThat((byte[]) held.get(in))
                .as("the last slice must not stay reachable through a closed stream")
                .isEmpty();
        assertThat(in.read())
                .as("a closed stream must report the end, not quietly resume querying")
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("A short read is legal and loses nothing: repeated reads still yield the whole payload in order")
    void shortReadsStillDeliverEverything() throws IOException {
        // InputStream allows returning fewer bytes than asked. What matters is
        // not how many come back per call but that the caller, looping as the
        // contract requires, ends up with every byte once and in order.
        byte[] payload = randomBytes(200, 4);

        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        try (ChunkedPayloadInputStream in = new ChunkedPayloadInputStream(
                readerOver(payload, new AtomicInteger()), VERSION, payload.length, 64)) {
            byte[] buffer = new byte[500];
            int n;
            while ((n = in.read(buffer, 0, buffer.length)) != -1) {
                assertThat(n).as("a read must never claim more than it wrote").isPositive();
                collected.write(buffer, 0, n);
            }
        }

        assertThat(collected.toByteArray()).isEqualTo(payload);
    }
}
