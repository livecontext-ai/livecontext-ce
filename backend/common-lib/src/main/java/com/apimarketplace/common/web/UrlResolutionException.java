package com.apimarketplace.common.web;

/**
 * The URL could not be CHECKED, as opposed to being checked and refused.
 *
 * <p>Raised when {@link UrlSafetyValidator} cannot reach a verdict because its own DNS
 * resolution ran out of capacity, timed out, or was interrupted. Those are conditions of
 * this process under load, not properties of the URL: the same URL will very likely pass
 * a moment later.
 *
 * <p>It extends {@link IllegalArgumentException} so that every existing caller, all of
 * which catch that, keeps behaving exactly as before. Callers that tell a caller of their
 * own whether to retry should catch this one FIRST and report it as a transient failure.
 * Getting that backwards is not a cosmetic error: an agent told "correct the URL" will
 * rewrite a perfectly good one instead of retrying the call that would have worked.
 *
 * <p>An unresolvable hostname is deliberately NOT one of these. A name that does not
 * exist is a property of the URL, and the right advice for it is to fix the URL.
 */
public class UrlResolutionException extends IllegalArgumentException {

    public UrlResolutionException(String message) {
        super(message);
    }
}
