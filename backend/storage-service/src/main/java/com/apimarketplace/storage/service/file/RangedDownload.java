package com.apimarketplace.storage.service.file;

import java.io.IOException;

/**
 * One byte range of a stored object, opened by {@link FileStorageService#openStreamRange}.
 *
 * <p>{@code contentRange} is the {@code Content-Range} value the object store answered with
 * ({@code bytes 0-1023/18370402}), passed through verbatim so the HTTP response can never claim a
 * range other than the bytes it streams. {@link #body()} is owned by the caller exactly like any
 * {@link DownloadStream}: close it to release the connection.
 */
public record RangedDownload(DownloadStream body, String contentRange) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        if (body != null) {
            body.close();
        }
    }
}
