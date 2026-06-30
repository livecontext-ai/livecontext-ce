package com.apimarketplace.agent.summary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage 5.2 - canonical shape of the structured COLD summary stored in
 * {@code conversation.conversations.summary_cold}.
 *
 * <p>A record rather than a free-form Map for two reasons:
 * <ol>
 *   <li>The Stage 5.3 gate needs {@code coldTokensAtGeneration} +
 *       {@code turnsCovered} to decide if regeneration is justified -
 *       having them as typed fields catches a summariser that forgot to
 *       populate either at compile time instead of at gate-evaluation
 *       time.</li>
 *   <li>Invalidation logic cross-references {@code userIntents} +
 *       {@code idsResolved} against the current HOT context - a
 *       rename of either field (say, a provider's output schema drifts)
 *       would silently break the lookup if the envelope were a
 *       {@code Map<String,Object>}.</li>
 * </ol>
 *
 * <p>Persisted via Jackson's default JSONB mapping. {@link JsonInclude}
 * NON_NULL keeps older rows (with missing optional collections) readable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColdSummaryEnvelope(
        @JsonProperty("decisions")                  List<Decision> decisions,
        @JsonProperty("ids_resolved")               Map<String, String> idsResolved,
        @JsonProperty("errors_resolved")            List<ErrorResolution> errorsResolved,
        @JsonProperty("user_intents")               List<String> userIntents,
        @JsonProperty("helped_actions")             List<String> helpedActions,
        /**
         * ISO-8601 UTC timestamp the summary was generated (e.g.
         * {@code 2026-04-20T12:00:00Z}). String rather than
         * {@code Instant} because agent-common is a pure-logic module
         * without the {@code jackson-datatype-jsr310} module on its
         * classpath - callers (Spring services) write the string via
         * {@code Instant.now().toString()} and parse on read.
         */
        @JsonProperty("generated_at")               String generatedAt,
        @JsonProperty("model")                      String model,
        @JsonProperty("cold_tokens_at_generation")  int coldTokensAtGeneration,
        @JsonProperty("turns_covered")              List<Integer> turnsCovered,
        /**
         * Recall trust level: {@link #STATUS_ACTIVE} for a freshly generated
         * envelope, {@link #STATUS_STALE} once the stored summary can no
         * longer be trusted verbatim (COLD zone shrank under it, or an
         * invalidation keyword fired without a successful regeneration).
         * Nullable: rows written before this field existed carry no status
         * and are treated as active. The renderer downgrades a stale
         * envelope to a caution-worded block; the monotone write guard
         * accepts smaller coverage only over a stale envelope.
         */
        @JsonProperty("status")                     String status
) {
    /** Freshly generated envelope - recall renders it as authoritative. */
    public static final String STATUS_ACTIVE = "active";
    /** Envelope flagged unreliable - recall renders it with a caution caveat. */
    public static final String STATUS_STALE = "stale";

    @JsonCreator
    public ColdSummaryEnvelope(
            @JsonProperty("decisions")                 List<Decision> decisions,
            @JsonProperty("ids_resolved")              Map<String, String> idsResolved,
            @JsonProperty("errors_resolved")           List<ErrorResolution> errorsResolved,
            @JsonProperty("user_intents")              List<String> userIntents,
            @JsonProperty("helped_actions")            List<String> helpedActions,
            @JsonProperty("generated_at")              String generatedAt,
            @JsonProperty("model")                     String model,
            @JsonProperty("cold_tokens_at_generation") int coldTokensAtGeneration,
            @JsonProperty("turns_covered")             List<Integer> turnsCovered,
            @JsonProperty("status")                    String status
    ) {
        this.decisions = decisions;
        this.idsResolved = idsResolved;
        this.errorsResolved = errorsResolved;
        this.userIntents = userIntents;
        this.helpedActions = helpedActions;
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.coldTokensAtGeneration = coldTokensAtGeneration;
        this.turnsCovered = turnsCovered;
        this.status = status;
    }

    /**
     * Legacy-shape convenience (pre-status rows): delegates with a null
     * status, which readers treat as {@link #STATUS_ACTIVE}.
     */
    public ColdSummaryEnvelope(List<Decision> decisions,
                               Map<String, String> idsResolved,
                               List<ErrorResolution> errorsResolved,
                               List<String> userIntents,
                               List<String> helpedActions,
                               String generatedAt,
                               String model,
                               int coldTokensAtGeneration,
                               List<Integer> turnsCovered) {
        this(decisions, idsResolved, errorsResolved, userIntents, helpedActions,
                generatedAt, model, coldTokensAtGeneration, turnsCovered, null);
    }

    /**
     * True only when the envelope is explicitly flagged stale.
     * {@code @JsonIgnore} keeps Jackson from serialising this derived
     * accessor as a phantom {@code "stale"} JSONB key alongside the real
     * {@code "status"} field.
     */
    @JsonIgnore
    public boolean isStale() {
        return STATUS_STALE.equals(status);
    }

    public record Decision(
            /** Turn index inside the conversation. */
            int turn,
            /** Free-form description of the decision. */
            String decision
    ) {
        @JsonCreator
        public Decision(@JsonProperty("turn") int turn,
                        @JsonProperty("decision") String decision) {
            this.turn = turn;
            this.decision = Objects.requireNonNull(decision, "decision text required");
        }
    }

    public record ErrorResolution(
            String error,
            String resolution
    ) {
        @JsonCreator
        public ErrorResolution(@JsonProperty("error") String error,
                               @JsonProperty("resolution") String resolution) {
            this.error = Objects.requireNonNull(error, "error text required");
            this.resolution = Objects.requireNonNull(resolution, "resolution text required");
        }
    }

    /**
     * Stage 5.4 - intermediate shape that matches the *LLM's* raw output. The
     * summariser prompt (see {@code ColdSummarizerPromptBuilder.SYSTEM_PROMPT})
     * asks only for the five "content" keys; the four metadata fields
     * ({@code generated_at}, {@code model}, {@code cold_tokens_at_generation},
     * {@code turns_covered}) are stamped by the service, not the model. Keeping
     * a separate partial record lets the service parse the LLM response with
     * strict Jackson bindings and then attach metadata in one call, instead of
     * juggling a raw {@link java.util.Map}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Partial(
            @JsonProperty("decisions")       List<Decision> decisions,
            @JsonProperty("ids_resolved")    Map<String, String> idsResolved,
            @JsonProperty("errors_resolved") List<ErrorResolution> errorsResolved,
            @JsonProperty("user_intents")    List<String> userIntents,
            @JsonProperty("helped_actions")  List<String> helpedActions
    ) {
        @JsonCreator
        public Partial(
                @JsonProperty("decisions")       List<Decision> decisions,
                @JsonProperty("ids_resolved")    Map<String, String> idsResolved,
                @JsonProperty("errors_resolved") List<ErrorResolution> errorsResolved,
                @JsonProperty("user_intents")    List<String> userIntents,
                @JsonProperty("helped_actions")  List<String> helpedActions
        ) {
            this.decisions = decisions;
            this.idsResolved = idsResolved;
            this.errorsResolved = errorsResolved;
            this.userIntents = userIntents;
            this.helpedActions = helpedActions;
        }

        /**
         * Promote a partial into a full envelope by attaching the four
         * service-owned metadata fields. All metadata args required (same
         * invariants as {@link ColdSummaryEnvelope}). Freshly promoted
         * envelopes are always {@link #STATUS_ACTIVE} - staleness is a
         * post-persistence transition, never a generation-time state.
         */
        public ColdSummaryEnvelope toEnvelope(String generatedAt,
                                              String model,
                                              int coldTokensAtGeneration,
                                              List<Integer> turnsCovered) {
            return new ColdSummaryEnvelope(
                    decisions,
                    idsResolved,
                    errorsResolved,
                    userIntents,
                    helpedActions,
                    generatedAt,
                    model,
                    coldTokensAtGeneration,
                    turnsCovered,
                    STATUS_ACTIVE
            );
        }
    }
}
