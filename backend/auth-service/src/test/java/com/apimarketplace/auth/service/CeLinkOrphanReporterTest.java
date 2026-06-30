package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkOrphanReporter")
class CeLinkOrphanReporterTest {

    @Mock private CeLinkRepository ceLinkRepository;
    @Mock private CeLinkHeartbeatRepository heartbeatRepository;

    private MeterRegistry registry;
    private CeLinkOrphanReporter reporter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        reporter = new CeLinkOrphanReporter(ceLinkRepository, heartbeatRepository, registry, 30L);
        reporter.registerGauges();
    }

    @Test
    @DisplayName("registersBothGaugesAtZeroBeforeFirstRefresh - Prometheus sees the metric even when not yet populated")
    void gauges_register_at_zero_before_refresh() {
        assertThat(registry.find("ce_link_active_total").gauge().value()).isZero();
        assertThat(registry.find("ce_link_stale_active_total").gauge().value()).isZero();
    }

    @Test
    @DisplayName("refresh populates both gauges from the configured repositories")
    void refresh_populates_gauges() {
        when(ceLinkRepository.countByStatus(CeLink.Status.ACTIVE)).thenReturn(123L);
        CeLinkHeartbeat hb1 = new CeLinkHeartbeat(UUID.randomUUID(), Instant.now(), "h", 1, "v");
        CeLinkHeartbeat hb2 = new CeLinkHeartbeat(UUID.randomUUID(), Instant.now(), "h", 1, "v");
        when(heartbeatRepository.findStaleActive(any(Instant.class))).thenReturn(List.of(hb1, hb2));

        reporter.refresh();

        assertThat(registry.find("ce_link_active_total").gauge().value()).isEqualTo(123.0);
        assertThat(registry.find("ce_link_stale_active_total").gauge().value()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("refreshFailureNeverKillsTheSchedulerThread - exception is swallowed, gauges keep their last value")
    void refresh_failure_swallowed() {
        when(ceLinkRepository.countByStatus(CeLink.Status.ACTIVE)).thenThrow(new RuntimeException("DB hiccup"));

        // Must not throw.
        reporter.refresh();

        // Gauges keep their initial zero - refresh didn't get past the throwing call.
        assertThat(registry.find("ce_link_active_total").gauge().value()).isZero();
    }
}
