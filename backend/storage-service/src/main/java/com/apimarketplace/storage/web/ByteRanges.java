package com.apimarketplace.storage.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an HTTP {@code Range} request header down to the one shape the signed file proxy serves:
 * a single byte range ({@code bytes=0-}, {@code bytes=100-199}, {@code bytes=-500}).
 *
 * <p>Why the proxy serves ranges at all: an interface video streams from a
 * {@code /api/files/proxy-signed} link, and a {@code <video>} element asks for ranges. Chrome
 * plays a 200 progressively but cannot seek it; Safari and iOS refuse to play it. Anything this
 * class does not recognise (several ranges, another unit, garbage) returns {@code null}, and the
 * caller answers with the whole file as a 200, which RFC 9110 explicitly allows.
 */
public final class ByteRanges {

    private static final Pattern SINGLE = Pattern.compile("^\\s*bytes\\s*=\\s*(\\d*)\\s*-\\s*(\\d*)\\s*$");

    private ByteRanges() {
    }

    /**
     * @return the normalised {@code bytes=start-end} spec to forward to the object store, or
     *         {@code null} when the header is absent or not a single satisfiable-looking range
     */
    public static String singleRange(String header) {
        if (header == null) return null;
        Matcher m = SINGLE.matcher(header);
        if (!m.matches()) return null;
        String start = m.group(1);
        String end = m.group(2);
        if (start.isEmpty() && end.isEmpty()) return null;
        if (!start.isEmpty() && !end.isEmpty()) {
            try {
                if (Long.parseLong(start) > Long.parseLong(end)) return null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (start.isEmpty() && "0".equals(end.replaceFirst("^0+(?=.)", ""))) {
            // bytes=-0 asks for zero bytes: nothing to serve as a range.
            return null;
        }
        return "bytes=" + start + "-" + end;
    }
}
