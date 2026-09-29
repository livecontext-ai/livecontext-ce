package com.apimarketplace.orchestrator.testsupport;

import com.apimarketplace.auth.client.access.OrgAccessGuard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Guards for controller tests that are not about access control: a Mockito mock of
 * OrgAccessGuard answers canWrite=false by default, which would turn every org-tagged
 * run into a 403 and hide what those tests actually check.
 */
public final class OrgAccessGuardStubs {

    private OrgAccessGuardStubs() {
    }

    /** A guard that lets every write through (the per-member deny-list is empty). */
    public static OrgAccessGuard allowAll() {
        OrgAccessGuard guard = mock(OrgAccessGuard.class);
        lenient().when(guard.canWrite(any(), any(), any(), any(), any())).thenReturn(true);
        lenient().when(guard.canAccess(any(), any(), any(), any(), any())).thenReturn(true);
        return guard;
    }
}
