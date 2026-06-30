package com.apimarketplace.orchestrator.domain.workflow;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configuration for chat trigger message matching.
 *
 * Supported match types:
 * - ANY: Any message triggers the workflow (default)
 * - STARTS_WITH: Message must start with the specified value
 * - ENDS_WITH: Message must end with the specified value
 * - CONTAINS: Message must contain the specified value
 * - EQUALS: Message must exactly equal the specified value
 * - REGEX: Message must match the specified regex pattern
 *
 * Options:
 * - caseSensitive: Whether matching is case-sensitive (default: false)
 * - trimPrefix: For STARTS_WITH, whether to remove the prefix from the message (default: true)
 * - trimSuffix: For ENDS_WITH, whether to remove the suffix from the message (default: true)
 */
public record ChatMatchConfig(
    MatchType type,
    String value,
    boolean caseSensitive,
    boolean trimPrefix,
    boolean trimSuffix
) {

    public enum MatchType {
        ANY,
        STARTS_WITH,
        ENDS_WITH,
        CONTAINS,
        EQUALS,
        REGEX
    }

    /**
     * Default constructor with sensible defaults.
     */
    public ChatMatchConfig {
        if (type == null) {
            type = MatchType.ANY;
        }
        // Value is optional for ANY type
        if (type != MatchType.ANY && (value == null || value.isEmpty())) {
            throw new IllegalArgumentException("Match value is required for type: " + type);
        }
        // Validate regex pattern if type is REGEX
        if (type == MatchType.REGEX && value != null) {
            try {
                Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid regex pattern: " + value, e);
            }
        }
    }

    /**
     * Creates a default ANY match config.
     */
    public static ChatMatchConfig any() {
        return new ChatMatchConfig(MatchType.ANY, null, false, true, true);
    }

    /**
     * Creates a STARTS_WITH match config.
     */
    public static ChatMatchConfig startsWith(String prefix, boolean caseSensitive) {
        return new ChatMatchConfig(MatchType.STARTS_WITH, prefix, caseSensitive, true, true);
    }

    /**
     * Creates an ENDS_WITH match config.
     */
    public static ChatMatchConfig endsWith(String suffix, boolean caseSensitive) {
        return new ChatMatchConfig(MatchType.ENDS_WITH, suffix, caseSensitive, true, true);
    }

    /**
     * Creates a CONTAINS match config.
     */
    public static ChatMatchConfig contains(String substring, boolean caseSensitive) {
        return new ChatMatchConfig(MatchType.CONTAINS, substring, caseSensitive, true, true);
    }

    /**
     * Creates an EQUALS match config.
     */
    public static ChatMatchConfig equals(String exactValue, boolean caseSensitive) {
        return new ChatMatchConfig(MatchType.EQUALS, exactValue, caseSensitive, true, true);
    }

    /**
     * Creates a REGEX match config.
     */
    public static ChatMatchConfig regex(String pattern) {
        return new ChatMatchConfig(MatchType.REGEX, pattern, true, true, true);
    }

    /**
     * Checks if the given message matches this config.
     *
     * @param message The message to check
     * @return true if the message matches, false otherwise
     */
    public boolean matches(String message) {
        if (message == null) {
            return false;
        }

        return switch (type) {
            case ANY -> true;
            case STARTS_WITH -> caseSensitive
                ? message.startsWith(value)
                : message.toLowerCase(Locale.ROOT).startsWith(value.toLowerCase(Locale.ROOT));
            case ENDS_WITH -> caseSensitive
                ? message.endsWith(value)
                : message.toLowerCase(Locale.ROOT).endsWith(value.toLowerCase(Locale.ROOT));
            case CONTAINS -> caseSensitive
                ? message.contains(value)
                : message.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
            case EQUALS -> caseSensitive
                ? message.equals(value)
                : message.equalsIgnoreCase(value);
            case REGEX -> {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                yield Pattern.compile(value, flags).matcher(message).find();
            }
        };
    }

    /**
     * Extracts the relevant part of the message after matching.
     * For STARTS_WITH with trimPrefix=true, removes the prefix.
     * For ENDS_WITH with trimSuffix=true, removes the suffix.
     * For other types, returns the original message.
     *
     * @param message The original message
     * @return The extracted/trimmed message
     */
    public String extractMessage(String message) {
        if (message == null || !matches(message)) {
            return message;
        }

        return switch (type) {
            case STARTS_WITH -> {
                if (trimPrefix && value != null) {
                    String msgToCheck = caseSensitive ? message : message.toLowerCase(Locale.ROOT);
                    String valToCheck = caseSensitive ? value : value.toLowerCase(Locale.ROOT);
                    if (msgToCheck.startsWith(valToCheck)) {
                        yield message.substring(value.length()).trim();
                    }
                }
                yield message;
            }
            case ENDS_WITH -> {
                if (trimSuffix && value != null) {
                    String msgToCheck = caseSensitive ? message : message.toLowerCase(Locale.ROOT);
                    String valToCheck = caseSensitive ? value : value.toLowerCase(Locale.ROOT);
                    if (msgToCheck.endsWith(valToCheck)) {
                        yield message.substring(0, message.length() - value.length()).trim();
                    }
                }
                yield message;
            }
            default -> message;
        };
    }

    /**
     * Creates a ChatMatchConfig from a Map (for JSON deserialization).
     */
    @SuppressWarnings("unchecked")
    public static ChatMatchConfig fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return any();
        }

        String typeStr = (String) map.getOrDefault("type", "any");
        MatchType matchType = switch (typeStr.toLowerCase(Locale.ROOT)) {
            case "startswith", "starts_with" -> MatchType.STARTS_WITH;
            case "endswith", "ends_with" -> MatchType.ENDS_WITH;
            case "contains" -> MatchType.CONTAINS;
            case "equals" -> MatchType.EQUALS;
            case "regex" -> MatchType.REGEX;
            default -> MatchType.ANY;
        };

        String value = (String) map.get("value");
        boolean caseSensitive = Boolean.TRUE.equals(map.get("caseSensitive"));
        boolean trimPrefix = map.containsKey("trimPrefix") ? Boolean.TRUE.equals(map.get("trimPrefix")) : true;
        boolean trimSuffix = map.containsKey("trimSuffix") ? Boolean.TRUE.equals(map.get("trimSuffix")) : true;

        return new ChatMatchConfig(matchType, value, caseSensitive, trimPrefix, trimSuffix);
    }

    /**
     * Converts this config to a Map (for JSON serialization).
     */
    public Map<String, Object> toMap() {
        return Map.of(
            "type", type.name().toLowerCase(Locale.ROOT),
            "value", value != null ? value : "",
            "caseSensitive", caseSensitive,
            "trimPrefix", trimPrefix,
            "trimSuffix", trimSuffix
        );
    }
}
