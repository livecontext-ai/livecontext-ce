package com.apimarketplace.catalog.service.credential;

import com.apimarketplace.common.scope.GrantedScopes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which of an account's connected credentials could actually run ONE endpoint, and
 * what to do when none of them can.
 *
 * <p>This is the answer a caller needs BEFORE it calls, and it is the answer nothing
 * gave it. A tool contract said an endpoint needs {@code gmail.readonly}; a credential
 * listing said the account holds a Gmail credential; neither said that the credential
 * was granted {@code gmail.labels} and {@code gmail.send} and can never run that
 * endpoint. The caller learned it from a refusal whose remedy, "add your Gmail key to
 * this account", is the one thing that does not fix it.
 *
 * <p>Pure on purpose: it takes the endpoint's requirement, the integration's policy
 * and the caller's accounts, and returns the block. Everything that has to fetch
 * something lives in {@link EndpointCredentialCapabilityService}, so the decision
 * table below is testable without a database, a tenant or an HTTP hop.
 *
 * <p><b>Two silences, and they are not the same.</b> A {@code null} account list means
 * the listing could not be read, and then nothing is said about accounts at all: an
 * unreachable credential service must never be reported as "you have none", which is a
 * false statement that sends a person to connect something they already have. An EMPTY
 * list means the listing WAS read and held nothing, which is the case worth naming.
 *
 * <p><b>The remedy is written for a reader that can only act through the tools.</b> It
 * names a tool call only where a tool call is the right next step, and otherwise says
 * plainly that the user has to act, because connecting or re-authorising an account is
 * not something an agent can do. It is ABSENT whenever the call is going to work: a
 * remedy offered on a healthy endpoint teaches a caller to ignore remedies.
 */
public final class EndpointCredentialCapability {

    /**
     * How many accounts are spelled out before the list starts counting instead. The
     * block is served on every contract read, so an account holding a hundred
     * credentials of one integration must not turn it into a wall.
     */
    static final int MAX_ACCOUNTS = 12;

    /** The one tool call that is genuinely the right next step: nothing is connected. */
    private static final String REQUIRE_CALL =
            "credential(action='require', services=['%s'], reason='<why you need it>')";

    private EndpointCredentialCapability() {
    }

    /**
     * One connected credential, as much of it as deciding needs.
     *
     * @param type the credential's auth mechanism as the credential service names it
     *             ({@code OAuth2}, {@code API Key}, ...). It decides whether the scope
     *             comparison applies to this account at all.
     */
    public record Account(String name, String integration, String status, String type,
                          boolean isDefault, List<String> grantedScopes) {

        boolean isActive() {
            return status != null && "active".equalsIgnoreCase(status);
        }

        /**
         * Whether an OAuth scope requirement is even a question for this credential.
         *
         * <p>The execution-time check exempts every non-OAuth credential outright
         * ({@code HttpExecutionService.preflightScopeCheck} returns as soon as the type
         * is not {@code oauth2}), and one integration can be connected through several
         * variants. Comparing scopes against a bearer or API-key credential, which has no
         * scope list at all, would report every required scope as missing and declare an
         * account unusable that the executor runs without complaint.
         */
        boolean scopesApply() {
            return type != null && "oauth2".equalsIgnoreCase(type.replace(" ", ""));
        }
    }

