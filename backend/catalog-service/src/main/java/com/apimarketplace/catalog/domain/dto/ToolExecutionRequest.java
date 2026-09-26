package com.apimarketplace.catalog.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request for tool execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionRequest {
    private Map<String, Object> parameters;
    private Map<String, String> metadata;
    private String context;

    /**
     * List of JSON paths to expand (bypass truncation).
     * Example: ["payload.body.data", "attachments"]
     * If null or empty, default truncation applies to all large fields.
     */
    private List<String> expand;

    /**
     * Cap every top-level array under the response root at this many items.
     * Excess items collapse into a {@code _shape:"array_digest"} marker with
     * {@code preview_items}, {@code total_items}, {@code skipped_from},
     * {@code skipped_to}. Used by chat agents to paginate APIs without a
     * native cursor parameter, or to inspect just the structure of a large
     * response. Honoured only in AGENT mode (i.e. when
     * {@link #billingScopeKind} equals {@code "STREAM"} case-insensitively).
     *
     * <p>Wire name is {@code max_items} (snake_case) - sent by
     * {@code CatalogExecuteModule.executeCatalogExecute} on agent calls and by
     * any external caller using the documented field name. {@link JsonAlias}
     * permits both {@code maxItems} and {@code max_items} on the wire.
     */
    @JsonAlias({"max_items"})
    private Integer maxItems;

    /**
     * Legacy per-call override hint. Sealed from external POSTs via
     * {@link JsonIgnore} so callers cannot resurrect a code path the
     * architecture says is gone. Internal callers that historically set
     * {@code "both"} can leave the field null - the catalog defaults to the
     * same fallback semantics whenever no explicit {@link #credentialSource}
     * is supplied.
     *
     * @deprecated Use {@link #credentialSource} instead. The field stays only
     *             so deserializing legacy bodies doesn't fail; the value is
     *             ignored at the wire level via {@link JsonIgnore}.
     */
    @Deprecated
    @JsonIgnore
    private String credentialModeOverride;

    /**
     * Workflow author's explicit credential choice, propagated from the UI
     * toggle (CredentialSection.tsx) on a per-step basis. Values:
     * <ul>
     *   <li>{@code "user"} - use the user's credential only, no fallback to platform.</li>
     *   <li>{@code "platform"} - use the platform credential only, no fallback to user.</li>
     *   <li>{@code null} (agentic paths: chat agent, image-gen, embedded agent) -
     *       try user first, fall back to platform if pricing is published for
     *       the endpoint.</li>
     * </ul>
     * When set, this value is durci: the catalog resolves the named pool
     * exclusively and surfaces {@code credentials_required} on miss, regardless
     * of whether the other pool has a credential available. This honors the
     * workflow author's deliberate design-time choice.
     */
    private String credentialSource;

    /**
     * Workflow node's selected user credential id, set when a workflow author
     * chooses a specific account in the builder. Catalog execution must honor
     * this id strictly for {@code credentialSource = "user"} instead of falling
     * back to the default credential for the same integration.
     */
    private Long selectedCredentialId;

    /**
     * Workflow node's pinned platform credential id, set when
     * {@code credentialSource = "platform"}. Forwarded so the catalog response
     * can stamp it back for billing-pin lookup at run completion.
     */
    private Long platformCredentialId;

    /**
     * The caller's user credential named rather than numbered, for a step whose
     * credential is chosen at RUN time (a table row, a trigger field, a split
     * item - none of which know database ids). Resolved here, against the
     * endpoint's own credential requirement, because the requirement is
     * catalog-side knowledge. Honored only with {@code credentialSource="user"}.
     */
    private String selectedCredentialName;

    /**
     * Refuse rather than degrade when the named or numbered credential cannot be
     * matched. Absent for every author-time pin, which keeps their deliberately
     * forgiving fallback to the integration default exactly as it is.
     */
    private Boolean credentialSelectionStrict;

    /**
     * Opt-out from inline-binary dehydration. By default catalog-service walks
     * the response and replaces any large base64 leaf with a {@code FileRef}
     * uploaded to MinIO, so the agent never sees megabytes of base64 (which
     * blows token budgets) and the workflow {@code step.output} JSONB stays
     * lean. Set this to {@code true} when the caller genuinely needs the raw
     * bytes inline (e.g. a workflow node that re-encodes the binary into an
     * email attachment, or a debug node inspecting the original payload).
     * Default {@code null}/{@code false} → dehydrate.
     */
    private Boolean inlineBinaries;

    /**
     * How long THIS call may spend waiting out a provider's rate-limit refusal, in seconds.
     *
     * <p>Null leaves the platform default in place (the usual case: a caller with no retry logic
     * of its own benefits from the 429 handling without knowing it exists). {@code 0} disables
     * retrying for this call, which is what a workflow node sends when its author already paces
     * the calls themselves - a loop that calls, waits and comes back would otherwise issue three
     * requests per turn instead of one, hammering the provider hardest for the author who was
     * most careful.
     *
     * <p>Seconds, not milliseconds, because this is set by a person in a form field.
     */
    private Integer providerRetryMaxWaitSeconds;

    /**
     * V148+ billing scope discriminator. Forwarded from the
     * {@code X-Lc-Billing-Scope-Kind} HTTP header by the catalog controller.
     * {@code "RUN"} when the caller is a workflow, {@code "STREAM"} when the
     * caller is a chat session. Null when neither applies (legacy callers /
     * test fixtures / non-billable internal tools).
     *
     * <p>Used by {@code CatalogToolBillingService} to build a {@code BillingScope}
     * for the post-success commit. RUN-priority semantics enforced upstream:
     * if both RUN and STREAM are present, callers always pass RUN.
     *
     * <p><b>Cross-cutting side-effects</b> - this single discriminator now
     * gates three independent behaviours in {@code ToolExecutionManager}:
     * <ol>
     *   <li>Billing scope routing (the original use, above).</li>
     *   <li>{@code ResponseShaper.Mode}: STREAM → AGENT (array digests +
     *       per-leaf truncation), otherwise → WORKFLOW (preserve shape).</li>
     *   <li>{@code ResponseCache} enable/disable: STREAM consults the 5-min
     *       Redis cache (chat-agent expand dedup); RUN / null bypasses it
     *       so workflow re-fires always see fresh upstream data. Fix origin:
     *       Gmail Auto-Labeler workflow re-processing identical emails
     *       across two epochs fired 30s apart.</li>
     * </ol>
     * Callers that need to change the cache or shaping behaviour without
     * changing the billing scope must introduce a new dedicated field;
     * piggy-backing on this one is by design (the "am I chat or am I
     * workflow?" question naturally answers all three).
     *
     * <p><b>Header-only, like the generation trio below.</b> The gateway strips
     * {@code X-Lc-Billing-*} off every inbound request precisely so a client
     * cannot name its own billing scope, and leaving the same value
     * deserializable left the other half of that door open. Naming a scope is
     * not cosmetic: an existing scope pin is what BYPASSES the delinquent-account
     * refusal, and the pin is keyed on (scopeKind, scopeId, credentialId) with
     * no user in it, so naming an older run also charges that run's older,
     * cheaper pricing version and writes another scope's pin onto this ledger
     * row. Every legitimate caller sets these through the header.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String billingScopeKind;

    /**
     * V148+ billing scope id - workflow {@code runId} for RUN scope, chat
     * {@code streamId} for STREAM scope. See {@link #billingScopeKind}.
     * Forwarded from the {@code X-Lc-Billing-Scope-Id} HTTP header, and only
     * from there. @see #billingScopeKind for why it is sealed off the wire.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String billingScopeId;

    /**
     * V148+ workflow step id, for {@code RUN} scope only. Lets the source-id
     * builder produce per-step keys via
     * {@code SourceIdBuilder.markupDebitWithCall(...)} instead of the simpler
     * chat shape. Forwarded from the {@code X-Lc-Billing-Step-Id} header.
     *
     * <p>Note what is deliberately NOT here: the per-call discriminator that
     * separates the second charge in a scope from the first. It used to live on
     * this DTO as {@code billingEpoch/Spawn/Iteration/ItemIndex/CallIndex}, and
     * no caller ever set any of them, so the sourceId was identical for every
     * call in a run or a chat turn and the ledger's idempotency fast-path made
     * everything after the first one free. Those fields were also plain
     * deserializable JSON, i.e. a value the caller could pin. The discriminator
     * is now minted server-side per dispatch in {@code ToolExecutionManager},
     * for the same reason {@link #generationQuantity} is: it decides the amount
     * charged, so it must not be reachable from the wire. The three scope
     * fields are now sealed for that same reason, which is the rule those
     * removed fields were the first casualty of.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String billingStepId;

    /**
     * Workflow (plan) id the call ran under, for product analytics ONLY. Forwarded
     * from the {@code X-Lc-Workflow-Id} header by the controller, never from the
     * body (sealed like the billing scope, so a caller cannot attribute its call
     * to someone else's workflow). Null outside a workflow run.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String analyticsWorkflowId;

    /**
     * Workflow node id the call ran under, for product analytics ONLY. Forwarded
     * from the {@code X-Lc-Node-Id} header. Deliberately distinct from
     * {@link #billingStepId}: that one shapes the ledger source-id, so lighting it
     * up for attribution would change what is charged. This one changes nothing.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String analyticsNodeId;

    /**
     * True when the result becomes a workflow STEP's output (a StepNode / FindNode call), from the
     * {@code X-Lc-Step-Output} header only. It selects {@code ResponseShaper.Mode.STEP_OUTPUT},
     * which lifts the 4 KB text-leaf clip: that output is the data every downstream node reads.
     * An agent inside a workflow calls with billing scope RUN too, so the scope cannot say it.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean stepOutput;

    /**
     * V428 generation model priced for this call, populated ONLY from the
     * {@code X-Lc-Generation-Model} header by the controller. Null for an
     * ordinary tool call.
     *
     * <p>Selects a PRICE row rather than a request parameter: one endpoint can
     * back several models at different rates, so the endpoint id alone is not
     * enough to know what to charge.
     *
     * <p>{@code @JsonIgnore} is load-bearing, not tidiness: this field and
     * {@link #generationQuantity} DECIDE the amount charged. Leaving them
     * deserializable would let anyone who can reach the execute endpoint post a
     * body naming a cheap model, or a quantity of zero, and generate for free.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String generationModelId;

    /**
     * V428 size of the call in PLATFORM units (seconds, assets, characters),
     * populated ONLY from the {@code X-Lc-Generation-Quantity} header.
     *
     * <p>Computed once, server side, from parameters that were already
     * validated, and carried rather than re-derived: by the time the request
     * reaches the provider the value may have been converted into the
     * provider's own unit (milliseconds), and pricing must stay in the
     * platform's.
     *
     * <p>It is NOT expressed in the price's unit. What a rate is charged per is
     * a property of the published price row, which an administrator can change
     * (per second today, per minute tomorrow), so converting the measurement
     * here would scale it by one unit while the rate applied another. The
     * conversion belongs to whoever reads the rate.
     *
     * <p>See {@link #generationModelId} for why this is never deserialized.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.math.BigDecimal generationQuantity;

    /**
     * The PLATFORM unit {@link #generationQuantity} is counted in: call,
     * second, image or character.
     *
     * <p>Travels with the number because the number alone cannot be checked
     * against the published rate. A row priced per image and a call measured in
     * seconds both arrive as a bare 10, and multiplying them charges a ten
     * second clip ten times the per-image rate. Nothing downstream could
     * notice, because both halves look perfectly ordinary on their own.
     *
     * <p>See {@link #generationModelId} for why this is never deserialized: it
     * decides an amount, so a caller able to set it could name a unit that
     * makes its call look small.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String generationQuantityUnit;

    /**
     * What the CHOICES in this call do to its price: 2 for a render the model
     * sells at twice its published rate, 1.2 for one carrying two reference
     * images priced at a tenth each.
     *
     * <p>Distinct from {@link #generationQuantity}, which says how BIG the call
     * is. A ten second clip is ten seconds at every resolution; what changes is
     * what those ten seconds cost. Folding one into the other would make the
     * run report a duration nobody asked for.
     *
     * <p>Null means "at the published rate", which is what every model without
     * declared modifiers sends and what every caller sent before they existed.
     *
     * <p>See {@link #generationModelId} for why this is never deserialized: it
     * multiplies the amount charged, so a caller able to set it could send
     * 0 and take the generation for free.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.math.BigDecimal generationPriceMultiplier;

    /**
     * The caller has ALREADY reserved and will settle the charge for this call
     * itself, so the catalog's own billing layer must neither charge again nor
     * refuse for lack of a scope.
     *
     * <p>Exactly one in-process caller sets it:
     * {@code CeCatalogRelayService}, which runs its own
     * reserve → execute → commit/release around this execution against the
     * linked cloud account. Its reservation is taken BEFORE the call and it
     * refuses outright without a strictly positive markup, so a relayed call is
     * always charged once and never zero times. Without this flag the execution
     * looks scope-less from inside, and the fail-closed rule on a resold
     * generation would refuse a call that was already paid for.
     *
     * <p>{@code @JsonIgnore} is load-bearing for the same reason it is on
     * {@link #generationModelId} and {@link #generationQuantity}: this field
     * decides whether the call is charged. Deserializing it would let anyone who
     * can reach the execute endpoint post {@code {"billingOwnedByCaller":true}}
     * and take every resold generation for free. It is reachable only by an
     * in-process caller that builds this DTO itself.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean billingOwnedByCaller;
}
