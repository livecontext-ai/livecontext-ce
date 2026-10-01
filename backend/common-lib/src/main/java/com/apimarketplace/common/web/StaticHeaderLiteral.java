package com.apimarketplace.common.web;

/**
 * The one rule for which static header values a catalog API may declare, shared by the two routes
 * that register them: the seed importer ({@code ApiMigrationImporter}) and a user's custom API
 * ({@code CustomApiRegistrationService}). Both call this one rule, so a value registers the same
 * way by either route.
 *
 * <p>A static header value is a short literal, not prose and not a template. Rejected: blank,
 * longer than 64 characters, and ANY value carrying a {@code {...}} placeholder (e.g.
 * {@code {{api_key}}}, {@code {YYYYMM}}), which are runtime or credential templates that would
 * shadow a real credential header or go out verbatim.
 *
 * <p>Whitespace is rejected too, with two exceptions, each a single space:
 * <ul>
 *   <li>after a {@code ;}: the media-type parameter form,
 *       {@code application/vnd.heroku+json; version=3} (Heroku's 26 endpoints answered 400
 *       {@code missing_version} while this rule dropped it);</li>
 *   <li>after a {@code ,}: the list form of a media range,
 *       {@code application/json, text/event-stream}, which an MCP server over HTTP requires
 *       before it answers anything but 406.</li>
 * </ul>
 * Prose never has that shape, so neither exception lets documentation through. Measured over the
 * seed corpus before the first widening: of the 12 distinct static values the whitespace test
 * rejected, 10 were credential templates (the brace test rejects them anyway) and 2 were prose.
 */
public final class StaticHeaderLiteral {

    /** Longest value accepted as a literal. */
    public static final int MAX_LENGTH = 64;

    private StaticHeaderLiteral() {
    }

    /** Whether {@code value} may be registered and sent verbatim as a static header. */
    public static boolean isLiteral(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            return false;
        }
        if (value.indexOf('{') >= 0 || value.indexOf('}') >= 0) {
            return false;
        }
        return value.replace("; ", ";").replace(", ", ",").chars().noneMatch(Character::isWhitespace);
    }
}
