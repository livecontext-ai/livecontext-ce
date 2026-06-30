package com.apimarketplace.common.web;

import java.nio.charset.StandardCharsets;

/**
 * Builds a {@code Content-Disposition} header value that is ALWAYS pure ASCII.
 *
 * <p>HTTP header values must be ISO-8859-1 (0-255). A file name containing a
 * character outside that range - e.g. a macOS screenshot {@code "Capture d'écran
 * 2026-06-16.png"} (curly apostrophe U+2019, accented {@code é}/{@code à}) - makes
 * Tomcat throw {@code IllegalArgumentException: The Unicode character [’] at code
 * point [8217] cannot be encoded} when the response header is set, so the download
 * 500s / the image never renders.
 *
 * <p>This emits BOTH a sanitized plain {@code filename="..."} (ASCII fallback for old
 * clients) AND an RFC 5987 {@code filename*=UTF-8''...} carrying the real UTF-8 name,
 * percent-encoded. Modern browsers prefer {@code filename*} (RFC 6266), so the user
 * still sees the correct name on save.
 */
public final class ContentDispositions {

    private ContentDispositions() {}

    /** {@code inline; filename="..."; filename*=UTF-8''...} - safe for any file name. */
    public static String inline(String fileName) {
        return build("inline", fileName);
    }

    /** {@code attachment; filename="..."; filename*=UTF-8''...} - safe for any file name. */
    public static String attachment(String fileName) {
        return build("attachment", fileName);
    }

    /** {@code <type>; filename="..."; filename*=UTF-8''...} where type is "inline" or "attachment". */
    public static String of(String type, String fileName) {
        return build(type, fileName);
    }

    private static String build(String type, String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? "file" : fileName;
        // ASCII fallback: replace any non-printable-ASCII char (and the quote/backslash
        // which would break the quoted-string) with '_'. Guarantees the header is ISO-8859-1.
        StringBuilder ascii = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            ascii.append(c >= 0x20 && c < 0x7F && c != '"' && c != '\\' ? c : '_');
        }
        return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + rfc5987(name);
    }

    /** Percent-encode per RFC 5987: attr-char stays literal, everything else is %XX of its UTF-8 bytes. */
    private static String rfc5987(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int u = b & 0xFF;
            // RFC 5987 attr-char = ALPHA / DIGIT / "!#$&+-.^_`|~"
            if ((u >= 'A' && u <= 'Z') || (u >= 'a' && u <= 'z') || (u >= '0' && u <= '9')
                    || "!#$&+-.^_`|~".indexOf(u) >= 0) {
                sb.append((char) u);
            } else {
                sb.append('%')
                  .append(Character.toUpperCase(Character.forDigit((u >> 4) & 0xF, 16)))
                  .append(Character.toUpperCase(Character.forDigit(u & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
