package com.apimarketplace.orchestrator.controllers.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TenantStatsDto record.
 */
@DisplayName("TenantStatsDto")
class TenantStatsDtoTest {

    @Test
    @DisplayName("Should store all fields correctly")
    void shouldStoreAllFields() {
        TenantStatsDto dto = new TenantStatsDto("tenant-1", 10, 5, 20, 3);

        assertThat(dto.tenantId()).isEqualTo("tenant-1");
        assertThat(dto.workflowCount()).isEqualTo(10);
        assertThat(dto.interfaceCount()).isEqualTo(5);
        assertThat(dto.tableCount()).isEqualTo(20);
        assertThat(dto.agentCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should handle zero counts")
    void shouldHandleZeroCounts() {
        TenantStatsDto dto = new TenantStatsDto("empty-tenant", 0, 0, 0, 0);

        assertThat(dto.workflowCount()).isZero();
        assertThat(dto.interfaceCount()).isZero();
        assertThat(dto.tableCount()).isZero();
        assertThat(dto.agentCount()).isZero();
    }
}
