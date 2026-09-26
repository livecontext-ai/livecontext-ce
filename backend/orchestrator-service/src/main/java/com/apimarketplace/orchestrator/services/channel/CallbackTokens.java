package com.apimarketplace.orchestrator.services.channel;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The opaque handle a chat button carries back, and the payloads built around it.
 *
 * <p>Extracted so the approval path and the question path mint the same kind of token from the
 * same generator. Two generators for one security property is how they end up differing, and the
 * difference that matters here is guessability: the token IS the authority to resolve a request,
 * checked before anything else, so a weaker one on one path weakens the feature.
 *
 * <p>Sizing is set by the provider. Telegram caps a callback payload at 64 bytes, so the token is
 * 128 bits encoded base64url without padding, which is 22 characters, leaving room for a prefix
 * and a short action suffix.
 */
public final class CallbackTokens {

    /** Namespaced prefix for a QUESTION button, distinct from approvals ({@code lcaut}). */
    public static final String QUESTION_PREFIX = "lcask";

    private static final SecureRandom RANDOM = new SecureRandom();

    private CallbackTokens() {
    }

    /** 128-bit, base64url, 22 chars: under the 64-byte cap a button payload has. */
    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * What one question button sends back: {@code lcask:<token>:<action>}.
     *
     * <p>Actions are {@code o<index>} (pick or toggle), {@code done} and {@code other}. Longest
     * is 5 + 1 + 22 + 1 + 5, which is 34 bytes against the 64 available, so the arithmetic has
     * the same headroom the approval payloads do.
     */
    public static String questionPayload(String token, String action) {
        return QUESTION_PREFIX + ":" + token + ":" + action;
    }
}
