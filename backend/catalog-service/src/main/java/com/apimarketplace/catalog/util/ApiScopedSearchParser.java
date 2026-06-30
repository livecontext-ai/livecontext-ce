package com.apimarketplace.catalog.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses catalog search inputs that optionally scope a keyword query to one or more APIs.
 */
public final class ApiScopedSearchParser {

    private static final int MAX_API_FILTERS = 10;
    private static final Pattern BRACKET_PREFIX = Pattern.compile("^\\s*\\[([^\\]]+)]\\s*(?:[,;:\\-])?\\s*(.*)$");

    private ApiScopedSearchParser() {
    }

    public static ParsedSearch parse(String query, Object api, Object apis) {
        String rawQuery = query == null ? "" : query.trim();

        List<String> explicitFilters = new ArrayList<>();
        explicitFilters.addAll(extractFilters(api));
        explicitFilters.addAll(extractFilters(apis));
        explicitFilters = normalizeFilters(explicitFilters);
        if (!explicitFilters.isEmpty()) {
            return new ParsedSearch(rawQuery, explicitFilters, false);
        }

        ParsedSearch bracketParsed = parseBracketPrefix(rawQuery);
        if (bracketParsed.hasApiFilters()) {
            return bracketParsed;
        }

        ParsedSearch commaParsed = parseCommaPrefix(rawQuery);
        if (commaParsed.hasApiFilters()) {
            return commaParsed;
        }

        return new ParsedSearch(rawQuery, List.of(), false);
    }

    public record ParsedSearch(String query, List<String> apiFilters, boolean inlineScope) {
        public boolean hasApiFilters() {
            return apiFilters != null && !apiFilters.isEmpty();
        }
    }

    private static ParsedSearch parseBracketPrefix(String query) {
        Matcher matcher = BRACKET_PREFIX.matcher(query);
        if (!matcher.matches()) {
            return new ParsedSearch(query, List.of(), false);
        }

        List<String> filters = normalizeFilters(splitFilterText(matcher.group(1)));
        if (filters.isEmpty()) {
            return new ParsedSearch(query, List.of(), false);
        }

        String keywordQuery = Objects.toString(matcher.group(2), "").trim();
        if (keywordQuery.isEmpty()) {
            keywordQuery = String.join(" ", filters);
        }
        return new ParsedSearch(keywordQuery, filters, true);
    }

    private static ParsedSearch parseCommaPrefix(String query) {
        List<String> parts = splitFilterText(query);
        if (parts.size() < 2 || parts.size() > MAX_API_FILTERS + 1) {
            return new ParsedSearch(query, List.of(), false);
        }

        String keywordQuery = parts.get(parts.size() - 1).trim();
        List<String> candidateFilters = normalizeFilters(parts.subList(0, parts.size() - 1));
        if (keywordQuery.isEmpty() || candidateFilters.isEmpty()) {
            return new ParsedSearch(query, List.of(), false);
        }
        if (candidateFilters.stream().anyMatch(filter -> !looksLikeCommaApiName(filter))) {
            return new ParsedSearch(query, List.of(), false);
        }

        return new ParsedSearch(keywordQuery, candidateFilters, true);
    }

    private static List<String> extractFilters(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<String> filters = new ArrayList<>();
            for (Object item : collection) {
                filters.addAll(extractFilters(item));
            }
            return filters;
        }
        if (value instanceof String text) {
            return splitFilterText(text);
        }
        return splitFilterText(String.valueOf(value));
    }

    private static List<String> splitFilterText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = stripWrappingBrackets(text.trim());
        String[] parts = cleaned.split("[,;]");
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            String item = stripQuotes(part.trim());
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return out;
    }

    private static String stripWrappingBrackets(String text) {
        if (text.length() >= 2 && text.startsWith("[") && text.endsWith("]")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static String stripQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    private static List<String> normalizeFilters(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String raw : filters) {
            if (raw == null) {
                continue;
            }
            String filter = stripQuotes(raw.trim());
            if (filter.isBlank()) {
                continue;
            }
            String key = compactIdentifier(filter);
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            normalized.add(filter);
            if (normalized.size() >= MAX_API_FILTERS) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private static boolean looksLikeApiName(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.length() > 80) {
            return false;
        }
        return trimmed.chars().allMatch(ch ->
            Character.isLetterOrDigit(ch)
                || Character.isWhitespace(ch)
                || ch == '-'
                || ch == '_'
                || ch == '.'
                || ch == '+'
        );
    }

    private static boolean looksLikeCommaApiName(String value) {
        return looksLikeApiName(value) && value.trim().chars().noneMatch(Character::isWhitespace);
    }

    public static String compactIdentifier(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