    /**
     * Adds the capability fields to an existing {@code {type, requiredScopes}} block.
     *
     * <p>Additive by contract: the two keys that were there before keep their exact
     * values, because two agent surfaces and their tests already read them. A caller
     * that knows nothing of the new keys sees what it always saw.
     *
     * @param base           the block built from the endpoint's own contract
     * @param integration    the integration's credential name, or null when unknown
     * @param requiredScopes the scopes the endpoint declares, possibly empty
     * @param policy         what a standard connection to this integration grants
     * @param accounts       the caller's credentials of this integration, or null when
     *                       the listing could not be read
     */
    public static Map<String, Object> enrich(Map<String, Object> base,
                                             String integration,
                                             List<String> requiredScopes,
                                             IntegrationScopePolicy policy,
                                             List<Account> accounts) {
        if (base == null) {
            return null;
        }
        if ("none".equals(String.valueOf(base.get("type")))) {
            // A keyless endpoint has no account to choose and no scope to be missing.
            // Returned BY IDENTITY, so a caller that reads "unchanged" as "nothing to add"
            // is not handed a block whose only content is the type it already had.
            return base;
        }
        Map<String, Object> block = new LinkedHashMap<>(base);

        // The REQUIRED side is never re-split: it comes from a catalog seed a validator
        // already checks, so its shape is the one that is known to be right. That is the
        // rule GrantedScopes states and the execution-time check follows.
        List<String> required = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
        List<String> needOwnClient = policy == null
                ? List.of()
                : policy.scopesNeedingOwnOAuthClient(required);
        boolean scopePolicyKnown = policy != null && policy.declared() && !required.isEmpty();
        if (scopePolicyKnown) {
            block.put("standardConnectionGrantsThis", needOwnClient.isEmpty());
            if (!needOwnClient.isEmpty()) {
                block.put("scopesNeedingOwnOAuthClient", needOwnClient);
            }
        }

        if (accounts == null) {
            // The listing could not be read. Everything below would describe accounts, so
            // there is nothing further to say and, above all, nothing to claim.
            return block;
        }

        Inventory inventory = take(accounts, required);
        block.put("accounts", inventory.rendered());
        if (accounts.size() > inventory.rendered().size()) {
            block.put("accountsNotShown", accounts.size() - inventory.rendered().size());
        }

        if (!inventory.runnable().isEmpty()) {
            block.put("runnableWith", inventory.runnable());
        }

        String remedy = remedy(integration, required, needOwnClient, scopePolicyKnown, inventory);
        if (remedy != null) {
            block.put("remedy", remedy);
        }
        return block;
    }

    /**
     * What the account list amounts to once every rule has been applied to it.
     *
     * <p>{@code anyCanRun} and a non-empty {@code runnable} are DIFFERENT facts, and
     * conflating them is the trap this record exists to keep apart: an account can be
     * perfectly able to run the endpoint and still be impossible to name, because a
     * second active account answers to the same name. Reading "nothing can run this" off
     * an empty {@code runnable} then produces a refusal that is false in every clause,
     * on a call that was going to succeed.
     */
    private record Inventory(List<Map<String, Object>> rendered,
                             List<String> runnable,
                             int total,
                             boolean anyCanRun,
                             boolean anyMissingScopes,
                             boolean chosenAccountCanRun) {
    }

