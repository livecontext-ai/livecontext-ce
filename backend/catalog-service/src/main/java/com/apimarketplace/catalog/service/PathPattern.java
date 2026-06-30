package com.apimarketplace.catalog.service;

import java.util.regex.Pattern;

/**
 * Shared utility for canonicalising JSON path strings emitted by the response
 * walker. Concrete indices like {@code items[3].about} collapse to the
 * wildcard form {@code items[].about}. Used by both:
 * <ul>
 *   <li>{@link ResponseShaper#shouldExpand} - to match an expand pattern
 *       against a runtime path with concrete indices.</li>
 *   <li>{@link ResponseShaper} pattern aggregation - to collapse 10
 *       per-index truncation paths into one pattern entry.</li>
 * </ul>
 *
 * <p>Extracted to a single util so the two normalisers cannot drift.
 */
public final class PathPattern {

    private static final Pattern INDEX = Pattern.compile("\\[\\d+]");

    private PathPattern() {}

    /**
     * Replace every concrete index {@code [N]} in the path with the wildcard
     * {@code []}. Identity for already-canonicalised paths.
     */
    public static String canonicalize(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        return INDEX.matcher(path).replaceAll("[]");
    }
}
