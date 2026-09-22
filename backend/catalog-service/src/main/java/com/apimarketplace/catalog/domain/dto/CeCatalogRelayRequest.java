package com.apimarketplace.catalog.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Body of a CE catalog relay call ({@code POST /api/ce-catalog/tools/{apiSlug}/{toolSlug}/execute}).
 *
 * <p>Deliberately a strict SUBSET of {@link ToolExecutionRequest}: only the
 * execution-shaping fields a linked CE install may influence. NO credential
 * fields and NO billing fields - the cloud resolves the platform credential and
 * the markup billing server-side and never trusts CE input for either (a
 * CE-supplied credential id or billing scope would let an install steer whose
 * key is used or whose account is billed).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CeCatalogRelayRequest {

    /** Tool parameters, forwarded verbatim to the catalog execution. */
    private Map<String, Object> parameters;

    /** JSON paths to expand (bypass truncation), same semantics as {@link ToolExecutionRequest#getExpand()}. */
    private List<String> expand;

    /** Top-level array cap, same semantics as {@link ToolExecutionRequest#getMaxItems()}. */
    private Integer maxItems;

    /** Opt-out from inline-binary dehydration, same semantics as {@link ToolExecutionRequest#getInlineBinaries()}. */
    private Boolean inlineBinaries;

    /**
     * How long ONE provider call may spend waiting out a rate-limit refusal, in seconds, as the
     * install's own node decided. Same semantics as
     * {@link ToolExecutionRequest#getProviderRetryMaxWaitSeconds()}: absent leaves the cloud's
     * budget in place, 0 says the install's workflow paces itself and the cloud must not re-send
     * underneath it.
     *
     * <p>Belongs in this strict subset because it shapes EXECUTION and nothing else: it names no
     * credential, bills nobody, and the cloud caps it at its own budget, so the worst a hostile
     * install can ask for is fewer requests than the cloud would have made. Without it a relayed
     * step could not express "do not retry", and a CE author who paced their own loop would have
     * the cloud multiply their requests to a provider that asked them to slow down - the exact
     * conflict the field exists to end, surviving in one edition only.
     */
    private Integer providerRetryMaxWaitSeconds;
}
