package com.apimarketplace.catalog.bundle;

import java.io.IOException;
import java.io.InputStream;

/**
 * Presents a stored bundle payload as a stream, fetching it one slice at a time.
 *
 * <p>Holds at most one slice, so the memory a download costs no longer scales
 * with the catalog. See {@link ApiCatalogBundleChunkReader} for why the payload
 * is sliced rather than read whole or streamed from an open connection.
 *
 * <p>Not thread-safe: one instance serves one response.
 */
final class ChunkedPayloadInputStream extends InputStream {

    private final ApiCatalogBundleChunkReader reader;
    private final long version;
    private final long totalLength;
    private final int chunkSize;

    private byte[] chunk = new byte[0];
    private int chunkPos;
    private long consumed;
    private boolean closed;

    ChunkedPayloadInputStream(ApiCatalogBundleChunkReader reader, long version, long totalLength, int chunkSize) {
        this.reader = reader;
        this.version = version;
        this.totalLength = totalLength;
        this.chunkSize = chunkSize;
    }

    @Override
    public int read() throws IOException {
        if (!ensureChunk()) {
            return -1;
        }
        return chunk[chunkPos++] & 0xFF;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (!ensureChunk()) {
            return -1;
        }
        int available = Math.min(length, chunk.length - chunkPos);
        System.arraycopy(chunk, chunkPos, destination, offset, available);
        chunkPos += available;
        return available;
    }

    /** Loads the next slice when the current one is spent. False at the end. */
    private boolean ensureChunk() throws IOException {
        if (closed) {
            // Without this, a read after close would quietly start fetching again
            // from where it left off - which is not what "released" means.
            return false;
        }
        if (chunkPos < chunk.length) {
            return true;
        }
        if (consumed >= totalLength) {
            return false;
        }
        int want = (int) Math.min(chunkSize, totalLength - consumed);
        byte[] next = reader.readChunk(version, consumed, want);
        if (next.length == 0) {
            // The row lost its payload mid-read. Failing is the honest answer:
            // a short body would be served as a complete, signed bundle.
            throw new IOException("bundle " + version + " returned no bytes at offset " + consumed
                    + " of " + totalLength + " - payload changed or disappeared mid-read");
        }
        chunk = next;
        chunkPos = 0;
        consumed += next.length;
        return true;
    }

    /**
     * Releases the held slice and ends the stream.
     *
     * <p>The writer closes it once the response is written; without this the last
     * slice stays reachable for as long as anything holds the stream. Reads after
     * close report the end rather than resuming, so a closed stream cannot go on
     * issuing database queries.
     */
    @Override
    public void close() {
        closed = true;
        chunk = new byte[0];
        chunkPos = 0;
    }
}
