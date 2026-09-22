package com.apimarketplace.catalog.service.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The block an agent reads before it calls, and the one sentence it acts on.
 *
 * <p>Every case here was reachable in production and answered wrongly or not at all.
 * The one that cost the most is {@link RunnableElsewhere}: an account that CAN run the
 * endpoint existed, and nothing said so.
 */
@DisplayName("EndpointCredentialCapability")
class EndpointCredentialCapabilityTest {

    private static final String READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String SEND = "https://www.googleapis.com/auth/gmail.send";
    private static final String LABELS = "https://www.googleapis.com/auth/gmail.labels";

    /** Gmail as the managed cloud actually declares it: read is restricted, send is not. */
    private static IntegrationScopePolicy gmailPolicy() {
        return IntegrationScopePolicy.declared(List.of(LABELS, SEND), List.of(READONLY), false);
    }

    private static Map<String, Object> oauthBase() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("type", "oauth2");
        base.put("requiredScopes", List.of(READONLY));
        return base;
    }

    private static EndpointCredentialCapability.Account account(
            String name, String status, boolean isDefault, String... scopes) {
        return new EndpointCredentialCapability.Account(
                name, "gmail", status, "OAuth2", isDefault, List.of(scopes));
    }

    /** The same account connected through a non-OAuth variant, which has no scopes. */
    private static EndpointCredentialCapability.Account apiKeyAccount(
            String name, String status, boolean isDefault) {
        return new EndpointCredentialCapability.Account(
                name, "gmail", status, "API Key", isDefault, List.of());
    }

    private static Map<String, Object> enrich(List<EndpointCredentialCapability.Account> accounts) {
        return EndpointCredentialCapability.enrich(
                oauthBase(), "gmail", List.of(READONLY), gmailPolicy(), accounts);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> accountsOf(Map<String, Object> block) {
        return (List<Map<String, Object>>) block.get("accounts");
    }

    private static String remedy(Map<String, Object> block) {
        return (String) block.get("remedy");
    }

    @Nested
    @DisplayName("the requirement half is never altered")
    class Additive {

        @Test
        @DisplayName("type and requiredScopes come back exactly as the contract stated them")
        void keepsTheOriginalKeys() {
            Map<String, Object> block = enrich(List.of());
            assertThat(block.get("type")).isEqualTo("oauth2");
            assertThat(block.get("requiredScopes")).isEqualTo(List.of(READONLY));
        }

        @Test
        @DisplayName("a keyless endpoint gains nothing, because an empty account list would read as a warning")
        void keylessEndpointIsLeftAlone() {
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("type", "none");
            Map<String, Object> block = EndpointCredentialCapability.enrich(
                    base, "jsonplaceholder", List.of(), IntegrationScopePolicy.unknown(), List.of());
            // Returned by identity, so a caller that treats an unchanged block as "nothing
            // was added" does not receive one carrying only the type it already had.
            assertThat(block).isSameAs(base);
        }
    }

    @Nested
    @DisplayName("no account of the integration is connected")
    class NothingConnected {

        @Test
        @DisplayName("a restricted endpoint says to bring an own OAuth client, NOT to add a key")
        void restrictedEndpointAsksForAnOwnClient() {
            // The exact production refusal this whole change exists for: the old sentence
            // said "add your Gmail key to this account", which is the one step that does
            // not make this endpoint work.
            String remedy = remedy(enrich(List.of()));
            assertThat(remedy).contains("own OAuth client credentials").contains(READONLY);
            assertThat(remedy).doesNotContain("credential(action='require'");
        }

        @Test
        @DisplayName("an unrestricted endpoint asks for an ordinary connect instead")
        void unrestrictedEndpointAsksForAConnect() {
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("type", "oauth2");
            base.put("requiredScopes", List.of(SEND));
            String remedy = remedy(EndpointCredentialCapability.enrich(
                    base, "gmail", List.of(SEND), gmailPolicy(), List.of()));
            assertThat(remedy)
                    .contains("credential(action='require', services=['gmail']")
                    .contains("reason=")
                    .doesNotContain("own OAuth client");
        }
    }

    @Nested
    @DisplayName("accounts exist but none can run the endpoint")
    class NoneRunnable {

        @Test
        @DisplayName("a restricted scope is not answered with a reconnect, which could never grant it")
        void restrictedScopeIsNotAnsweredWithAReconnect() {
            String remedy = remedy(enrich(List.of(account("Perso", "active", true, SEND))));
            assertThat(remedy).contains("own OAuth client credentials");
            assertThat(remedy).doesNotContain("re-authorise");
        }

        @Test
        @DisplayName("an ordinary missing scope says the USER must re-authorise, and prescribes no tool call")
        void ordinaryMissingScopeIsTheUsersToFix() {
            // Deliberately NOT a require(force=true) instruction. The credential tool's own
            // decision table puts a missing scope in the "do not call require again" row,
            // and its anti-loop guard blocks a second forced attempt, so prescribing that
            // call here would send an agent into something the server refuses. What is true
            // on every path is that only the user can widen a grant.
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("type", "oauth2");
            base.put("requiredScopes", List.of(SEND));
            String remedy = remedy(EndpointCredentialCapability.enrich(
                    base, "gmail", List.of(SEND), gmailPolicy(),
                    List.of(account("Perso", "active", true, LABELS))));
            assertThat(remedy)
                    .contains("Only the user can re-authorise")
                    .contains(SEND)
                    .doesNotContain("force=true")
                    .doesNotContain("own OAuth client");
        }

        @Test
        @DisplayName("an account holding every scope but revoked cannot run it, because naming it FAILS the call")
        void revokedAccountCannotRunIt() {
            Map<String, Object> block = enrich(List.of(account("Perso", "needs_reauth", true, READONLY)));
            assertThat(accountsOf(block).get(0).get("canRunThis")).isEqualTo(false);
            assertThat(accountsOf(block).get(0)).doesNotContainKey("missingScopes");
            assertThat(block).doesNotContainKey("runnableWith");
        }

        @Test
        @DisplayName("and it is told its TOKEN is the problem, never that it lacks a scope it plainly holds")
        void aRevokedAccountIsNotAccusedOfMissingAScopeItHas() {
            // The most ordinary failure this feature will ever describe: an expired OAuth
            // token on a scope-gated endpoint. Deciding the sentence on "does the endpoint
            // declare scopes" rather than "is a scope actually missing" answers it with
            // "your account was not granted gmail.readonly, go and register your own OAuth
            // application" - about an account whose own row, two lines above, correctly
            // reports nothing missing.
            String remedy = remedy(enrich(List.of(account("Perso", "needs_reauth", true, READONLY))));
            assertThat(remedy)
                    .contains("cannot be used right now")
                    .contains("re-authorise")
                    .doesNotContain("was not granted")
                    .doesNotContain("own OAuth client");
        }

        @Test
        @DisplayName("a genuinely missing scope is still named as a missing scope")
        void theScopeSentenceStillFiresWhenAScopeIsMissing() {
            // The pair: without this, the fix above would pass on an implementation that
            // never mentions scopes at all.
            assertThat(remedy(enrich(List.of(account("Perso", "active", true, SEND)))))
                    .contains("own OAuth client credentials")
                    .contains(READONLY);
        }

        @Test
        @DisplayName("with nothing missing, the refusal is about STATUS and says to reconnect")
        void statusOnlyRefusalDoesNotInventAMissingScope() {
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("type", "api_key");
            String remedy = remedy(EndpointCredentialCapability.enrich(
                    base, "stripe", List.of(), IntegrationScopePolicy.unknown(),
                    List.of(new EndpointCredentialCapability.Account(
                            "Main", "stripe", "error", "API Key", true, List.of()))));
            assertThat(remedy)
                    .contains("cannot be used right now")
                    .contains("Only the user can re-authorise")
                    .doesNotContain("force=true");
        }
    }

    @Nested
    @DisplayName("an account that CAN run it exists")
    class RunnableElsewhere {

        @Test
        @DisplayName("when it is not the default, the remedy names it for credential_name")
        void namesTheAccountThatWorks() {
            Map<String, Object> block = enrich(List.of(
                    account("Perso", "active", true, SEND),
                    account("Boulot", "active", false, READONLY, SEND)));
            assertThat(block.get("runnableWith")).isEqualTo(List.of("Boulot"));
            assertThat(remedy(block))
                    .contains("credential_name=\"Boulot\"")
                    .contains("copied exactly");
        }

        @Test
        @DisplayName("when the default can run it, there is NO remedy at all")
        void silenceWhenTheCallWillWork() {
            Map<String, Object> block = enrich(List.of(
                    account("Perso", "active", true, READONLY),
                    account("Boulot", "active", false, SEND)));
            assertThat(block).doesNotContainKey("remedy");
            assertThat(block.get("runnableWith")).isEqualTo(List.of("Perso"));
        }

        @Test
        @DisplayName("with no default at all, the FIRST ACTIVE account is the one the call would pick, so a working call gets no remedy")
        void noDefaultMeansTheFirstActiveAccount() {
            // The credential service falls through to the first active credential of the
            // integration when nothing is marked default, and that population is real
            // (an account holding exactly one credential nobody ever marked). Treating
            // "no default" as "the call will pick the wrong one" fires a remedy on a call
            // that was always going to work, which is the one thing a remedy must not do.
            Map<String, Object> block = enrich(List.of(
                    account("Boulot", "active", false, READONLY)));
            assertThat(block.get("runnableWith")).isEqualTo(List.of("Boulot"));
            assertThat(block).doesNotContainKey("remedy");
        }

        @Test
        @DisplayName("with no default, a first active account that CANNOT run it still names the one that can")
        void noDefaultAndTheFirstActiveCannotRunIt() {
            Map<String, Object> block = enrich(List.of(
                    account("Perso", "active", false, SEND),
                    account("Boulot", "active", false, READONLY)));
            assertThat(remedy(block)).contains("credential_name=\"Boulot\"");
        }

        @Test
        @DisplayName("a revoked DEFAULT is skipped too, because the credential service resolves on status and not on the flag")
        void aRevokedDefaultIsNotTheOneTheCallWouldPick() {
            // findCredential filters on ACTIVE in every branch and never reads is_default on
            // its own, so a revoked default is not where an unnamed call lands. Treating it
            // as one fires "your default cannot run this" on a call already routed elsewhere.
            Map<String, Object> block = enrich(List.of(
                    account("Dead", "needs_reauth", true, SEND),
                    account("Boulot", "active", false, READONLY)));
            assertThat(block).doesNotContainKey("remedy");
        }

        @Test
        @DisplayName("a revoked account is skipped when working out which one the call would pick")
        void aRevokedAccountIsNotTheOneTheCallWouldPick() {
            Map<String, Object> block = enrich(List.of(
                    account("Dead", "needs_reauth", false, READONLY),
                    account("Boulot", "active", false, READONLY)));
            assertThat(block).doesNotContainKey("remedy");
        }
    }

    @Nested
    @DisplayName("what the accounts list carries")
    class AccountsList {

        @Test
        @DisplayName("each entry says whether it can run this one and what it is missing")
        void perAccountVerdict() {
            Map<String, Object> block = enrich(List.of(
                    account("Perso", "active", true, SEND),
                    account("Boulot", "active", false, READONLY)));
            Map<String, Object> perso = accountsOf(block).get(0);
            assertThat(perso.get("name")).isEqualTo("Perso");
            assertThat(perso.get("isDefault")).isEqualTo(true);
            assertThat(perso.get("canRunThis")).isEqualTo(false);
            assertThat(perso.get("missingScopes")).isEqualTo(List.of(READONLY));

            Map<String, Object> boulot = accountsOf(block).get(1);
            assertThat(boulot.get("canRunThis")).isEqualTo(true);
            assertThat(boulot).doesNotContainKey("missingScopes");
        }

        @Test
        @DisplayName("status is lower-cased, and an absent one reads as unknown rather than as active")
        void statusShaping() {
            Map<String, Object> block = enrich(List.of(
                    account("Loud", "ACTIVE", false, READONLY),
                    new EndpointCredentialCapability.Account(
                            "Silent", "gmail", null, "OAuth2", false, List.of(READONLY))));
            assertThat(accountsOf(block).get(0).get("status")).isEqualTo("active");
            assertThat(accountsOf(block).get(0).get("canRunThis")).isEqualTo(true);
            assertThat(accountsOf(block).get(1).get("status")).isEqualTo("unknown");
            assertThat(accountsOf(block).get(1).get("canRunThis")).isEqualTo(false);
        }

        @Test
        @DisplayName("an account that can run it is shown even when the list was cut before reaching it")
        void aRunnableAccountOutsideTheWindowIsPromoted() {
            // The remedy says "copied exactly from the accounts list". Offering a name that
            // the cut removed makes that instruction point at nothing, and dropping the name
            // instead would hide the only account that answers the question. So it is shown.
            List<EndpointCredentialCapability.Account> many = new ArrayList<>();
            for (int i = 0; i < EndpointCredentialCapability.MAX_ACCOUNTS; i++) {
                many.add(account("cannot-" + i, "active", i == 0, SEND));
            }
            many.add(account("works", "active", false, READONLY));

            Map<String, Object> block = enrich(many);
            List<String> visible = accountsOf(block).stream()
                    .map(a -> (String) a.get("name")).toList();
            assertThat(block.get("runnableWith")).isEqualTo(List.of("works"));
            assertThat(visible).contains("works");
            assertThat(remedy(block)).contains("credential_name=\"works\"");
        }

        @Test
        @DisplayName("a long list is cut and SAYS it was cut, so the missing ones are not read as absent")
        void listIsBounded() {
            List<EndpointCredentialCapability.Account> many = new ArrayList<>();
            for (int i = 0; i < EndpointCredentialCapability.MAX_ACCOUNTS + 3; i++) {
                many.add(account("acct-" + i, "active", i == 0, READONLY));
            }
            Map<String, Object> block = enrich(many);
            // The literal, not the constant: asserting MAX_ACCOUNTS against itself passes
            // for any value it could ever be given, including one.
            assertThat(accountsOf(block)).hasSize(12);
            assertThat(block.get("accountsNotShown")).isEqualTo(3);
            // Whatever is offered has to be IN the list the remedy says to copy from.
            @SuppressWarnings("unchecked")
            List<String> offered = (List<String>) block.get("runnableWith");
            List<String> visible = accountsOf(block).stream()
                    .map(a -> (String) a.get("name")).toList();
            assertThat(visible).containsAll(offered);
        }
    }

    @Nested
    @DisplayName("when the integration declares no scope policy")
    class UnknownPolicy {

        @Test
        @DisplayName("nothing is claimed about a standard connection, and the account verdicts still stand")
        void noClaimWithoutAPolicy() {
            Map<String, Object> block = EndpointCredentialCapability.enrich(
                    oauthBase(), "acme", List.of(READONLY), IntegrationScopePolicy.unknown(),
                    List.of(account("Main", "active", true, READONLY)));
            assertThat(block).doesNotContainKey("standardConnectionGrantsThis");
            assertThat(block).doesNotContainKey("scopesNeedingOwnOAuthClient");
            assertThat(accountsOf(block).get(0).get("canRunThis")).isEqualTo(true);
            assertThat(block).doesNotContainKey("remedy");
        }

        @Test
        @DisplayName("a missing scope with no policy asks for a reconnect, never for an OAuth client the user may not need")
        void missingScopeWithoutAPolicyStaysNeutral() {
            Map<String, Object> block = EndpointCredentialCapability.enrich(
                    oauthBase(), "acme", List.of(READONLY), IntegrationScopePolicy.unknown(),
                    List.of(account("Main", "active", true, SEND)));
            assertThat(remedy(block))
                    .contains("Only the user can re-authorise")
                    .doesNotContain("own OAuth client");
        }
    }

    @Nested
    @DisplayName("the grantable flag")
    class StandardConnectionFlag {

        @Test
        @DisplayName("false with the scopes that need an own client named, true when a plain connect suffices")
        void bothDirections() {
            Map<String, Object> restricted = enrich(List.of());
            assertThat(restricted.get("standardConnectionGrantsThis")).isEqualTo(false);
            assertThat(restricted.get("scopesNeedingOwnOAuthClient")).isEqualTo(List.of(READONLY));

            Map<String, Object> base = new LinkedHashMap<>();
            base.put("type", "oauth2");
            base.put("requiredScopes", List.of(SEND));
            Map<String, Object> ordinary = EndpointCredentialCapability.enrich(
                    base, "gmail", List.of(SEND), gmailPolicy(), List.of());
            assertThat(ordinary.get("standardConnectionGrantsThis")).isEqualTo(true);
            assertThat(ordinary).doesNotContainKey("scopesNeedingOwnOAuthClient");
        }

        @Test
        @DisplayName("absent when the endpoint declares no scopes, because there is nothing to grant")
        void absentWithoutARequirement() {
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("type", "oauth2");
            Map<String, Object> block = EndpointCredentialCapability.enrich(
                    base, "gmail", List.of(), gmailPolicy(), List.of(account("Perso", "active", true)));
            assertThat(block).doesNotContainKey("standardConnectionGrantsThis");
            assertThat(block).doesNotContainKey("remedy");
        }
    }

    @Nested
    @DisplayName("credentials that have no scopes to compare")
    class NonOAuthAccounts {

        @Test
        @DisplayName("an API-key account of a scoped integration is NOT declared unusable, because the executor exempts it")
        void scopesDoNotApplyToANonOAuthAccount() {
            // The execution-time check returns without comparing anything unless the
            // credential is oauth2. Comparing anyway reports every required scope as
            // missing on a credential that has no scope list at all, and the contract then
            // tells the agent to abandon an account the run would have used.
            Map<String, Object> block = enrich(List.of(apiKeyAccount("PAT", "active", true)));
            assertThat(accountsOf(block).get(0).get("canRunThis")).isEqualTo(true);
            assertThat(accountsOf(block).get(0)).doesNotContainKey("missingScopes");
            assertThat(block.get("runnableWith")).isEqualTo(List.of("PAT"));
            assertThat(block).doesNotContainKey("remedy");
        }

        @Test
        @DisplayName("an account whose type is absent is exempted, because that is what the executor does with it")
        void anAbsentTypeIsExemptedLikeTheExecutorDoes() {
            // preflightScopeCheck returns without comparing anything when the type is null,
            // so exempting here keeps the listing and the run saying the same thing. The
            // alternative - compare anyway - would declare an account unusable that the run
            // accepts, and abandoning a working account costs more than attempting one that
            // is refused with a precise message. This is the posture the whole class takes:
            // where something cannot be established, do not claim a problem.
            Map<String, Object> block = enrich(List.of(
                    new EndpointCredentialCapability.Account(
                            "Mystery", "gmail", "active", null, true, List.of(SEND))));
            assertThat(accountsOf(block).get(0).get("canRunThis")).isEqualTo(true);
            assertThat(block).doesNotContainKey("remedy");
        }
    }

    @Nested
    @DisplayName("a name two accounts share")
    class AmbiguousNames {

        @Test
        @DisplayName("is never offered, because naming it resolves to NEITHER account")
        void duplicateNamesAreNotOffered() {
            // The run-time matcher refuses an ambiguous name rather than picking at random,
            // so handing it over confidently is worse than handing over nothing.
            Map<String, Object> block = enrich(List.of(
                    account("Shared", "active", true, SEND),
                    account("Shared", "active", false, READONLY),
                    account("Unique", "active", false, READONLY)));
            assertThat(block.get("runnableWith")).isEqualTo(List.of("Unique"));
            assertThat(remedy(block)).contains("credential_name=\"Unique\"");
        }

        @Test
        @DisplayName("does NOT make the endpoint look unreachable: the call still works, and the remedy must not say otherwise")
        void anUnnameableAccountIsNotAnUnusableOne() {
            // The trap the uniqueness rule opened. "Can run it" and "can be named" stopped
            // being the same question, and a remedy that reads the first off the second
            // announces "none of your 2 accounts was granted this scope, go and register an
            // OAuth application" about two accounts that both hold it, on a call that would
            // have succeeded on the default.
            Map<String, Object> block = enrich(List.of(
                    account("Shared", "active", true, READONLY),
                    account("Shared", "active", false, READONLY)));
            assertThat(block).doesNotContainKey("runnableWith");
            assertThat(block).doesNotContainKey("remedy");
        }

        @Test
        @DisplayName("when the call WOULD miss, it says the working accounts cannot be named rather than that none exists")
        void unnameableAndNotTheDefault() {
            Map<String, Object> block = enrich(List.of(
                    account("Perso", "active", true, SEND),
                    account("Shared", "active", false, READONLY),
                    account("Shared", "active", false, READONLY)));
            assertThat(block).doesNotContainKey("runnableWith");
            assertThat(remedy(block))
                    .contains("cannot be named")
                    .doesNotContain("was not granted")
                    .doesNotContain("own OAuth client");
        }

        @Test
        @DisplayName("says so on the row, so two identical-looking entries are not a puzzle")
        void theRowSaysWhyItCannotBeNamed() {
            Map<String, Object> block = enrich(List.of(
                    account("Shared", "active", true, READONLY),
                    account("Shared", "active", false, READONLY)));
            assertThat(accountsOf(block).get(1).get("canRunThis")).isEqualTo(true);
            assertThat(accountsOf(block).get(1).get("notSelectableByName"))
                    .asString().contains("same name");
            assertThat(block).doesNotContainKey("runnableWith");
        }

        @Test
        @DisplayName("a revoked namesake does not make an otherwise unique name ambiguous")
        void onlyActiveNamesakesCount() {
            // Only ACTIVE rows are candidates at run time, so a revoked twin cannot be the
            // one a name resolves to and must not cost the live one its offer.
            Map<String, Object> block = enrich(List.of(
                    account("Boulot", "needs_reauth", false, READONLY),
                    account("Boulot", "active", true, READONLY)));
            assertThat(block.get("runnableWith")).isEqualTo(List.of("Boulot"));
        }
    }

    @Nested
    @DisplayName("when the account listing could not be read")
    class ListingUnavailable {

        @Test
        @DisplayName("nothing is said about accounts, and above all nothing is claimed")
        void nullAccountsSayNothing() {
            // An unreachable credential service reported as "you have no account" sends a
            // person to connect a service they may already have connected. The policy half
            // is still knowable and is still stated.
            Map<String, Object> block = EndpointCredentialCapability.enrich(
                    oauthBase(), "gmail", List.of(READONLY), gmailPolicy(), null);
            assertThat(block.get("standardConnectionGrantsThis")).isEqualTo(false);
            assertThat(block).doesNotContainKey("accounts");
            assertThat(block).doesNotContainKey("runnableWith");
            assertThat(block).doesNotContainKey("remedy");
        }

        @Test
        @DisplayName("an EMPTY listing is a different answer and does name the missing connection")
        void emptyListingIsNotSilence() {
            assertThat(remedy(enrich(List.of()))).isNotNull();
        }
    }
}
