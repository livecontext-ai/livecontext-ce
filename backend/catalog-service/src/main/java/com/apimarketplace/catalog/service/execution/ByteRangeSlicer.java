package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Cuts one declared slice out of a file's bytes, for endpoints that upload a large
 * file in fixed-size parts.
 *
 * <p>{@code execution.request.rangeFirstByteParam} and {@code rangeLastByteParam} name
 * the two request parameters that carry the INCLUSIVE first and last byte of the part
 * to send. Declare neither and the whole file goes, exactly as before.
 *
 * <p>It lives on its own because TWO body encoders need it and they encode nothing
 * alike. {@link RawBinaryBodyEncoder} serves providers that hand back one pre-signed
 * URL per chunk (LinkedIn's Videos API, 4 MB parts); {@link MultipartBodyEncoder}
 * serves providers that keep ONE endpoint and identify the part by an index field in
 * the form (X's {@code /2/media/upload/{id}/append}, which carries {@code media} and
 * {@code segment_index} together and therefore cannot be raw binary).
 *
 * <p><b>A range that cannot be honoured FAILS THE CALL</b>, by throwing
 * {@link ByteRangeException}. Sending everything to a part that asked for a slice
 * succeeds at the transport level: each call answers 2xx and the upload is only found to
 * be corrupt at finalize time or on playback. Sending an empty slice instead would leave
 * the provider to decide what a zero-byte part means, which is a guess about someone
 * else's API - and on a multipart body it is not even an empty request, only an empty
 * part in an otherwise complete form. A failed call names the two missing parameters and
 * stops, which is the only outcome that is both safe and diagnosable.
 */
@Slf4j
final class ByteRangeSlicer {

    /** Warn above this: the whole file is buffered in heap, and a ranged upload buffers
     *  it once PER PART because StorageClient has no ranged read. */
    static final long LARGE_FILE_WARN_BYTES = 50L * 1024 * 1024;

    private ByteRangeSlicer() {
    }

    /**
     * @param who the calling encoder, so the message says which body shape refused
     * @return the declared slice, or the input untouched when no range is declared
     * @throws ByteRangeException when a range is declared and cannot be honoured
     */
    static byte[] slice(byte[] bytes, JsonNode requestSpec, Map<String, Object> parameters, String who) {
        String firstParam = requestSpec.path("rangeFirstByteParam").asText("");
        String lastParam = requestSpec.path("rangeLastByteParam").asText("");
        if (firstParam.isBlank() || lastParam.isBlank()) {
            return bytes;
        }
        Long first = asLong(parameters.get(firstParam));
        Long last = asLong(parameters.get(lastParam));
        if (first == null || last == null) {
            // Declared but not supplied: the caller meant to send a part and did not say which.
            throw new ByteRangeException(who + ": this endpoint uploads one part at a time and "
                + "declares the byte range parameters '" + firstParam + "' and '" + lastParam
                + "', but the call supplied " + firstParam + "=" + parameters.get(firstParam)
                + " and " + lastParam + "=" + parameters.get(lastParam) + ". Give both bounds "
                + "(inclusive); a single-part upload is " + firstParam + "=0, " + lastParam
                + "=<total bytes - 1>.");
        }
        if (first < 0 || last < first) {
            throw new ByteRangeException(who + ": invalid byte range " + first + "-" + last
                + " (the first byte must be >= 0 and <= the last).");
        }
        if (first >= bytes.length) {
            throw new ByteRangeException(who + ": byte range starts at " + first
                + " but the file is only " + bytes.length + " bytes.");
        }
        // The final part's declared lastByte can run past the end when a provider computes ranges
        // as fixed-size blocks. Clamping sends the real tail; refusing would fail every upload
        // whose size is not an exact multiple of the block size.
        long end = Math.min(last, bytes.length - 1L);
        int from = first.intValue();
        int to = (int) end + 1;
        if (end < last) {
            log.debug("{}: byte range {}-{} clamped to {}-{} (file is {} bytes)",
                who, first, last, first, end, bytes.length);
        }
        byte[] slice = new byte[to - from];
        System.arraycopy(bytes, from, slice, 0, slice.length);
        return slice;
    }

    /** A range bound as sent: a JSON number, or a string a template resolved to. */
    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
