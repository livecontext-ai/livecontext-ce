package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The startup sync must run exactly one sync after boot, and it MUST go through the
 * injected scheduler bean (the Spring proxy) so the {@code @SchedulerLock} on
 * {@code tick()} fires - a self-invocation would bypass AOP and race the cron tick.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogBundleStartupSync - one sync on ApplicationReadyEvent, via the proxy")
class CatalogBundleStartupSyncTest {

    @Mock private CatalogBundleSyncScheduler scheduler;

    @Test
    @DisplayName("syncOnceOnStartup() triggers exactly one scheduler.tickIfDue(), never the backoff-bypassing tick()")
    void runsOneTickThroughTheScheduler() {
        new CatalogBundleStartupSync(scheduler).syncOnceOnStartup();

        // Runs on a daemon thread → await it. timeout() also proves it's called only once.
        verify(scheduler, timeout(2_000).times(1)).tickIfDue();
        verify(scheduler, never()).tick();
    }
}
