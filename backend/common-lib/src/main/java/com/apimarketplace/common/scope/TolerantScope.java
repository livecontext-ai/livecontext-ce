package com.apimarketplace.common.scope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method (or enclosing type) as intentionally using the tolerant
 * (owner-OR-org) scope predicate from
 * {@link ScopeGuard#isInOwnerOrOrgScope(String, String, String, String)}.
 *
 * <p>Required by each service's {@code OrgScopePredicateInvariantTest}
 * ArchUnit rule - any call to {@code isInOwnerOrOrgScope} on a method NOT
 * carrying this annotation fails the build for that service. The reference
 * implementation lives in {@code orchestrator-service} and other services
 * mirror it (ArchUnit only scans its host module's classpath). The
 * {@link #reason()} string MUST explain why cross-workspace tolerance is
 * acceptable on this surface (typically because an upstream caller has
 * already gated entry).
 *
 * <p>Examples of legitimate tolerance:
 * <ul>
 *   <li>WS channel authorizers consumed by the gateway after the gateway
 *       has resolved the session against the user's memberships.</li>
 *   <li>Internal cascade deletes invoked by another service that has
 *       already validated the caller's scope.</li>
 * </ul>
 *
 * <p>Public auth-required HTTP endpoints MUST use
 * {@link ScopeGuard#isInStrictScope(String, String, String, String)} instead.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TolerantScope {
    /**
     * Human-readable justification for the tolerance. Must be specific enough
     * that a future reader can decide whether the exception is still valid
     * (e.g. "WS channel - caller already gated at gateway ChannelAuthorizer").
     */
    String reason();
}
