package com.apimarketplace.common.credit;

import java.util.Objects;

/**
 * Canonical {@code sourceId} keys for idempotent ledger rows.
 *
 * <h2>Markup (unified workflow + chat, V148+)</h2>
 * <ul>
 *   <li>Run-init reservation: {@code platform-markup:INIT:<scopeKind>:<scopeId>:<credentialId>}
 *       - workflow run-init ({@code scopeKind=RUN}) reserves whole-run budget.</li>
 *   <li>Per-call workflow debit: {@code platform-markup:RUN:<runId>:step:<stepId>:<epoch>:<spawn>:<iteration>:<itemIndex>:<callIndex>}
 *       - adds {@code callIndex} for n=N partial-release support (image-gen Gemini loop).</li>
 *   <li>Per-call chat debit: {@code platform-markup:STREAM:<streamId>:<toolId>:<callIndex>}
 *       - chat-scope tool calls bill via STREAM-pin.</li>
 *   <li>Refund (legacy): {@code platform-markup-refund:<originalSourceId>:<nonce>}
 *       - kept for backwards-compat with V101 markup refund flow.</li>
 * </ul>
 *
 * <p><b>Lifecycle invariant:</b> {@code sourceId-pre IS sourceId-final}. The ledger
 * row written at {@code tryReserveMarkup} is the SAME row that {@code commitReservation}
 * UPDATEs to {@code PLATFORM_MARKUP} or {@code releaseReservation} flips to
 * {@code PLATFORM_MARKUP_RELEASED*}. No second sourceId per call.
 *
 * <h2>Tool billing (web search, image generation)</h2>
 * Tools can be invoked from two distinct scopes with different identifier sets
 * available in {@code ToolExecutionContext.credentials()}:
 * <ul>
 *   <li><b>Chat scope</b> &mdash; the credentials map carries
 *       {@code __streamId__} + {@code __toolCallId__} (no {@code runId}).
 *       Key shape: {@code <tool>:CHAT:<streamId>:<toolCallId>:<callIndex>}.</li>
 *   <li><b>Workflow scope</b> &mdash; the credentials map carries
 *       {@code runId} + {@code stepId}. Key shape:
 *       {@code <tool>:RUN:<runId>:step:<stepId>:<callIndex>}.</li>
 * </ul>
 *
 * <p>The two scopes use distinct prefixes ({@code :CHAT:} vs {@code :RUN:})
 * so a chat-scope key and a workflow-scope key can never collide in the
 * {@code source_id} unique index, even if the chat {@code streamId} happens
 * to match a workflow {@code runId}.
 *
 * <p>Method names (rather than overloads) are used for the two scopes
 * because the parameter erasure {@code (String, String, int)} is identical
 * &mdash; Java's overload resolution would not be able to disambiguate.
 */
public final class SourceIdBuilder {

    public static final String MARKUP_DEBIT_PREFIX = "platform-markup";
    public static final String WEB_SEARCH_PREFIX = "web-search";
    public static final String WEB_FETCH_PREFIX = "web-fetch";
    public static final String IMAGE_GENERATION_PREFIX = "image-generation";

    private SourceIdBuilder() {
    }

