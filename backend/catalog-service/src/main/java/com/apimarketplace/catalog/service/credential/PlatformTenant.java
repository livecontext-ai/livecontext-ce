package com.apimarketplace.catalog.service.credential;

/**
 * The tenant the PLATFORM own credentials are stored under.
 *
 * <p>Named once because three places ask the credential service for a platform key and
 * a fourth now asks whether one exists. A gate that spelled the tenant differently from
 * the executor would answer about a pool nothing reads.
 */
public final class PlatformTenant {

    /** The tenant id the platform keys are filed under. */
    public static final String ID = "PLATFORM";

    private PlatformTenant() {
    }
}
