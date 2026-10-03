package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.credential.client.dto.CredentialScopesDto;
import com.apimarketplace.credential.client.CredentialClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "may this tenant's data be restricted-scope user data" for the web-search egress
 * gate (LC-029), and is the PRODUCER the gate was missing.
 *
 * <p><b>Why this class exists.</b> {@code WebSearchToolsProvider} has carried a
 * restricted-scope destination bound since the first LC-029 pass, but it read a tag on the
 * execution credentials that nothing anywhere wrote, so it classified every execution
 * STANDARD and refused nothing. A guard with no input is not a control. This class supplies
 * the input from a fact the platform already holds: whether one of the tenant's ACTIVE
 * credentials was GRANTED a restricted Google scope (full mail,
 * gmail.readonly/modify/compose/insert/metadata/settings.*, drive, drive.readonly,
 * drive.metadata*, drive.scripts, drive.activity*). The integration name only preselects which
 * credentials are worth a scope lookup; gmail.send-only or Calendar-only tenants are not flagged.
 *
 * <p><b>The classification is not redefined here.</b> The restricted set comes from
 * {@link RestrictedDataPolicy} (the same component the retention sweeper, the observability
 * writer and the step-payload persister use), so the vocabulary cannot drift between sinks.
 * The integration names come from {@link CredentialClient#getConfiguredIntegrations(String)},
 * the same call the agent envelope uses to tell an agent which integrations are configured.
 *
 * <p><b>Evidence strength decides denial strength.</b> A tenant-level answer means "this
 * execution MIGHT carry mailbox-derived content", not "it does": nothing here inspects the
 * turn. {@link Mode} is what turns that weaker evidence into a proportionate denial, and
 * {@code WebSearchToolsProvider} keeps the strict, fail-closed reading for the stronger
 * evidence (an execution explicitly tagged on its credentials). See
 * {@code WebSearchToolsProvider.checkRestrictedScopeEgress}.
 *
 * <p><b>Fail-open on a lookup failure, stated plainly.</b>
 * {@code getConfiguredIntegrations} returns an empty set both when the tenant has no
 * credentials and when auth-service is unreachable, and the two cannot be told apart through
 * that API. An auth-service outage therefore relaxes this control instead of blocking every
 * fetch in the product. That is a deliberate availability trade, not an oversight: the
 * strong-evidence path (an explicitly tagged execution) is unaffected, and an operator who
 * needs the strict reading everywhere sets {@link Mode#STRICT} together with a destination
 * allow-list.
 */
@Slf4j
@Component
public class RestrictedScopeTenantDetector {

    /** Property selecting how far a tenant-level answer is allowed to go. */
    public static final String MODE_PROPERTY = "websearch.egress.restricted-scope-mode";

    /**
     * How much a tenant-level restricted answer is allowed to refuse.
     *
     * <p>The default is {@link #STRICT} (audit round 2): a tenant is only flagged when one of its
     * credentials actually holds a RESTRICTED Google scope, so
     * the population is the one CASA is about, and for it an unconfigured destination list means
     * "nowhere is authorised": fetch is refused until the operator lists allowed hosts.
     * {@link #BOUND} (fetch unbounded while the list is empty) remains available as an explicit
     * operator choice.
     */
    public enum Mode {

        /**
         * No tenant-level detection. Only an execution explicitly tagged on its credentials
         * counts as restricted. Set this to keep the gate keyed strictly on per-execution
         * provenance.
         */
        OFF,

        /**
         * Opt-in relaxation. A restricted tenant cannot run {@code agent_browse} (a browser session
         * navigates on its own, so no destination check is possible), and its {@code fetch}
         * calls are bounded to {@code websearch.egress.restricted-allowed-hosts} whenever that
         * list is configured. With no list configured, {@code fetch} proceeds: the operator
         * has expressed no bound and the evidence is an inference.
         */
        BOUND,

        /**
         * Default. {@link #BOUND} plus the fail-closed reading of an empty allow-list: a restricted
         * tenant with no configured destination cannot fetch at all.
         */
        STRICT;

        /** Parse the property, tolerating null / blank / unknown by answering {@link #STRICT}. */
        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return STRICT;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                log.warn("Unknown {}='{}', falling back to strict", MODE_PROPERTY, raw);
                return STRICT;
            }
        }
    }

    /**
     * How long one tenant's answer is reused. A credential is connected or revoked by a human
     * action, so a minute of staleness is invisible, while the lookup is an internal HTTP call
     * that would otherwise run on every fetch of every turn.
     */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    /**
     * Cap on distinct tenants held at once. Reached only by a deployment with a very large
     * number of active tenants; the whole map is dropped rather than grown, which costs one
     * lookup per tenant and cannot leak memory.
     */
    private static final int MAX_ENTRIES = 10_000;

    private final CredentialClient credentialClient;
    private final Mode mode;
    private final long ttlMillis;
    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();

    private record CachedAnswer(boolean restricted, long expiresAtMillis) {
    }

    @Autowired
    public RestrictedScopeTenantDetector(
            @Nullable CredentialClient credentialClient,
            @Value("${" + MODE_PROPERTY + ":strict}") String modeProperty) {
        this(credentialClient, Mode.parse(modeProperty), DEFAULT_TTL);
    }

    /** Test seam: explicit mode and cache window. */
    RestrictedScopeTenantDetector(CredentialClient credentialClient,
                                  Mode mode,
                                  Duration ttl) {
        this.credentialClient = credentialClient;
        this.mode = mode != null ? mode : Mode.STRICT;
        this.ttlMillis = ttl != null ? ttl.toMillis() : DEFAULT_TTL.toMillis();
        log.info("Web-search restricted-scope egress detection: mode={}", this.mode);
    }

    /** The configured mode. {@link Mode#OFF} disables tenant-level detection entirely. */
    public Mode mode() {
        return mode;
    }

    /**
     * True when this tenant holds an active credential for a restricted-scope integration.
     *
     * <p>Always false in {@link Mode#OFF}, for a blank tenant, and when no
     * {@link CredentialClient} is wired (a unit-constructed provider), so a caller that has no
     * way to ask never invents a denial.
     */
    public boolean holdsRestrictedScopeCredential(String tenantId) {
        if (mode == Mode.OFF || tenantId == null || tenantId.isBlank() || credentialClient == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        CachedAnswer cached = cache.get(tenantId);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.restricted();
        }
        boolean restricted = lookup(tenantId);
        store(tenantId, restricted, now);
        return restricted;
    }

    private boolean lookup(String tenantId) {
        Set<String> configured;
        try {
            configured = credentialClient.getConfiguredIntegrations(tenantId);
        } catch (RuntimeException e) {
            // getConfiguredIntegrations already swallows transport failures; this catch only
            // covers an unexpected one, and answers the same way for the same reason.
            log.warn("Restricted-scope detection: could not read configured integrations for "
                    + "tenant {}, treating the execution as standard: {}", tenantId, e.getMessage());
            return false;
        }
        if (configured == null || configured.isEmpty()) {
            return false;
        }
        for (String integration : configured) {
            if (!isGoogleCandidate(integration)) {
                continue;
            }
            if (credentialHoldsRestrictedScope(tenantId, integration)) {
                log.debug("Tenant {} holds a restricted Google scope on '{}': web-search egress bounded",
                        tenantId, integration);
                return true;
            }
        }
        return false;
    }

    /**
     * Integrations whose credential CAN carry a Google scope: the ones the platform classification
     * lists, plus any Google-named one (a generic Google OAuth app can be granted Gmail scopes).
     * Only these are worth a scope lookup.
     */
    private boolean isGoogleCandidate(String integration) {
        if (integration == null) {
            return false;
        }
        String name = integration.toLowerCase(Locale.ROOT);
        return RestrictedDataPolicy.isRestrictedIntegration(integration)
                || name.contains("google") || name.contains("gmail");
    }

    /**
     * Decides on the SCOPES actually granted to the credential, not on the integration name
     * (audit round 2): a tenant that connected Gmail with {@code gmail.send} only, or Calendar,
     * holds no restricted-scope data and must not lose web fetching for it. The restricted set is
     * {@link RestrictedDataPolicy#RESTRICTED_GOOGLE_SCOPES}, the one definition shared across the
     * platform.
     *
     * <p>When the scopes cannot be read (lookup failure, credential not found under that slug) the
     * answer falls back to the name classification: an integration the platform classifies as
     * restricted counts as restricted, a merely Google-named one does not. A non-OAuth credential
     * (scopes {@code null}) carries no Google scope.
     */
    private boolean credentialHoldsRestrictedScope(String tenantId, String integration) {
        java.util.Optional<CredentialScopesDto> scopes;
        try {
            scopes = credentialClient.getCredentialScopes(tenantId, integration);
        } catch (RuntimeException e) {
            scopes = java.util.Optional.empty();
        }
        if (scopes.isEmpty()) {
            return RestrictedDataPolicy.isRestrictedIntegration(integration);
        }
        return RestrictedDataPolicy.grantedScopesIncludeRestricted(scopes.get().getScopes());
    }

    private void store(String tenantId, boolean restricted, long now) {
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(tenantId, new CachedAnswer(restricted, now + ttlMillis));
    }

    /** Drop every cached answer. Used by tests and by an operator-facing refresh. */
    public void invalidate() {
        cache.clear();
    }
}