    public static String markupDebit(String runId,
                                     String stepId,
                                     int epoch,
                                     int spawn,
                                     int iteration,
                                     int itemIndex) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepId, "stepId");
        return MARKUP_DEBIT_PREFIX + ":RUN:" + runId
                + ":step:" + stepId
                + ":" + epoch
                + ":" + spawn
                + ":" + iteration
                + ":" + itemIndex;
    }

    /**
     * Workflow per-call debit key with callIndex. Extends the legacy
     * {@link #markupDebit} format with a 7th tail segment so n=N partial release
     * (image-gen Gemini loop returns 1 image per call) gets distinct sourceIds
     * per sub-call without colliding with the existing 6-segment format. The
     * legacy 6-segment format remains for backwards compat with prod ledger rows
     * written by the pre-V148 orchestrator-side markup-debit path (since removed;
     * the live billing path is {@code CatalogToolBillingService.billImmediate}).
     */
    public static String markupDebitWithCall(String runId,
                                              String stepId,
                                              int epoch,
                                              int spawn,
                                              int iteration,
                                              int itemIndex,
                                              int callIndex) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepId, "stepId");
        return MARKUP_DEBIT_PREFIX + ":RUN:" + runId
                + ":step:" + stepId
                + ":" + epoch
                + ":" + spawn
                + ":" + iteration
                + ":" + itemIndex
                + ":" + callIndex;
    }

    /**
     * Chat per-call debit key. Format: {@code platform-markup:STREAM:<streamId>:<toolId>:<callIndex>}.
     * Distinct from workflow keys via the {@code :STREAM:} discriminator so chat
     * and workflow billings can never collide on the {@code source_id} unique
     * index, even if a streamId happens to match a runId.
     *
     * <p>{@code toolId} here is the catalog tool slug (e.g. {@code openai/openai-create-image}),
     * not the UUID - both encode tool identity but the slug is what callers
     * carry in their context.
     */
    public static String markupDebitChat(String streamId,
                                          String toolId,
                                          int callIndex) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(toolId, "toolId");
        return MARKUP_DEBIT_PREFIX + ":STREAM:" + streamId + ":" + toolId + ":" + callIndex;
    }

    /**
     * Run-init / stream-init reservation key. Format:
     * {@code platform-markup:INIT:<scopeKind>:<scopeId>:<credentialId>}.
     *
     * <p>Used at workflow run-init to reserve whole-run budget against a
     * platform credential. The reservation row's {@code source_id} stays this
     * shape until commit/release. The {@code :INIT:} prefix is recognised by
     * {@link #isMarkupDebit} so analytics, sweepers, and history filters all
     * see init reservations as part of the markup debit family.
     */
    public static String markupReserveInit(String scopeKind,
                                            String scopeId,
                                            long credentialId) {
        Objects.requireNonNull(scopeKind, "scopeKind");
        Objects.requireNonNull(scopeId, "scopeId");
        return MARKUP_DEBIT_PREFIX + ":INIT:" + scopeKind + ":" + scopeId + ":" + credentialId;
    }

    /** Web search debit key for chat-scope invocations. */
    public static String webSearchDebitChat(String streamId, String toolCallId, int callIndex) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(toolCallId, "toolCallId");
        return WEB_SEARCH_PREFIX + ":CHAT:" + streamId + ":" + toolCallId + ":" + callIndex;
    }

    /** Web search debit key for workflow-scope invocations. */
    public static String webSearchDebitWorkflow(String runId, String stepId, int callIndex) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepId, "stepId");
        return WEB_SEARCH_PREFIX + ":RUN:" + runId + ":step:" + stepId + ":" + callIndex;
    }

    /** Web fetch debit key for chat-scope invocations. */
    public static String webFetchDebitChat(String streamId, String toolCallId, int callIndex) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(toolCallId, "toolCallId");
        return WEB_FETCH_PREFIX + ":CHAT:" + streamId + ":" + toolCallId + ":" + callIndex;
    }

    /** Web fetch debit key for workflow-scope invocations. */
    public static String webFetchDebitWorkflow(String runId, String stepId, int callIndex) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepId, "stepId");
        return WEB_FETCH_PREFIX + ":RUN:" + runId + ":step:" + stepId + ":" + callIndex;
    }

    /** Image generation debit key for chat-scope invocations. */
    public static String imageGenerationDebitChat(String streamId, String toolCallId, int callIndex) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(toolCallId, "toolCallId");
        return IMAGE_GENERATION_PREFIX + ":CHAT:" + streamId + ":" + toolCallId + ":" + callIndex;
    }

    /** Image generation debit key for workflow-scope invocations. */
    public static String imageGenerationDebitWorkflow(String runId, String stepId, int callIndex) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepId, "stepId");
        return IMAGE_GENERATION_PREFIX + ":RUN:" + runId + ":step:" + stepId + ":" + callIndex;
    }

    /**
     * True for any markup debit row family - workflow per-call ({@code :RUN:}),
     * chat per-call ({@code :STREAM:}), or scope init reservation ({@code :INIT:}).
     * Used by ledger aggregations and sweepers to identify markup-family rows
     * regardless of scope.
     */
    public static boolean isMarkupDebit(String sourceId) {
        if (sourceId == null) return false;
        return sourceId.startsWith(MARKUP_DEBIT_PREFIX + ":RUN:")
            || sourceId.startsWith(MARKUP_DEBIT_PREFIX + ":STREAM:")
            || sourceId.startsWith(MARKUP_DEBIT_PREFIX + ":INIT:");
    }

    public static boolean isWebSearchDebit(String sourceId) {
        return sourceId != null && sourceId.startsWith(WEB_SEARCH_PREFIX + ":");
    }

    public static boolean isWebFetchDebit(String sourceId) {
        return sourceId != null && sourceId.startsWith(WEB_FETCH_PREFIX + ":");
    }

    public static boolean isImageGenerationDebit(String sourceId) {
        return sourceId != null && sourceId.startsWith(IMAGE_GENERATION_PREFIX + ":");
    }
}
