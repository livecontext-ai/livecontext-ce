package com.apimarketplace.agent.tools.ask;

/**
 * The gate key a question park uses, shared by the side that parks (agent-service) and the
 * side that answers (conversation-service).
 *
 * <p>The suffix is a security check, not a naming convention: the approval gate parses any
 * JSON envelope as a verdict, so an answer written against an AUTHORIZATION park's key
 * (the bare tool call id) would approve a sensitive action with no permission card ever
 * clicked. The answer endpoint refuses any key that is not {@link #forToolCall} of its own
 * call, and both sides must agree on the shape for that refusal to mean anything.
 */
public final class UserQuestionGateKeys {

    /** Keeps a question park's key apart from an authorization park of the same call. */
    public static final String SUFFIX = ":ask";

    private UserQuestionGateKeys() {
    }

    /** The gate key for the question raised by {@code toolCallId}. */
    public static String forToolCall(String toolCallId) {
        return toolCallId + SUFFIX;
    }

    /** True when {@code gateKey} is exactly this call's question key. */
    public static boolean belongsTo(String toolCallId, String gateKey) {
        return gateKey != null && gateKey.equals(forToolCall(toolCallId));
    }

    /**
     * The call id inside a question gate key, or null when the key is not one.
     *
     * <p>The inverse of {@link #forToolCall}, so a record that stored only the gate key can
     * still name the call the answer endpoint keys on. Returns null rather than guessing on a
     * key of another shape, because the shape is a security check and a lenient parse here
     * would hand an authorization park's key back as if it were a question's.
     */
    public static String toolCallIdOf(String gateKey) {
        if (gateKey == null || !gateKey.endsWith(SUFFIX) || gateKey.length() == SUFFIX.length()) {
            return null;
        }
        return gateKey.substring(0, gateKey.length() - SUFFIX.length());
    }
}
