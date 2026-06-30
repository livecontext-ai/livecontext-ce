package com.apimarketplace.auth.service.license;

/**
 * Resource limit resolved from a signed self-hosted enterprise license.
 *
 * @param licensed whether a valid active license was found
 * @param planCode effective local plan code exposed to existing entitlement clients
 * @param limit maximum resource count; {@code null} means explicitly unlimited
 */
public record EnterpriseLicenseResourceLimit(
        boolean licensed,
        String planCode,
        Integer limit) {

    public static EnterpriseLicenseResourceLimit unlicensed() {
        return new EnterpriseLicenseResourceLimit(false, EnterpriseLicenseService.NO_LICENSE_PLAN_CODE, null);
    }
}
