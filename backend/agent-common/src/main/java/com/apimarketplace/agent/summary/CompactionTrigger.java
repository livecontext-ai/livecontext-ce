package com.apimarketplace.agent.summary;

/**
 * What makes the COLD summariser fire. Exactly ONE mode is active at a time:
 * the modes are alternative ways of answering "is it time to regenerate?",
 * never independent switches that could both fire and produce two passes.
 *
 * <p><b>The size gate is NOT one of these modes.</b>
 * {@link ColdSummaryGate#passesSizeGate} stays ANDed in front of every mode.
 * It is a credit-protection floor ("is there enough in COLD for a summary to
 * be worth its own tokens?"). A trigger mode only decides WHEN, above that
 * floor, the pass happens.
 *
 * <p><b>Both size modes measure GROWTH, not absolute size.</b> The COLD zone
 * keeps its messages after a summary lands, so an absolute-size trigger would
 * stay above its threshold for ever and fire a summariser call on every
 * subsequent turn, indefinitely: no per-conversation per-day cap is enforced
 * anywhere in this codebase. The
 * comparison is therefore against COLD tokens appended SINCE the stored
 * envelope's coverage, which resets after each successful pass exactly like
 * {@code turnsSinceLastSummary}.

 * <p><b>What "growth" can and cannot see.</b> The COLD zone excludes the last
 * {@code hotWarmTurnWindow} messages, so a large tool result is HOT on the turn
 * it lands and is invisible to this trigger until it ages out of that window.
 * Growth therefore means "a lot has aged into COLD", which is what the
 * summariser can actually act on. It reacts in token terms rather than turn
 * terms; it is not an immediate reaction to the turn that caused the growth.
 */
public enum CompactionTrigger {

    /**
     * Cadence only: fire after N new COLD turns. The historical behaviour and
     * the default, so an untouched deployment keeps byte-identical semantics.
     *
     * <p>Blind spot: the cadence counts turns, so a conversation whose turns
     * are individually huge waits exactly as long as one whose turns are tiny.
     * Between two cadence-driven summaries the COLD zone can grow without
     * bound, and every turn in between re-sends it in full. The size modes
     * react to that growth instead, though only once the material has aged out
     * of the HOT+WARM window (see above), never on the turn it arrives.
     */
    TURNS(false, true),

    /**
     * Growth only: fire once COLD has grown by N tokens since the last
     * summary, whatever the turn count.
     *
     * <p>Blind spot, and the reason this is not the default: a long
     * conversation of small turns may never reach the threshold, so no
     * envelope is ever written and the agent keeps no recall of anything
     * older than the HOT+WARM window. Pick this only when the workload is
     * known to be token-heavy.
     */
    SIZE(true, false),

    /**
     * Whichever comes first. Still ONE pass with two ways of saying yes, not
     * two triggers: the gate is a single boolean and the summariser is
     * additionally serialised by its per-conversation lock and by the
     * monotone write guard on {@code summary_cold}.
     *
     * <p>Recommended for token-heavy agent workloads: growth reacts to how
     * many tokens have aged into COLD rather than to how many turns have
     * passed, while the cadence still guarantees that a quiet conversation
     * eventually gets an envelope.
     */
    SIZE_OR_TURNS(true, true);

    private final boolean usesSize;
    private final boolean usesTurns;

    CompactionTrigger(boolean usesSize, boolean usesTurns) {
        this.usesSize = usesSize;
        this.usesTurns = usesTurns;
    }

    /** True when COLD growth since the last summary may fire this mode. */
    public boolean usesSize() {
        return usesSize;
    }

    /** True when the turn cadence may fire this mode. */
    public boolean usesTurns() {
        return usesTurns;
    }

    /**
     * Lenient parse for configuration: case-insensitive, tolerates the
     * {@code size-or-turns} spelling that reads naturally in YAML, and falls
     * back to {@link #TURNS} on anything blank or unknown.
     *
     * <p>Falling back rather than throwing is deliberate: a typo in a config
     * value must not stop a service from booting, and {@code TURNS} is the
     * pre-feature behaviour, so an unreadable value degrades to "as before"
     * instead of to "no compaction at all".
     */
    public static CompactionTrigger parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TURNS;
        }
        // Locale.ROOT, never the default locale: on a Turkish JVM
        // "size".toUpperCase() is "SIZE" with a dotted capital I (U+0130), which
        // matches no enum name and would silently fall back to TURNS. Both size
        // spellings contain an 'i' and "turns" does not, so the failure would be
        // one-directional and invisible: the mode is configured, the service boots,
        // and compaction quietly keeps running in the old mode.
        String normalised = raw.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        for (CompactionTrigger t : values()) {
            if (t.name().equals(normalised)) {
                return t;
            }
        }
        return TURNS;
    }
}
