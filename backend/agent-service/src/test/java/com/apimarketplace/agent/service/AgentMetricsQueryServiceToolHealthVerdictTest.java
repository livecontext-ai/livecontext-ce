package com.apimarketplace.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict is the whole point of the cross-tenant tool-health view: it is what
 * separates "the catalog is wrong" from "one customer pasted a bad key", and those
 * two need opposite fixes. Pin every boundary, because a verdict that leans the
 * wrong way sends someone to re-import a catalog over one user's expired token.
 */
class AgentMetricsQueryServiceToolHealthVerdictTest {

    @Test
    @DisplayName("a tool called by a single tenant can never indict the catalog")
    void singleTenantIsNeverACatalogVerdict() {
        assertThat(AgentMetricsQueryService.verdictFor(1, 1)).isEqualTo("SINGLE_TENANT");
    }

    @Test
    @DisplayName("failing for every tenant that calls it points at the catalog")
    void allTenantsAffected() {
        assertThat(AgentMetricsQueryService.verdictFor(7, 7)).isEqualTo("ALL_TENANTS");
    }

    @Test
    @DisplayName("half or more of the callers affected is widespread, not isolated")
    void halfIsWidespread() {
        // Exactly at the boundary: 3 of 6. Reading this as ISOLATED would hide a
        // real defect behind the customers who happen not to have hit it yet.
        assertThat(AgentMetricsQueryService.verdictFor(6, 3)).isEqualTo("WIDESPREAD");
    }

    @Test
    @DisplayName("just under half is isolated")
    void justUnderHalfIsIsolated() {
        assertThat(AgentMetricsQueryService.verdictFor(7, 3)).isEqualTo("ISOLATED");
    }

    @Test
    @DisplayName("one tenant out of many is a credential problem, not a catalog one")
    void oneOfManyIsIsolated() {
        assertThat(AgentMetricsQueryService.verdictFor(12, 1)).isEqualTo("ISOLATED");
    }

    @Test
    @DisplayName("zero callers degrades to single-tenant rather than dividing by zero")
    void zeroCallersDoesNotBlowUp() {
        assertThat(AgentMetricsQueryService.verdictFor(0, 0)).isEqualTo("SINGLE_TENANT");
    }
}
