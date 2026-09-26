package com.apimarketplace.agent.skill.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillBundleStartupSync - one sync at boot, through the backoff")
class SkillBundleStartupSyncTest {

    @Mock private SkillBundleSyncScheduler scheduler;

    @Test
    @DisplayName("boot runs tickIfDue(), never the backoff-bypassing tick(): a restart loop must not re-download each boot")
    void startupHonoursBackoff() {
        new SkillBundleStartupSync(scheduler).syncOnceOnStartup();

        verify(scheduler, timeout(2_000).times(1)).tickIfDue();
        verify(scheduler, never()).tick();
    }
}
