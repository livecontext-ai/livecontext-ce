package com.apimarketplace.common.scope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one place that turns a provider's answer about which OAuth scopes it granted into
 * individual scope strings.
 *
 * <p>It exists because the same string is parsed at two moments, in two services, and they
 * disagreed for two months. auth-service parses the token response at CONNECT time and stores
 * the result on the credential; catalog-service reads that stored list back at EXECUTION time
 * and compares it against an endpoint's {@code requiredScopes}. When only the first knew that a
 * provider may answer with commas, every credential connected before that fix kept one
 * comma-joined blob for ever, and the second refused every gated endpoint on a grant that was
 * perfectly correct. Nothing failed at connect time, and {@code parseGrantedScopes} is called
 * only when a credential is created, never on refresh, so no amount of redeploying repaired it.
 *
 * <p>The lesson is why the normalization now happens on BOTH sides rather than only at the
 * source: a value that is parsed where it is written and trusted where it is read is only as
 * correct as the oldest row in the table. Normalizing again at the point of comparison costs a
 * split and makes the stored shape stop mattering.
 *
 * <p><b>Both delimiters, for every provider.</b> RFC 6749 section 5.1 specifies a
 * space-delimited list, and several providers answer with commas anyway (LinkedIn returns
 * {@code "r_basicprofile,w_member_social,..."}; TikTok declares {@code "scopeDelimiter": ","} in
 * its catalog entry). The delimiter is a property of the RESPONSE, no catalog field describes
 * it, and a provider is free to answer in a different form than it was asked in, so both are
 * split for everyone rather than for a list of known offenders.
 *
 * <p>Splitting alone would not be safe, because RFC 6749 section 3.3 {@code NQCHAR} permits a
 * separator inside a scope token and one such scope already exists in the catalog
 * ({@code workday.json}'s {@code "Tenant Non-Configurable"}). {@link #missingFrom} therefore
 * accepts a requirement that matches a stored entry WHOLE as well as one that matches a piece
 * of it, so a scope carrying a space keeps working while a comma-joined blob is repaired. No
 * endpoint requires that scope today, which is exactly why the narrow version would have looked
 * correct until the day one did.
 */
public final class GrantedScopes {

    /** Space (RFC 6749) or comma (several providers), any run of them. */
    private static final String DELIMITERS = "[,\\s]+";

    private GrantedScopes() {
    }

    /**
     * Split a raw granted-scope string into individual scopes, order-preserving, blank
     * fragments dropped (a trailing separator, {@code "a, b"}).
     *
     * @param grantedScope the provider's raw {@code scope} value, possibly null or blank
     * @return the scopes it names, never null
     */
    public static List<String> parse(String grantedScope) {
        if (grantedScope == null || grantedScope.isBlank()) {
            return List.of();
        }
        return Arrays.stream(grantedScope.split(DELIMITERS))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Re-split an ALREADY STORED scope list, so a row written before this rule existed compares
     * correctly. An element holding several scopes becomes several elements; an ordinary list
     * passes through unchanged, which is why this is safe to apply to every credential rather
     * than to a detected subset.
     *
     * @param stored the scope list as persisted on the credential, possibly null
     * @return every scope it names, de-duplicated, never null
     */
    public static Set<String> normalize(Collection<String> stored) {
        if (stored == null || stored.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String entry : stored) {
            out.addAll(parse(entry));
        }
        return out;
    }

    /**
     * The required scopes a granted set does not cover.
     *
     * <p>A requirement counts as held when it matches a stored entry EXACTLY or one of the
     * scopes that entry splits into. Widening rather than replacing is what makes this safe in
     * both directions at once: splitting alone would repair the comma-joined blob and break a
     * scope that legitimately contains a separator, since {@code "Tenant Non-Configurable"}
     * (workday) would be compared as two tokens and could never be matched. No endpoint
     * requires that scope today, so the narrower version would have passed every test and
     * failed the first time one did.
     *
     * <p>The REQUIRED side is never split. It comes from a catalog seed a validator already
     * checks against the integration's declared scopes, so it is the side whose shape is known
     * to be right.
     *
     * @return the missing scopes, empty when the grant covers everything
     */
    public static Set<String> missingFrom(Collection<String> required, Collection<String> granted) {
        if (required == null || required.isEmpty()) {
            return Set.of();
        }
        Set<String> have = new LinkedHashSet<>(normalize(granted));
        if (granted != null) {
            for (String entry : granted) {
                if (entry != null && !entry.isBlank()) {
                    have.add(entry.trim());
                }
            }
        }
        Set<String> missing = new LinkedHashSet<>(new ArrayList<>(required));
        missing.removeAll(have);
        return missing;
    }
}
