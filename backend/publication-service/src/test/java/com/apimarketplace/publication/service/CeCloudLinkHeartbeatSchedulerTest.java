package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeCloudLinkHeartbeatScheduler")
class CeCloudLinkHeartbeatSchedulerTest {

    @Mock
    private CeCloudLinkRepository cloudLinkRepository;
    @Mock
    private CloudLinkService cloudLinkService;

    private CeCloudLinkHeartbeatScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CeCloudLinkHeartbeatScheduler(cloudLinkRepository, cloudLinkService);
    }

    private CeCloudLinkEntity buildLink(Long tenantId) {
        CeCloudLinkEntity link = new CeCloudLinkEntity();
        link.setTenantId(tenantId);
        return link;
    }

    @Nested
    @DisplayName("sweepLinkedTenants")
    class SweepLinkedTenants {

        @Test
        @DisplayName("Should skip the tick and never ping the cloud when no tenants are linked")
        void shouldSkipTickWhenNoLinkedTenants() {
            when(cloudLinkRepository.findAll()).thenReturn(List.of());

            scheduler.sweepLinkedTenants();

            // Empty list short-circuits before the loop: the cloud is never contacted.
            verifyNoInteractions(cloudLinkService);
        }

        @Test
        @DisplayName("Should send a heartbeat for every linked tenant in the sweep")
        void shouldSendHeartbeatForEveryLinkedTenant() {
            CeCloudLinkEntity linkA = buildLink(1L);
            CeCloudLinkEntity linkB = buildLink(2L);
            when(cloudLinkRepository.findAll()).thenReturn(List.of(linkA, linkB));
            when(cloudLinkService.sendHeartbeat(any())).thenReturn(CloudLinkService.HeartbeatOutcome.OK);

            scheduler.sweepLinkedTenants();

            // Linear loop visits each linked tenant exactly once.
            verify(cloudLinkService).sendHeartbeat(linkA);
            verify(cloudLinkService).sendHeartbeat(linkB);
            verify(cloudLinkService, times(2)).sendHeartbeat(any());
        }

        @Test
        @DisplayName("Should route every HeartbeatOutcome variant without throwing")
        void shouldRouteEveryOutcomeVariantWithoutThrowing() {
            // One link per enum constant so the switch (ok / revoked / default error buckets)
            // is exercised across the full outcome space.
            CeCloudLinkEntity ok = buildLink(1L);
            CeCloudLinkEntity registered = buildLink(2L);
            CeCloudLinkEntity pendingRegister = buildLink(3L);
            CeCloudLinkEntity tokenUnavailable = buildLink(4L);
            CeCloudLinkEntity revoked = buildLink(5L);
            CeCloudLinkEntity notFound = buildLink(6L);
            CeCloudLinkEntity transientFailure = buildLink(7L);
            when(cloudLinkRepository.findAll()).thenReturn(
                    List.of(ok, registered, pendingRegister, tokenUnavailable, revoked, notFound, transientFailure));
            when(cloudLinkService.sendHeartbeat(ok)).thenReturn(CloudLinkService.HeartbeatOutcome.OK);
            when(cloudLinkService.sendHeartbeat(registered)).thenReturn(CloudLinkService.HeartbeatOutcome.REGISTERED);
            when(cloudLinkService.sendHeartbeat(pendingRegister)).thenReturn(CloudLinkService.HeartbeatOutcome.PENDING_REGISTER);
            when(cloudLinkService.sendHeartbeat(tokenUnavailable)).thenReturn(CloudLinkService.HeartbeatOutcome.TOKEN_UNAVAILABLE);
            when(cloudLinkService.sendHeartbeat(revoked)).thenReturn(CloudLinkService.HeartbeatOutcome.REVOKED);
            when(cloudLinkService.sendHeartbeat(notFound)).thenReturn(CloudLinkService.HeartbeatOutcome.NOT_FOUND);
            when(cloudLinkService.sendHeartbeat(transientFailure)).thenReturn(CloudLinkService.HeartbeatOutcome.TRANSIENT_FAILURE);

            scheduler.sweepLinkedTenants();

            // Every outcome (ok/registered, revoked/not-found, default-error) is consumed and
            // each tenant is still pinged exactly once.
            verify(cloudLinkService, times(7)).sendHeartbeat(any());
        }

        @Test
        @DisplayName("Should keep sweeping remaining tenants after one heartbeat throws an unexpected RuntimeException")
        void shouldContinueLoopWhenOneHeartbeatThrows() {
            CeCloudLinkEntity boom = buildLink(1L);
            CeCloudLinkEntity survivor = buildLink(2L);
            when(cloudLinkRepository.findAll()).thenReturn(List.of(boom, survivor));
            when(cloudLinkService.sendHeartbeat(boom))
                    .thenThrow(new RuntimeException("unexpected token-fetch blowup"));
            when(cloudLinkService.sendHeartbeat(survivor))
                    .thenReturn(CloudLinkService.HeartbeatOutcome.OK);

            // The last-line catch must absorb the exception so the scheduler thread survives.
            scheduler.sweepLinkedTenants();

            // The tenant after the failing one is still processed (loop did not abort).
            verify(cloudLinkService).sendHeartbeat(survivor);
        }

        @Test
        @DisplayName("Should swallow the exception and not propagate when a single linked tenant throws")
        void shouldSwallowExceptionAndCompleteSingleTenantSweep() {
            CeCloudLinkEntity boom = buildLink(99L);
            when(cloudLinkRepository.findAll()).thenReturn(List.of(boom));
            when(cloudLinkService.sendHeartbeat(boom))
                    .thenThrow(new IllegalStateException("kaboom"));

            // A single failing tenant must not propagate the exception out of the sweep:
            // sweepLinkedTenants returns normally instead of rethrowing.
            assertThatCode(() -> scheduler.sweepLinkedTenants()).doesNotThrowAnyException();

            // The failing tenant was pinged exactly once (no retry inside the same tick).
            verify(cloudLinkService, times(1)).sendHeartbeat(boom);
        }
    }
}
