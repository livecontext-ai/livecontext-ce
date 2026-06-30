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
     */
    private String billingScopeKind;

    /**
     * V148+ billing scope id - workflow {@code runId} for RUN scope, chat
     * {@code streamId} for STREAM scope. See {@link #billingScopeKind}.
     * Forwarded from the {@code X-Lc-Billing-Scope-Id} HTTP header.
     */
    private String billingScopeId;

    /**
     * V148+ workflow step id, for {@code RUN} scope only. Lets the source-id
     * builder produce per-step idempotent keys via
     * {@code SourceIdBuilder.markupDebitWithCall(...)} instead of the simpler
     * chat shape. Forwarded from the {@code X-Lc-Billing-Step-Id} header.
     */
    private String billingStepId;

    /** V148+ workflow execution coords (epoch / spawn / iteration / itemIndex / callIndex). */
    private Integer billingEpoch;
    private Integer billingSpawn;
    private Integer billingIteration;
    private Integer billingItemIndex;
    private Integer billingCallIndex;
}
