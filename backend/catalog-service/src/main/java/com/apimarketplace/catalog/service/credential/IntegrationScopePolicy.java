package com.apimarketplace.catalog.service.credential;

import com.apimarketplace.common.scope.GrantedScopes;

import java.util.List;
import java.util.stream.Stream;

/**
 * What a STANDARD connection to an integration can and cannot grant.
 *
 * <p>An integration's catalog template declares two scope lists:
 * {@code oauth2Config.scopes}, which the platform-shared OAuth client actually
 * requests, and {@code oauth2Config.byokOnlyScopes}, which it deliberately never
 * requests because the provider treats them as restricted (Gmail {@code readonly} is
 * the reference case: Google requires CASA verification for it, so the shared,
 * verified consent screen stays narrow). A restricted scope is obtainable only
 * through a CUSTOM OAuth connection, where the user supplies their own OAuth client
 * and declares the scope on their own consent screen.
 *
 * <p>The builder's own banner applies the same rule in TypeScript
 * ({@code resolvePlatformScopeList} / {@code MissingScopesBanner}), because the two
 * answers describe the same endpoint to the same person, one in a panel and one through
 * an agent. They are not identical today and it is worth knowing where: the banner has
 * no embedded-auth branch, so on a self-hosted install it still routes a restricted
 * scope to a custom OAuth connection while this class says a standard connect grants it.
 * That divergence predates this class and errs towards over-warning, which is the safe
 * direction for a non-blocking panel. Any change to the rule belongs in both.
 *
 * <p><b>Edition.</b> There is no platform-shared OAuth app on an embedded-auth
 * install: whatever supplied the client id and secret, the OAuth client is the user's
 * own, and the authorize request carries the FULL catalog scope set
 * ({@code scopes} plus {@code byokOnlyScopes} - see {@code OAuth2Service.initiate}).
 * The platform/restricted split is a managed-cloud concept, so an embedded install
 * must never be told to go and bring an OAuth client it already is. That is keyed on
 * {@code auth.mode=embedded}, the same signal {@code OAuth2Service} uses, and NOT on
 * "self-hosted": a self-hosted ENTERPRISE install runs Keycloak, has a shared app,
 * and belongs on the managed side of this line.
 *
 * <p><b>{@link #unknown()} is not "nothing is restricted".</b> It is "this template
 * declares no OAuth policy", which happens for a custom API and for any row whose
 * metadata could not be read. Every consumer omits the capability rather than
 * asserting one, because the sentence it produces is relayed to a person: telling
 * them to go and register an OAuth application they do not need costs far more than
 * saying nothing.
 */
public record IntegrationScopePolicy(
        boolean declared,
        List<String> platformScopes,
        List<String> byokOnlyScopes,
        boolean ownClientIsTheOnlyClient) {

    private static final IntegrationScopePolicy UNKNOWN =
            new IntegrationScopePolicy(false, List.of(), List.of(), false);

    /** No OAuth policy is known for this integration. */
    public static IntegrationScopePolicy unknown() {
        return UNKNOWN;
    }

    public static IntegrationScopePolicy declared(List<String> platformScopes,
                                                  List<String> byokOnlyScopes,
                                                  boolean ownClientIsTheOnlyClient) {
        // Stored AS DECLARED, blanks aside. Splitting here would destroy a scope that
        // legitimately contains a separator (workday declares "Tenant Non-Configurable"),
        // and the comparison below already reads both shapes: GrantedScopes matches a
        // requirement against a whole entry as well as against the pieces it splits into.
        // Normalising at construction would keep the comma-joined case working and quietly
        // break the space-carrying one, which is the pair that rule exists to hold together.
        return new IntegrationScopePolicy(
                true,
                nonBlank(platformScopes),
                nonBlank(byokOnlyScopes),
                ownClientIsTheOnlyClient);
    }

    private static List<String> nonBlank(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        return scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * The scopes a standard connection can obtain. On an install where the user's own
     * OAuth client is the only client there is, that is everything the catalog
     * declares.
     */
    public List<String> grantableByStandardConnection() {
        if (!declared) {
            return List.of();
        }
        if (!ownClientIsTheOnlyClient) {
            return platformScopes;
        }
        return Stream.concat(platformScopes.stream(), byokOnlyScopes.stream())
                .distinct()
                .toList();
    }

    /**
     * Which of {@code required} a standard connection would never grant, so a caller
     * can say so before the user spends a consent screen finding out.
     *
     * <p>Keyed on absence from the grantable set rather than presence in
     * {@code byokOnlyScopes}, which is what the builder's banner settled on: it also
     * catches a required scope the catalog declared in NEITHER list, and that scope
     * is just as ungrantable for being undeclared.
     *
     * <p>Compared through {@link GrantedScopes}, the same reading the execution-time
     * scope check uses, so a scope that legitimately contains a separator is treated
     * identically on both sides rather than being split here and matched whole there.
     *
     * <p>Empty whenever the policy is {@link #unknown()}, by the rule on this class.
     */
    public List<String> scopesNeedingOwnOAuthClient(List<String> required) {
        if (!declared) {
            return List.of();
        }
        return List.copyOf(GrantedScopes.missingFrom(required, grantableByStandardConnection()));
    }
}
