package com.apimarketplace.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of {@code PUT /api/users/profile/context}: what the app reports about the signed-in
 * person for the lifecycle emails. Every field is optional; an invalid value is ignored.
 *
 * @param locale         one of en fr es de pt zh
 * @param localeExplicit true when the person picked this language in the UI (always wins),
 *                       false when the app merely displayed it (never overrides a pick)
 * @param timeZone       IANA zone id from the browser
 * @param acquisition    first-touch attribution, write-once
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileContextRequest(String locale,
                                    Boolean localeExplicit,
                                    String timeZone,
                                    Acquisition acquisition) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Acquisition(String utmSource,
                              String utmMedium,
                              String utmCampaign,
                              String utmContent,
                              String utmTerm,
                              String referrer,
                              String landingPath,
                              String firstSeenAt) {
    }
}
