package com.apimarketplace.auth.lifecycle;

import java.net.InetAddress;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation of the untrusted values the lifecycle context endpoint receives (body fields
 * and Cloudflare headers). Every method answers {@code null} for "ignore this value", never
 * throws: a bad value is dropped, it never fails the request.
 *
 * <p>Trust note: {@code CF-IPCountry} and {@code CF-Connecting-IP} are taken as sent. The
 * origin can be reached without going through Cloudflare, so a caller can forge both. That is
 * accepted on purpose: both values are write-once, and a request can only ever write them onto
 * the caller's OWN user row (the endpoint resolves the user from its token), so a forger can
 * only mislabel their own account, never anyone else's.
 */
public final class LifecycleInputs {

    /** The app locales, and the only values the contact property {@code locale} may take. */
    public static final Set<String> SUPPORTED_LOCALES = Set.of("en", "fr", "es", "de", "pt", "zh");

    static final int MAX_UTM_LENGTH = 255;
    static final int MAX_URL_LENGTH = 1024;
    static final int MAX_TIME_ZONE_LENGTH = 64;

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern IPV4 = Pattern.compile(
            "((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])[.]){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private LifecycleInputs() {
    }

    /** One of {@link #SUPPORTED_LOCALES} (case-insensitive), else null. */
    public static String locale(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_LOCALES.contains(v) ? v : null;
    }

    /** A zone id {@link ZoneId#of} accepts, returned in its canonical spelling, else null. */
    public static String timeZone(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || v.length() > MAX_TIME_ZONE_LENGTH) return null;
        try {
            return ZoneId.of(v).getId();
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * The {@code CF-IPCountry} header as ISO-3166 alpha-2 uppercase. Cloudflare's {@code XX}
     * (unknown) and {@code T1} (Tor) carry no country and are ignored.
     */
    public static String country(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(v).matches() || "XX".equals(v)) return null;
        return v;
    }

    /**
     * The {@code CF-Connecting-IP} header when it is an IPv4 or IPv6 LITERAL, else null.
     * Never resolves a host name: an IPv6 literal is parsed by {@link InetAddress} without any
     * lookup, and nothing that is not made of hex digits, colons and dots gets that far.
     */
    public static String ip(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || v.length() > 45) return null;
        if (IPV4.matcher(v).matches()) return v;
        if (v.indexOf(':') < 0 || !IPV6_CHARS.matcher(v).matches()) return null;
        try {
            // An IPv4-mapped IPv6 literal (::ffff:a.b.c.d) parses to an Inet4Address: still valid.
            return InetAddress.getByName(v) != null ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Trimmed, blank as null, capped at {@code max} characters. */
    public static String text(String raw, int max) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }
}
