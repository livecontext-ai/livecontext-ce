package com.apimarketplace.agent.tools.authz;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * What identifies ONE request for permission: the rule plus the call that asked for it.
 *
 * <p><b>Why an ask is not a rule.</b> A grant recorded against a rule authorizes the next call of
 * that rule, whatever its arguments. In the app that is barely visible: the person is looking at
 * the card, presses Approve, and the call they were shown resumes in the turn already running. From
 * a chat it is a different thing entirely. Hours can pass, the run that asked has ended, and the
 * grant is consumed by a SCHEDULED run nobody is watching. The person approved "publish the
 * September report to LinkedIn"; a rule-scoped grant would let the agent's next run publish
 * whatever it happened to compose, unasked, and the message they approved would be the only record
 * saying otherwise.
 *
 * <p>So a grant written for a question answered elsewhere carries the ask with it, and is spent
 * only by the same call. The three pieces are here, in one place, because they have to agree
 * across three services: orchestrator writes the fingerprint on the request row, conversation
 * records the grant, and agent-service decides whether a call is covered by it. Two copies of a
 * digest drift silently, and the failure mode of the drift is a permission that is never matched,
 * which looks exactly like a person who never pressed the button.
 */
public final class AuthorizationAsk {

    /** Separates the rule from the ask digest in a scoped grant. Absent from both halves. */
    private static final char SCOPE_SEPARATOR = '#';

    /** Long enough that a collision is not a concern, short enough to sit in a JSON list. */
    private static final int DIGEST_CHARS = 32;

    private AuthorizationAsk() {
    }

    /**
     * The raw material that identifies a call: its tool and the arguments it was made with.
     *
     * <p>Derived from the CALL, never from a display summary: that summary is only written when
     * there is something worth naming, and without it the identity would collapse to the rule, so
     * "publish post A" and "publish post B" would be the same ask. Keys are sorted, so the same
     * arguments in a different order stay the same ask.
     */
    public static String material(String toolName, Map<String, Object> arguments) {
        StringBuilder material = new StringBuilder(toolName != null ? toolName : "");
        if (arguments != null) {
            new TreeMap<>(arguments).forEach((key, value) ->
                    material.append('|').append(key).append('=').append(value));
        }
        return material.toString();
    }

    /**
     * A bounded digest of the rule and that material.
     *
     * <p>Hashed rather than kept raw because it is stored in a column and repeated in a grant
     * list, and an argument map has no length anyone controls.
     */
    public static String fingerprint(String rule, String material) {
        String subject = (rule != null ? rule : "") + "|" + (material != null ? material : "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(subject.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, DIGEST_CHARS);
        } catch (NoSuchAlgorithmException ex) {
            // Cannot happen on a JVM; a length-bounded fallback keeps the value storable.
            return subject.length() <= 80 ? subject : subject.substring(0, 80);
        }
    }

    /** The fingerprint of one call, in one step. */
    public static String fingerprintOfCall(String rule, String toolName, Map<String, Object> arguments) {
        return fingerprint(rule, material(toolName, arguments));
    }

    /**
     * The grant entry that authorizes exactly this ask, and nothing else of the same rule.
     *
     * <p>Falls back to the bare rule when there is no fingerprint, which is the in-app case: the
     * person is present, the call they approved resumes in place, and narrowing that grant would
     * change a behaviour nobody complained about.
     */
    public static String scopedGrant(String rule, String fingerprint) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            return rule;
        }
        return rule + SCOPE_SEPARATOR + fingerprint;
    }

    /**
     * Whether the grants held for this turn authorize this call.
     *
     * <p>Three ways in, and they are not interchangeable. {@code "*"} is the conversation-wide
     * toggle the person set deliberately. The bare rule is an in-app approval or a remembered
     * "always allow". The scoped entry is an answer given somewhere the person could not see what
     * ran next, and it opens the door for one call only.
     */
    public static boolean authorizes(Collection<?> grants, String rule, String askFingerprint) {
        if (grants == null || grants.isEmpty()) {
            return false;
        }
        if (grants.contains("*")) {
            return true;
        }
        if (rule == null || rule.isBlank()) {
            return false;
        }
        if (grants.contains(rule)) {
            return true;
        }
        String scoped = scopedGrant(rule, askFingerprint);
        return scoped != null && !scoped.equals(rule) && grants.contains(scoped);
    }
}
