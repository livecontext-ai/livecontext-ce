package com.apimarketplace.agent.summary;

import java.util.regex.Pattern;

/**
 * Stage 5.4 - invalidation keyword matcher for the COLD summarizer.
 *
 * <p>The summarizer is expensive; regenerating it on every turn would
 * burn credits. We only regenerate when either (a) a fixed cadence has
 * elapsed, or (b) the user says something that meaningfully <em>changes
 * the direction</em> of the conversation so the prior summary is now
 * misleading.
 *
 * <p><b>Why a narrow matcher.</b> The v3 plan used a naive keyword list
 * ({@code actually}, {@code instead}, {@code forget}, {@code reset},
 * {@code ignore}) which triggered on unrelated text like "ignore the
 * noise in the logs" or "reset password". R22 + R50 tightened the rules
 * to three patterns only:
 * <ul>
 *   <li><b>Sentence-initial intent words.</b> A message starting with
 *       {@code actually}/{@code instead}/{@code forget}, followed by a
 *       comma or whitespace. Starting a message with these words is a
 *       strong signal the user is pivoting; mid-sentence occurrence is
 *       usually noise (e.g. "I actually think it's fine"). {@code reset}
 *       was removed per R50 (false-positive rate on "reset password" /
 *       "factory reset" was unacceptable).</li>
 *   <li><b>{@code instead of X} phrase template.</b> The fragment
 *       {@code instead of} followed by a word marks a substitution - a
 *       direct contradiction of an earlier decision.</li>
 *   <li><b>{@code forget (about)? the/my/that/what} phrase
 *       template.</b> Restricted to the forms that actually announce a
 *       retraction; {@code "forget what I said"} qualifies,
 *       {@code "don't forget to save"} does not.</li>
 * </ul>
 *
 * <p>A fourth rule - proximity of {@code actually}/{@code instead} to a
 * tool-name or ID that appears in HOT context - is the caller's job
 * (this class has no view on HOT state). See
 * {@link #proximityTriggerEligible(String)} for the base check; callers
 * pair it with their own proximity check.
 *
 * <p><b>FP target.</b> Grafana metric
 * {@code summarizer.invalidations_per_turn} must stay &lt; 2% of turns.
 * Each pattern below is pinned in {@link
 * ColdSummaryInvalidationKeywordsTest} against the FP list ("reset
 * password", "ignore the noise", etc.) so a sloppy "make it fire more"
 * refactor trips CI.
 */
public final class ColdSummaryInvalidationKeywords {

    /**
     * Sentence-initial intent words. Pattern anchors at input start
     * (after optional leading whitespace), matches one of
     * {@code actually|instead|forget}, and requires a comma or
     * whitespace follower so {@code "actuallyfine"} (unlikely but
     * possible) doesn't trip. Case-insensitive.
     */
    static final Pattern SENTENCE_INITIAL =
            Pattern.compile("^\\s*(actually|instead|forget)[,\\s]",
                    Pattern.CASE_INSENSITIVE);

    /**
     * {@code instead of <word>} - substitution template. The required
     * trailing word prevents matches on {@code "instead of."} alone.
     */
    static final Pattern INSTEAD_OF =
            Pattern.compile("instead of\\s+\\w+", Pattern.CASE_INSENSITIVE);

    /**
     * {@code forget (about )?(the|my|that|what)} - retraction
     * template. The required determiner tail is what excludes
     * {@code "don't forget to save"} (followed by {@code to}, not a
     * determiner).
     */
    static final Pattern FORGET_PHRASE =
            Pattern.compile("forget( about)? (the|my|that|what)\\b",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Loose proximity-eligible match - {@code actually} or {@code
     * instead} anywhere in the message. The caller pairs this with
     * their own HOT-proximity check (within 30 chars of a prior tool
     * name or ID). On its own this would over-trigger - never use
     * {@link #proximityTriggerEligible(String)} as the sole decision
     * gate.
     */
    static final Pattern PROXIMITY_KEYWORD =
            Pattern.compile("\\b(actually|instead)\\b",
                    Pattern.CASE_INSENSITIVE);

    private ColdSummaryInvalidationKeywords() {}

    /**
     * Does {@code userMessage} match any of the three strict
     * invalidation patterns? Returns {@code false} for null / blank /
     * empty.
     *
     * <p>Strict means: sentence-initial intent, {@code instead of} with
     * a word, or a {@code forget the/my/…} template. Pure keyword
     * occurrence anywhere in the message is <em>not</em> sufficient -
     * that's what the proximity rule is for, and it needs caller
     * context.
     *
     * @param userMessage the raw user turn text; may be {@code null}.
     * @return {@code true} iff the message matches one of the strict
     *         patterns.
     */
    public static boolean matchesStrict(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        return SENTENCE_INITIAL.matcher(userMessage).find()
                || INSTEAD_OF.matcher(userMessage).find()
                || FORGET_PHRASE.matcher(userMessage).find();
    }

    /**
     * Is {@code userMessage} eligible for the proximity rule? {@code
     * true} means the message contains {@code actually} or
     * {@code instead}; the caller must then verify the keyword sits
     * within 30 characters of a prior tool name / ID visible in HOT
     * context before firing invalidation.
     */
    public static boolean proximityTriggerEligible(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        return PROXIMITY_KEYWORD.matcher(userMessage).find();
    }
}
