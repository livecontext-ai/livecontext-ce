package com.apimarketplace.agent.service;

import com.apimarketplace.agent.bridge.BridgeAccessDecision;
import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides whether an agent may be SAVED on a given LLM provider.
 *
 * <p>{@link BridgeAccessGuard} was enforced at dispatch and applied to the model LISTING, but
 * nothing checked it when an agent was persisted. So an agent could be stored on a CLI bridge
 * its owner can never dispatch, and it then failed at every single run - a scheduled agent in
 * that state fails on every fire, forever, with only a log line to show for it. Production had
 * 11 such agents across 8 non-admin owners, one of them on a 30-minute cron.
 *
 * <p>This lives in ONE place because an agent can be written from several: the REST controller,
 * the {@code agent} MCP tool (how an LLM creates agents), and clone. Gating only the one that
 * happened to be looked at first would leave the others reproducing the same state.
 *
 * <h2>Two deliberate narrowings</h2>
 * <ul>
 *   <li><b>Only a STANDING denial blocks a save.</b> A daily quota resets, and a guard that
 *       could not be reached is not evidence of anything, so neither may stop someone editing
 *       their agent. Dispatch still fails closed on both.</li>
 *   <li><b>Only a provider that is CHANGING is judged.</b> An agent already sitting on a
 *       forbidden bridge has to stay editable, or its owner cannot move it off - which is the
 *       repair path for the agents already stuck there.</li>
 * </ul>
 *
 * <h2>The write paths this does NOT cover</h2>
 * <p>Three, listed so the list is exhaustive rather than illustrative. The two internal
 * endpoints share one mechanical reason: they never receive an {@code X-User-Roles} header, so
 * judging them would read every caller as a plain {@code USER} and refuse an admin.
 * <ul>
 *   <li>{@code POST /api/internal/agents/clone-from-snapshot}, which publication-service calls
 *       to install an agent from the marketplace. It also wants different semantics: acquiring
 *       a published agent built on a bridge should SUBSTITUTE the catalog default rather than
 *       refuse the install, so the acquirer gets something that runs. <b>Until that exists,
 *       installing a marketplace agent pinned to a bridge is the only way left, in normal
 *       operation, to STORE a bridge in the row and reach the stuck state this guard was
 *       written to prevent.</b></li>
 *   <li>A REST create that sends no {@code modelProvider} at all. It is not judged because
 *       {@code AgentService.createAgent} stores the provider verbatim, so nothing lands in the
 *       row - unlike the {@code agent} tool, which substitutes the catalog default and stores
 *       THAT, and therefore judges it. On an install where an admin ranked a bridge first, such
 *       an agent resolves to a bridge at dispatch time.</li>
 *   <li>{@code InternalAgentController}'s create/update, reached through
 *       {@code AgentClient.createAgent}/{@code updateAgent}. No in-repo caller uses them today,
 *       so nothing currently arrives that way, but they are not gated.</li>
 * </ul>
 *
 * <h2>What this is, and is not</h2>
 * <p>A consistency guard, not a security boundary. {@code /api/agents} is in agent-service's
 * own {@code public-paths}, so anything with network reach to the pod can forge
 * {@code X-User-Roles: ADMIN} - exactly as it can against the dispatch guard. This stops an
 * honest caller from storing an agent that cannot run; the dispatch guard is what actually
 * enforces access.
 *
 * <p>A run whose roles were never threaded through (some webhook and schedule paths leave
 * {@code __userRoles__} unset) is judged as {@code USER}, so an admin can be refused there.
 * On a self-hosted install the access policy then resolves the persisted role itself, and the
 * admin is admitted. In cloud the refusal stands: the cloud rule is decided on the forwarded
 * roles alone and BEFORE the policy, so a role-less run never reaches the lookup that would
 * recognise the admin. That is deliberate and fail-closed (an admin's scheduled agent cannot
 * store an agent on a CLI in cloud; nobody gains a bridge), and it makes this guard STRICTER
 * than dispatch there, never looser: it can never allow a save that dispatch would then deny.
 */
@Component
public class BridgeProviderSaveGuard {

    private static final Logger log = LoggerFactory.getLogger(BridgeProviderSaveGuard.class);

