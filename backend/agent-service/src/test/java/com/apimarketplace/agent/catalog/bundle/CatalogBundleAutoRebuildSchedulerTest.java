package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

/**
 * The auto-republish loop (V381): stale active bundle → build + activate +
 * prune; everything else → strictly no side effects. CE inertness is the
 * critical guard: the bean exists on every install, but without a signing key
 * it must never touch the repository.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogBundleAutoRebuildScheduler")
class CatalogBundleAutoRebuildSchedulerTest {

    @Mock private CatalogBundleService bundleService;
    @Mock private CatalogBundleSigner signer;

    private CatalogBundleAutoRebuildScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CatalogBundleAutoRebuildScheduler(bundleService, signer, 20);
    }

    @Test
    @DisplayName("CE / unsigned install: tick is completely inert")
    void inertWithoutSigningKey() {
        when(signer.canSign()).thenReturn(false);

        scheduler.tick();

        verifyNoInteractions(bundleService);
    }

    @Test
    @DisplayName("fresh active bundle: no rebuild, no prune")
    void noOpWhenNotStale() {
        when(signer.canSign()).thenReturn(true);
        when(bundleService.isActiveBundleStale()).thenReturn(false);

        scheduler.tick();

        verify(bundleService, never()).buildBundle();
        verify(bundleService, never()).activateBundle(any());
        verify(bundleService, never()).pruneInactiveBundles(anyInt());
    }

    @Test
    @DisplayName("stale active bundle: build, activate the new one, prune with the configured retention")
    void staleTriggersRepublish() {
        when(signer.canSign()).thenReturn(true);
        when(bundleService.isActiveBundleStale()).thenReturn(true);
        CatalogBundleEntity built = new CatalogBundleEntity();
        built.setId(42L);
        built.setVersion(1234L);
        when(bundleService.buildBundle()).thenReturn(built);
        when(bundleService.activateBundle(42L)).thenReturn(built);

        scheduler.tick();

        verify(bundleService).buildBundle();
        verify(bundleService).activateBundle(42L);
        verify(bundleService).pruneInactiveBundles(20);
    }

    @Test
    @DisplayName("a failing republish never breaks the scheduler (next tick retries)")
    void failureIsSwallowed() {
        when(signer.canSign()).thenReturn(true);
        when(bundleService.isActiveBundleStale()).thenReturn(true);
        when(bundleService.buildBundle()).thenThrow(new IllegalStateException("catalog empty"));

        scheduler.tick(); // must not throw

        verify(bundleService, never()).activateBundle(any());
    }
}