    /**
     * Reads the accounts once and answers the three questions the block needs: what to
     * show, which names can actually be handed over, and whether the account this call
     * would pick on its own is one that works.
     */
    private static Inventory take(List<Account> accounts, List<String> required) {
        // A name held by two ACTIVE accounts of one integration resolves to NEITHER: the
        // run-time matcher refuses rather than picking at random. Offering such a name is
        // worse than offering none, so the uses are counted first and excluded below.
        Map<String, Integer> activeNameUses = new HashMap<>();
        for (Account account : accounts) {
            if (account.isActive() && account.name() != null && !account.name().isBlank()) {
                activeNameUses.merge(account.name().trim().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> rendered = new ArrayList<>();
        List<String> runnable = new ArrayList<>();
        List<String> runnableShown = new ArrayList<>();
        boolean anyCanRun = false;
        // WHY an account cannot run it, which is not the same question as whether the
        // endpoint declares scopes. A revoked credential that holds every required scope
        // is unusable for its STATUS, and telling its owner it "was not granted" them
        // sends them to obtain something they already have.
        boolean anyMissingScopes = false;
        Account defaultAccount = null;
        Account firstActive = null;
        Map<String, Object> promotable = null;
        for (Account account : accounts) {
            List<String> missing = missingFor(account, required);
            // A revoked or errored account cannot be named either: the run-time matcher
            // resolves from ACTIVE rows only, so naming one FAILS the call rather than
            // falling back to the default. It stays in the list, with its status, because
            // "reconnect this one" is something the caller can relay.
            boolean canRun = account.isActive() && missing.isEmpty();
            boolean nameIsUnique = account.name() != null && !account.name().isBlank()
                    && activeNameUses.getOrDefault(
                            account.name().trim().toLowerCase(Locale.ROOT), 0) == 1;

            boolean shown = rendered.size() < MAX_ACCOUNTS;
            if (shown || (canRun && nameIsUnique && promotable == null)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", account.name());
                entry.put("status", account.status() == null
                        ? "unknown" : account.status().toLowerCase(Locale.ROOT));
                entry.put("isDefault", account.isDefault());
                entry.put("canRunThis", canRun);
                if (!missing.isEmpty()) {
                    entry.put("missingScopes", missing);
                }
                if (canRun && !nameIsUnique) {
                    // It can run the call, and yet naming it cannot select it. Said here
                    // rather than left to be inferred from two identical-looking rows -
                    // and the two causes are told apart, because a field that exists so a
                    // reason is not guessed must not state the wrong one.
                    boolean sharesItsName = account.name() != null && !account.name().isBlank();
                    entry.put("notSelectableByName", sharesItsName
                            ? "another active account of this integration carries the same name"
                            : "this account has no name to select it by");
                }
                if (shown) {
                    rendered.add(entry);
                } else {
                    // Held rather than added: it is only worth the extra row if the window
                    // turns out to hold no runnable account at all.
                    promotable = entry;
                }
            }
            if (canRun) {
                anyCanRun = true;
            }
            if (!missing.isEmpty()) {
                anyMissingScopes = true;
            }
            if (canRun && nameIsUnique) {
                runnable.add(account.name());
                if (shown) {
                    // Only a name the reader can actually SEE is worth being told to copy:
                    // the remedy says "copied exactly from the accounts list", and that
                    // list stops at MAX_ACCOUNTS.
                    runnableShown.add(account.name());
                }
            }
            if (account.isDefault() && defaultAccount == null) {
                defaultAccount = account;
            }
            if (account.isActive() && firstActive == null) {
                firstActive = account;
            }
        }

        // WHICH account an unnamed call lands on: the integration's default when there is
        // one, and otherwise the first ACTIVE credential, which is what the credential
        // service falls back to. Getting this wrong produces the worst kind of remedy, one
        // that fires on a call that was always going to work.
        // The default only counts while it is ACTIVE. The credential service filters on
        // status in every branch and never reads is_default on its own, so a revoked
        // default is not the account the call lands on - and treating it as one fires the
        // "your default cannot run this" remedy on a call already routed elsewhere.
        Account chosen = defaultAccount != null && defaultAccount.isActive()
                ? defaultAccount : firstActive;
        boolean chosenCanRun = chosen != null && missingFor(chosen, required).isEmpty();

        // Only a name that is IN the list can be offered, because the remedy tells the
        // reader to copy it from there. When the window holds no runnable account but a
        // later one is runnable, that one is PROMOTED into the list rather than the
        // instruction being left pointing at nothing - the window exists to bound the
        // output, not to hide the one row that answers the question.
        if (runnableShown.isEmpty() && promotable != null) {
            rendered.add(promotable);
            runnableShown.add(String.valueOf(promotable.get("name")));
        }
        return new Inventory(List.copyOf(rendered),
                runnableShown.stream().limit(MAX_ACCOUNTS).toList(),
                accounts.size(), anyCanRun, anyMissingScopes, chosenCanRun);
    }

    /** The required scopes an account lacks, empty whenever scopes do not apply to it. */
    private static List<String> missingFor(Account account, List<String> required) {
        if (!account.scopesApply()) {
            return List.of();
        }
        return List.copyOf(GrantedScopes.missingFrom(required, account.grantedScopes()));
    }

    /**
     * The one sentence to act on, or null when the call is going to work.
     *
     * <p>Four outcomes, and the branch that matters most is the last one: an account
     * that CAN run the endpoint exists and is not the one the call would pick. That is
     * a working call the caller would otherwise never have made.
     */
    private static String remedy(String integration,
                                 List<String> required,
                                 List<String> needOwnClient,
                                 boolean scopePolicyKnown,
                                 Inventory inventory) {
        String display = IntegrationNames.displayName(integration);
        String slug = integration == null ? "" : integration.trim();
        boolean ownClientNeeded = scopePolicyKnown && !needOwnClient.isEmpty();

        if (inventory.total() == 0) {
            if (ownClientNeeded) {
                return "No " + display + " account is connected, and this endpoint needs "
                        + list(needOwnClient) + ", which a standard connection never grants. "
                        + "Ask the user to connect " + display + " with their own OAuth client "
                        + "credentials and to grant those scopes; connecting it the ordinary "
                        + "way will not be enough.";
            }
            return "No " + display + " account is connected. Call "
                    + String.format(REQUIRE_CALL, slug)
                    + " to ask the user to connect one, then run this again.";
        }

        if (!inventory.anyCanRun()) {
            // Keyed on whether a scope is actually MISSING, not on whether the endpoint
            // declares any. A revoked account holding every required scope fails for its
            // status, and the scope sentence would tell its owner to go and obtain a
            // permission the account already carries.
            if (!inventory.anyMissingScopes()) {
                // Nothing is missing, so every account here is unusable for its STATUS.
                return (inventory.total() == 1
                        ? "The connected " + display + " account holds what this endpoint needs "
                                + "but cannot be used right now"
                        : "None of the " + inventory.total() + " connected " + display
                                + " accounts can be used right now")
                        + " (each status is in the accounts list). Only the user can "
                        + "re-authorise it; you cannot, and re-sending this call changes nothing.";
            }
            String held = inventory.total() == 1
                    ? "The connected " + display + " account was not granted "
                    : "None of the " + inventory.total() + " connected " + display
                            + " accounts was granted ";
            if (ownClientNeeded) {
                return held + list(needOwnClient) + ", and re-connecting the ordinary way can "
                        + "never grant them. Ask the user to connect " + display + " with their "
                        + "own OAuth client credentials and to grant those scopes.";
            }
            return held + list(required) + ". Only the user can re-authorise the account and "
                    + "grant them; you cannot, and re-sending this call changes nothing.";
        }

        if (inventory.chosenAccountCanRun()) {
            // The call works as sent. Nothing to say, which is what makes a remedy worth
            // reading when there IS one.
            return null;
        }
        if (inventory.runnable().isEmpty()) {
            // Something can run it and nothing can be NAMED: every account that works
            // shares its name with another active one, and naming a shared name resolves
            // to neither. The endpoint is reachable, just not by an instruction this
            // caller can give.
            return "An account that can run this one is connected, but the account this call "
                    + "would pick on its own is not it, and the ones that work cannot be named "
                    + "because another active " + display + " account carries the same name. "
                    + "Only the user can rename one of them or make the right one the default.";
        }
        return "Not every connected " + display + " account can run this one, and the account "
                + "this call would pick on its own is not one that can. Run it with "
                + "credential_name=\"" + inventory.runnable().get(0) + "\", copied exactly "
                + "from the accounts list.";
    }

    /** A scope list as prose, bounded so one endpoint cannot fill a turn. */
    private static String list(List<String> scopes) {
        if (scopes.isEmpty()) {
            return "the scopes it declares";
        }
        int shown = Math.min(scopes.size(), 4);
        String named = String.join(", ", scopes.subList(0, shown));
        int hidden = scopes.size() - shown;
        return hidden == 0 ? named : named + " and " + hidden + " more";
    }
}