    /**
     * Standing reason for the cloud refusal of a non-admin. A distinct token rather than a reuse
     * of {@code admin_only_requires_admin_role}: that one tells the caller to get an admin to do
     * it, which is false here - in cloud no policy an admin could widen lets a user pick a bridge
     * by name, so the caller's next step is to choose the billed model instead.
     */
    public static final String REASON_CLOUD = "bridge_not_selectable_in_cloud";

    /** One line per process, not one per save: a misconfiguration is a state, not an event. */
    private final AtomicBoolean warnedMissingGuard = new AtomicBoolean(false);

    /**
     * {@code auth.mode}: {@code "embedded"} ⇒ CE (self-hosted). Same spelling and same default as
     * {@code ModelCatalogService}, so the provider a cloud catalog stops LISTING is exactly the
     * provider a cloud save stops ACCEPTING. Blank (cloud / tests) ⇒ not CE.
     */
    @org.springframework.beans.factory.annotation.Value("${auth.mode:}")
    private String authMode = "";

    /** Visible for tests, like {@link #setBridgeAccessGuard} - the callers live in other packages. */
    public void setAuthMode(String authMode) {
        this.authMode = authMode == null ? "" : authMode;
    }


    @Autowired(required = false)
    private com.apimarketplace.common.web.AppEditionProvider appEditionProvider;

    /** Visible for tests. */
    public void setAppEditionProvider(com.apimarketplace.common.web.AppEditionProvider provider) {
        this.appEditionProvider = provider;
    }

    /** Exposed so the agent-facing catalogue trims exactly what this guard would refuse. */
    public boolean isSelfHosted() {
        return isSelfHostedInstall();
    }

    /**
     * Whether this install runs its own CLI. Resolved from {@code AppEditionProvider}, which
     * knows the difference between CE_FREE, SELF_HOSTED_ENTERPRISE (self-hosted, but running
     * keycloak) and the hosted product - a difference {@code auth.mode} alone cannot express, and
     * getting it wrong would ban an enterprise operator from the CLI they installed themselves.
     *
     * <p>The bean comes from common-lib auto-configuration and is present in every service. It is
     * optional only so test slices need not raise it; absent, the check falls back to
     * {@code auth.mode=embedded}, which is right for the CE monolith and is the value every
     * pre-existing test already sets.
     */
    private boolean isSelfHostedInstall() {
        if (appEditionProvider != null) {
            return appEditionProvider.isSelfHosted();
        }
        return "embedded".equalsIgnoreCase(authMode == null ? "" : authMode.trim());
    }

    private boolean isCloud() {
        return !isSelfHostedInstall();
    }

    /**
     * Optional: the bean is always registered in agent-service and in the CE monolith, but
     * optional injection keeps test slices and any bridge-less deployment green.
     */
    @Autowired(required = false)
    private BridgeAccessGuard bridgeAccessGuard;

    /** Visible for tests (mirrors AgentHelpModule / BridgeLoopDispatcher). */
    public void setBridgeAccessGuard(BridgeAccessGuard guard) {
        this.bridgeAccessGuard = guard;
    }

