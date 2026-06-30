package com.apimarketplace.orchestrator.services.state.elide;

import com.apimarketplace.orchestrator.services.flag.TenantFlagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantFlagBackedElideResolver} - pins the
 * {@code default-ON, opt-out via row=false} contract introduced 2026-05-08
 * with the P2.3 chain shipping in full and {@code runningNodeIds} no longer
 * persisted to JSONB for any tenant by default.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantFlagBackedElideResolver")
class TenantFlagBackedElideResolverTest {

    @Mock private TenantFlagService flagService;

    @Test
    @DisplayName("Default ON - no tenant_flags row → elide enabled (the post-2026-05-08 contract)")
    void defaultOnWhenNoRow() {
        when(flagService.getValue(TenantFlagBackedElideResolver.FLAG_NAME, "tenant-T1"))
                .thenReturn(Optional.empty());
        TenantFlagBackedElideResolver resolver = new TenantFlagBackedElideResolver(flagService);

        assertThat(resolver.isElideEnabled("tenant-T1")).isTrue();
        verify(flagService).getValue("state-snapshot.elide-running-nodes", "tenant-T1");
    }

    @Test
    @DisplayName("Explicit ON - row value=true → elide enabled")
    void explicitOn() {
        when(flagService.getValue(TenantFlagBackedElideResolver.FLAG_NAME, "tenant-T2"))
                .thenReturn(Optional.of(true));
        TenantFlagBackedElideResolver resolver = new TenantFlagBackedElideResolver(flagService);

        assertThat(resolver.isElideEnabled("tenant-T2")).isTrue();
    }

    @Test
    @DisplayName("Explicit opt-OUT - row value=false → elide disabled (only path to OFF)")
    void explicitOptOut() {
        // The only way to disable elide for a tenant is an explicit
        // tenant_flags row with value=false. Operators use this to
        // quarantine a tenant that's hitting an unexpected post-elide
        // edge case while keeping the global default ON.
        when(flagService.getValue(TenantFlagBackedElideResolver.FLAG_NAME, "tenant-T3"))
                .thenReturn(Optional.of(false));
        TenantFlagBackedElideResolver resolver = new TenantFlagBackedElideResolver(flagService);

        assertThat(resolver.isElideEnabled("tenant-T3")).isFalse();
    }

    @Test
    @DisplayName("FLAG_NAME constant is exactly state-snapshot.elide-running-nodes")
    void flagNameConstant() {
        assertThat(TenantFlagBackedElideResolver.FLAG_NAME)
                .isEqualTo("state-snapshot.elide-running-nodes");
    }
}
