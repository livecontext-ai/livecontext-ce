package com.apimarketplace.common.web;

/**
 * The URL's hostname did not resolve ({@code "Cannot resolve hostname: ..."}).
 *
 * <p>For a URL checked once, before a first call, this is a property of the URL and the right
 * advice is to fix it, which is why it is NOT a {@link UrlResolutionException}. It gets its own
 * type for the callers that already reached the same host a moment ago: there a name that stops
 * resolving is a DNS incident, not a bad URL (a resolver's SERVFAIL surfaces in Java as the same
 * {@code UnknownHostException} as a name that does not exist). The async poll of a job the
 * upstream already accepted is one: failing it on one lookup abandons a job the user paid for.
 *
 * <p>It extends {@link IllegalArgumentException} so every existing caller, all of which catch
 * that, behaves exactly as before.
 */
public class UnresolvableHostException extends IllegalArgumentException {
    public UnresolvableHostException(String message) {
        super(message);
    }
}