    /**
     * @param userId            the CALLER attempting the save, which is whose access is judged.
     *                          For an org-scoped agent edited by a teammate this is the
     *                          teammate, not the agent's owner - deliberately, since it is the
     *                          caller who would go on to run it
     * @param userRoles         comma-separated roles, as the {@code X-User-Roles} header carries
     *                          them; {@code null} is treated as {@code USER} downstream
     * @param requestedProvider the provider the save wants to store; {@code null}/blank means the
     *                          caller is not setting one, so there is nothing to judge
     * @param currentProvider   what the agent is stored on today; {@code null} on create
     * @return the standing denial reason when the save must be refused, otherwise empty
     */
    public Optional<String> denialReason(String userId, String userRoles,
                                         String requestedProvider, String currentProvider) {
        if (requestedProvider == null
                || requestedProvider.isBlank()
                || !BridgeAccessGuard.isBridgeProvider(requestedProvider)
                || requestedProvider.equalsIgnoreCase(currentProvider)) {
            return Optional.empty();
        }
        // CLOUD: a bridge is an administrator's choice only. All four CLIs run on ONE operator
        // subscription, and the platform already routes a billed pair onto a CLI through
        // agent.model_execution_links, so for a user choosing the bridge by name buys nothing an
        // anthropic/openai pick does not already get. A non-admin is refused here, BEFORE the
        // access policy and whatever it says: the policy can be widened to all_users by an admin,
        // and the hosted product must not reopen on that. An admin falls through to the policy,
        // exactly as on a self-hosted install, so an admin-only policy still admits them and a
        // disabled one still refuses them. CE is untouched: there the CLI is the self-hoster's own.
        if (isCloud() && bridgeAccessGuard != null
                && !com.apimarketplace.common.web.AdminRoleGuard.isAdmin(userRoles)) {
            // The access-guard bean is also the signal that this instance is Spring-managed. A
            // hand-constructed fallback (AgentController and AgentCrudModule each field-initialise
            // one) has no injected edition either, so it would read as hosted and refuse EVERY
            // bridge save - including on a self-hosted install, where they are the point. Before
            // this rule the unwired fallback was permissive by design; it stays permissive.
            log.info("Refusing to save an agent on bridge provider={} for user={}: {}",
                requestedProvider, userId, REASON_CLOUD);
            return Optional.of(REASON_CLOUD);
        }
        if (bridgeAccessGuard == null) {
            // Reached only for a real bridge save, so this is the one moment the absence
            // matters. Without this line the guard degrades to a no-op indistinguishable from
            // "nobody tried" - and every other failure mode of this class is already silent.
            if (warnedMissingGuard.compareAndSet(false, true)) {
                log.warn("No BridgeAccessGuard wired: agent saves onto CLI bridge providers are "
                    + "NOT being checked. Dispatch still refuses them, so such an agent would be "
                    + "created and then fail at every run.");
            }
            return Optional.empty();
        }

        BridgeAccessDecision decision;
        try {
            decision = bridgeAccessGuard.check(userId, userRoles, requestedProvider);
        } catch (Exception e) {
            // Defensive only: HttpBridgeAccessClient already converts every failure into a
            // guard_unavailable DENIAL rather than throwing. Saving an agent must not depend
            // on the access service being reachable either way.
            log.debug("Bridge access check threw for provider={}: {} - allowing the save",
                requestedProvider, e.getMessage());
            return Optional.empty();
        }
        if (decision == null || decision.allowed() || !isStanding(decision.reason())) {
            return Optional.empty();
        }
        log.info("Refusing to save an agent on bridge provider={} for user={}: {}",
            requestedProvider, userId, decision.reason());
        return Optional.of(decision.reason());
    }

    /**
     * True when a denial reflects a standing policy rather than a passing condition.
     *
     * <p>{@code guard_unavailable} and {@code daily_quota_exhausted} are deliberately absent:
     * the first means the access service could not answer, the second resets on its own. This
     * omission is the ONLY thing that keeps an auth-service outage from blocking every bridge
     * agent save, so it is asserted by a test rather than left to reading.
     */
    private static boolean isStanding(String reason) {
        return BridgeAccessDecision.REASON_NOT_ADMIN.equals(reason)
            || BridgeAccessDecision.REASON_NOT_ALLOWLISTED.equals(reason)
            || BridgeAccessDecision.REASON_DISABLED.equals(reason)
            // A bridge with no policy row is refused at dispatch too, so accepting the save
            // would only store an agent that cannot run. Adding a bridge to the provider map
            // without seeding its policy therefore blocks saving as well - deliberate parity.
            || BridgeAccessDecision.REASON_UNKNOWN_BRIDGE.equals(reason);
    }

    /** The message shown to whoever attempted the save. Shared so every path words it the same. */
    public static String deniedMessage(String provider) {
        return "This account cannot run agents on '" + provider + "', so an agent saved on it "
            + "would fail every time it runs. Pick a model from another provider, or ask an "
            + "administrator for access to this one.";
    }

    /**
     * The same message, branched on WHY the save was refused.
     *
     * <p>The two reasons need different advice and giving the wrong one sends the reader somewhere
     * that cannot help. "Ask an administrator for access" is right when a policy could be widened
     * for this caller; it is wrong under {@link #REASON_CLOUD}, where no policy an administrator
     * could widen lets a user select a bridge. There the useful thing to say is that the platform
     * already runs these models for you, because it does: an execution link routes the billed
     * pair onto the same CLI.
     */
    public static String deniedMessage(String provider, String reason) {
        if (REASON_CLOUD.equals(reason)) {
            return "'" + provider + "' is not a selectable provider on this platform - it is how "
                + "some models are executed, not a model you pick. Choose the model you want "
                + "(for example an Anthropic or OpenAI one) and the platform routes it for you.";
        }
        return deniedMessage(provider);
    }
}
