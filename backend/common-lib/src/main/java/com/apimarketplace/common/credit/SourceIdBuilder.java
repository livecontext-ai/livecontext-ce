package com.apimarketplace.common.credit;

import java.util.Objects;

/**
 * Canonical {@code sourceId} keys for idempotent ledger rows.
 *
 * <h2>Markup (unified workflow + chat, V148+)</h2>
 * <ul>
 *   <li>Run-init reservation: {@code platform-markup:INIT:<scopeKind>:<scopeId>:<credentialId>}
 *       - workflow run-init ({@code scopeKind=RUN}) reserves whole-run budget.</li>
 *   <li>Per-call workflow debit: {@code platform-markup:RUN:<runId>:step:<stepId>:<callRef>}
 *       - one row per dispatched call, discriminated by {@code callRef}.</li>
 *   <li>Per-call chat debit: {@code platform-markup:STREAM:<streamId>:<toolId>:<callRef>}
 *       - chat-scope tool calls bill via STREAM-pin.</li>
 *   <li>Refund (legacy): {@code platform-markup-refund:<originalSourceId>:<nonce>}
 *       - kept for backwards-compat with V101 markup refund flow.</li>
 * </ul>
 *
 * <p><b>{@code callRef} is what makes each call its own charge, and it is
 * SERVER-GENERATED.</b> The ledger has a unique index on {@code source_id} and
 * {@code CreditService.tryReserveMarkup} answers "already reserved, zero debit"
 * for a key it has seen before. So whatever discriminates two calls inside one
 * run or one chat turn IS the thing that decides whether the second one is
 * charged. It used to be a caller-supplied {@code callIndex} that no caller ever
 * set, which left it at 0 for every call: the first generation in a scope was
 * charged and every generation after it was free. The producer
 * ({@code ToolExecutionManager}) now mints a fresh value per dispatched call, so
 * a caller has no way to force a collision and buy a free one.</p>
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
    public static final String MARKETPLACE_PURCHASE_PREFIX = "marketplace-purchase";

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
     * Workflow per-call debit key. Format:
     * {@code platform-markup:RUN:<runId>:step:<stepId>:<callRef>}.
     *
     * <p>The {@code callRef} tail is what separates the ten iterations of one
     * loop node into ten charges instead of one. It replaced a fixed-width
     * {@code epoch:spawn:iteration:itemIndex:callIndex} tail that no caller ever
     * populated, so every call in a run wrote the same key and only the first
     * one cost anything. Cannot collide with the legacy 6-segment
     * {@link #markupDebit} rows still in prod: those end in four numeric
     * segments, this one ends in a single opaque reference.
     *
     * @param callRef server-generated reference identifying THIS dispatched
     *                call. Never a value a caller can choose - see the class
     *                javadoc for why that distinction is the whole mechanism.
     */
    public static String markupDebitWithCall(String runId,
                                              String stepId,
                                              String callRef) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stepId, "stepId");
        requireCallRef(callRef);
        return MARKUP_DEBIT_PREFIX + ":RUN:" + runId
                + ":step:" + stepId
                + ":" + callRef;
    }

    /**
     * Chat per-call debit key. Format: {@code platform-markup:STREAM:<streamId>:<toolId>:<callRef>}.
     * Distinct from workflow keys via the {@code :STREAM:} discriminator so chat
     * and workflow billings can never collide on the {@code source_id} unique
     * index, even if a streamId happens to match a runId.
     *
     * <p>{@code toolId} here is the catalog tool slug (e.g. {@code openai/openai-create-image}),
     * not the UUID - both encode tool identity but the slug is what callers
     * carry in their context.
     *
     * @param callRef server-generated reference identifying THIS dispatched
     *                call; five generations in one chat turn are five charges
     *                only because this differs between them.
     */
    public static String markupDebitChat(String streamId,
                                          String toolId,
                                          String callRef) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(toolId, "toolId");
        requireCallRef(callRef);
        return MARKUP_DEBIT_PREFIX + ":STREAM:" + streamId + ":" + toolId + ":" + callRef;
    }

    /**
     * A blank {@code callRef} would silently rebuild the defect it exists to
     * close (every call in the scope keyed the same, all but the first free), so
     * it is rejected rather than defaulted.
     */
    private static void requireCallRef(String callRef) {
        if (callRef == null || callRef.isBlank()) {
            throw new IllegalArgumentException(
                    "callRef is required: without it every call in the scope keys the same ledger row "
                            + "and only the first one is charged");
        }
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

    /**
     * Marketplace purchase debit key. Format:
     * {@code marketplace-purchase:<buyerOrganizationId>:<publicationId>}.
     *
     * <p>One key per PURCHASE, and "a purchase" is exactly what the publication receipt makes
     * unique: one {@code (organization_id, publication_id)} pair. Keying the charge on the
     * publication id alone made the ledger's global {@code source_id} unique index refuse every
     * buyer after the first (HTTP 500, purchase failed). Keying it on the receipt's own pair
     * keeps a retry of the SAME purchase on the same key, so the ledger answers it as an
     * idempotent replay instead of charging it twice, including a retry after a charge whose
     * response was lost (the receipt is rolled back and re-created, the key does not change).
     */
    public static String marketplacePurchase(String buyerOrganizationId, String publicationId) {
        if (buyerOrganizationId == null || buyerOrganizationId.isBlank()) {
            throw new IllegalArgumentException("buyerOrganizationId is required: without it every buyer "
                    + "of a publication keys the same ledger row and only the first can be charged");
        }
        if (publicationId == null || publicationId.isBlank()) {
            throw new IllegalArgumentException("publicationId is required");
        }
        return MARKETPLACE_PURCHASE_PREFIX + ":" + buyerOrganizationId + ":" + publicationId;
    }

    /** True for a per-purchase key built by {@link #marketplacePurchase}. */
    public static boolean isMarketplacePurchase(String sourceId) {
        return sourceId != null && sourceId.startsWith(MARKETPLACE_PURCHASE_PREFIX + ":");
    }

}
