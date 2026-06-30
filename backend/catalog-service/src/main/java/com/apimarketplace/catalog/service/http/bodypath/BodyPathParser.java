package com.apimarketplace.catalog.service.http.bodypath;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BodyPathParser {

    private static final Pattern SEGMENT = Pattern.compile(
            "^([a-zA-Z_][a-zA-Z0-9_]*)(?:\\[(\\d*)\\])?$");

    private BodyPathParser() {}

    public static boolean isStructuredPath(String path) {
        return path != null && (path.indexOf('.') >= 0 || path.indexOf('[') >= 0);
    }

    public static List<BodyPathToken> parse(String path) {
        if (path == null || path.isBlank()) {
            throw new BodyPathException.Grammar(String.valueOf(path), "path is null or blank");
        }
        String[] segments = path.split("\\.", -1);
        List<BodyPathToken> tokens = new ArrayList<>(segments.length);
        int mappedArrayCount = 0;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new BodyPathException.Grammar(path, "empty segment");
            }
            Matcher m = SEGMENT.matcher(segment);
            if (!m.matches()) {
                throw new BodyPathException.Grammar(path, "invalid segment '" + segment + "'");
            }
            String key = m.group(1);
            String bracketBody = m.group(2);
            if (bracketBody == null) {
                tokens.add(new BodyPathToken.Literal(key));
            } else if (bracketBody.isEmpty()) {
                mappedArrayCount++;
                if (mappedArrayCount > 1) {
                    throw new BodyPathException.Grammar(path,
                            "only one '[]' (array-mapping) allowed per path");
                }
                tokens.add(new BodyPathToken.MappedArray(key));
            } else {
                int idx;
                try {
                    idx = Integer.parseInt(bracketBody);
                } catch (NumberFormatException e) {
                    throw new BodyPathException.Grammar(path,
                            "invalid index '" + bracketBody + "' in segment '" + segment + "'");
                }
                if (idx < 0) {
                    throw new BodyPathException.Grammar(path,
                            "negative index " + idx + " in segment '" + segment + "'");
                }
                tokens.add(new BodyPathToken.IndexedArray(key, idx));
            }
        }
        return tokens;
    }
}
