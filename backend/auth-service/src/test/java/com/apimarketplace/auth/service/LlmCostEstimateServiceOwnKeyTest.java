package com.apimarketplace.auth.service;

import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.common.plan.PlanFeatureKeys;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The own-key block of the estimate basis: what a picker needs to quote the exact fee the
 * ledger will take, instead of a token-rate estimate that route never pays.
 */
@DisplayName("LlmCostEstimateService - own-key block")
class LlmCostEstimateServiceOwnKeyTest {

    private static final OwnKeyTurnPricing FEES = new OwnKeyTurnPricing(
            new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("5"),
            new BigDecimal("10"), new BigDecimal("2"));

    private static ModelPricingService pricing() {
        ModelPricingService pricing = mock(ModelPricingService.class);
        when(pricing.getCloudLlmBillingMultiplier()).thenReturn(new BigDecimal("1.333333"));
        return pricing;
    }

    private static CreditService metered() {
        CreditService credits = mock(CreditService.class);
        when(credits.isUnlimited()).thenReturn(false);
        return credits;
    }

    /** A plan gate that answers {@code allowed} for the own-key feature, on any plan. */
    private static PlanFeatureRequirementService gateAnswering(boolean allowed) {
        PlanFeatureRequirementService gate = mock(PlanFeatureRequirementService.class);
        when(gate.allows(anyString(), anyString())).thenReturn(allowed);
        return gate;
    }

    private static PlanLimitService plan(String planCode) {
        PlanLimitService plans = mock(PlanLimitService.class);
        when(plans.getPlanCode(anyString())).thenReturn(planCode);
        return plans;
    }

    /** Managed cloud: the only edition where the plan decides anything. */
    private static AppEditionProvider managedCloud() {
        AppEditionProvider edition = mock(AppEditionProvider.class);
        when(edition.isSelfHosted()).thenReturn(false);
        when(edition.isDedicatedCloud()).thenReturn(false);
        return edition;
    }

    /** The shipped wiring: managed cloud, on a plan that allows the feature. */
    private static LlmCostEstimateService serviceWith(CredentialRepository repo, OwnKeyTurnPricing fees) {
        return serviceWith(repo, fees, gateAnswering(true), plan("PRO"), managedCloud());
    }

    private static LlmCostEstimateService serviceWith(CredentialRepository repo, OwnKeyTurnPricing fees,
                                                      PlanFeatureRequirementService gate, PlanLimitService plans) {
        return serviceWith(repo, fees, gate, plans, managedCloud());
    }

    private static LlmCostEstimateService serviceWith(CredentialRepository repo, OwnKeyTurnPricing fees,
                                                      PlanFeatureRequirementService gate, PlanLimitService plans,
                                                      AppEditionProvider edition) {
        LlmCostEstimateService service =
                new LlmCostEstimateService(pricing(), metered(), repo, gate, plans, edition);
        ReflectionTestUtils.setField(service, "ownKeyTurnPricing", fees);
        return service;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ownKeyOf(Map<String, Object> basis) {
        return (Map<String, Object>) basis.get("ownKey");
    }

    @Test
    @DisplayName("publishes the provider whose saved key serves, stripped of the llm_ prefix, with the fee per tier")
    void publishesProvidersAndFees() {
        CredentialRepository repo = mock(CredentialRepository.class);
        when(repo.findUsableLlmIntegrations("42")).thenReturn(List.of("llm_anthropic", "llm_openai"));

        Map<String, Object> ownKey = ownKeyOf(serviceWith(repo, FEES).buildBasis("42"));

        assertThat(ownKey).isNotNull();
        assertThat((List<String>) ownKey.get("providers")).containsExactly("anthropic", "openai");
        assertThat((Map<String, Object>) ownKey.get("feeByTier"))
                .containsEntry("top", new BigDecimal("10"))
                .containsEntry("high", new BigDecimal("5"))
                .containsEntry("mid", new BigDecimal("2"))
                .containsEntry("budget", new BigDecimal("1"))
                .containsEntry("unknown", new BigDecimal("2"));
    }

    @Test
    @DisplayName("the fee ladder is a price list: published to a caller with no key, on a plan that forbids the route")
    void theLadderIsPublishedToEveryone() {
        // The own-key BLOCK answers "does your next call take that route", so it is absent for
        // everyone who has not saved a key - which is exactly the reader deciding whether to save
        // one. The ladder answers "what would it cost", and that question has the same answer for
        // all of them. Without this, the settings panel could only advertise a fee to the people
        // already paying it.
        CredentialRepository noKey = mock(CredentialRepository.class);
        when(noKey.findUsableLlmIntegrations(anyString())).thenReturn(List.of());

        Map<String, Object> basis = serviceWith(noKey, FEES, gateAnswering(false), plan("FREE")).buildBasis("42");

        assertThat(basis).doesNotContainKey("ownKey");
        assertThat((Map<String, Object>) basis.get("ownKeyFeeByTier"))
                .containsEntry("budget", new BigDecimal("1"))
                .containsEntry("mid", new BigDecimal("2"))
                .containsEntry("high", new BigDecimal("5"))
                .containsEntry("top", new BigDecimal("10"))
                .containsEntry("unknown", new BigDecimal("2"));
        // Even with no caller at all: the price list is not about anybody.
        assertThat(serviceWith(noKey, FEES).buildBasis(null)).containsKey("ownKeyFeeByTier");
    }

    @Test
    @DisplayName("the advertised ladder and the quoted one are the same numbers, from one bean")
    void theAdvertisedLadderIsTheQuotedOne() {
        // Two maps in one payload are two chances to drift, and the failure is silent money: a
        // panel promising 5 credits beside a row charging 10. They are built by one method.
        CredentialRepository repo = mock(CredentialRepository.class);
        when(repo.findUsableLlmIntegrations("42")).thenReturn(List.of("llm_anthropic"));

        Map<String, Object> basis = serviceWith(repo, FEES).buildBasis("42");

        assertThat(basis.get("ownKeyFeeByTier")).isEqualTo(ownKeyOf(basis).get("feeByTier"));
    }

    @Test
    @DisplayName("an install that wires no ladder publishes no price list, rather than an empty one")
    void noLadderWiredPublishesNoPriceList() {
        // An empty map would draw an (i) opening onto an empty table, which reads as "free".
        CredentialRepository repo = mock(CredentialRepository.class);

        assertThat(serviceWith(repo, null).buildBasis("42")).doesNotContainKey("ownKeyFeeByTier");
    }

    @Test
    @DisplayName("no key, no caller, no fee config or no credential table: the block is absent and every picker falls back to the estimate")
    void absentWhenNothingToSay() {
        CredentialRepository empty = mock(CredentialRepository.class);
        when(empty.findUsableLlmIntegrations(anyString())).thenReturn(List.of());
        assertThat(serviceWith(empty, FEES).buildBasis("42")).doesNotContainKey("ownKey");

        CredentialRepository repo = mock(CredentialRepository.class);
        assertThat(serviceWith(repo, FEES).buildBasis(null)).doesNotContainKey("ownKey");
        assertThat(serviceWith(repo, FEES).buildBasis("  ")).doesNotContainKey("ownKey");
        // A blank caller must not even reach the credential table.
        verify(repo, never()).findUsableLlmIntegrations(anyString());

        CredentialRepository withKey = mock(CredentialRepository.class);
        when(withKey.findUsableLlmIntegrations("42")).thenReturn(List.of("llm_openai"));
        assertThat(serviceWith(withKey, null).buildBasis("42")).doesNotContainKey("ownKey");

        // The repository is optional on purpose (a context that wires no credential table),
        // and a present caller must not turn that into a null dereference.
        assertThat(serviceWith(null, FEES).buildBasis("42")).doesNotContainKey("ownKey");
    }

    @Test
    @DisplayName("only llm_ rows become providers: one that is not, or names nothing after the prefix, is dropped")
    void onlyLlmRowsBecomeProviders() {
        CredentialRepository repo = mock(CredentialRepository.class);
        // A non-LLM row used to be published under its own name, which would have quoted the
        // own-key fee on every model of a provider the caller holds no LLM key for.
        when(repo.findUsableLlmIntegrations("42")).thenReturn(List.of("smtp", "llm_", "llm_openai"));

        Map<String, Object> ownKey = ownKeyOf(serviceWith(repo, FEES).buildBasis("42"));

        assertThat((List<String>) ownKey.get("providers")).containsExactly("openai");
    }

    @Test
    @DisplayName("nothing but such rows leaves no block at all, rather than an empty one")
    void noProviderAtAllLeavesNoBlock() {
        CredentialRepository repo = mock(CredentialRepository.class);
        when(repo.findUsableLlmIntegrations("42")).thenReturn(List.of("llm_", "smtp"));

        assertThat(serviceWith(repo, FEES).buildBasis("42")).doesNotContainKey("ownKey");
    }

    @Test
    @DisplayName("a credential-table failure costs the own-key block, never the estimate itself")
    void credentialFailureDegradesToTheEstimate() {
        CredentialRepository broken = mock(CredentialRepository.class);
        when(broken.findUsableLlmIntegrations("42")).thenThrow(new IllegalStateException("db down"));

        Map<String, Object> basis = serviceWith(broken, FEES).buildBasis("42");

        assertThat(basis).doesNotContainKey("ownKey");
        assertThat(basis).containsEntry("enabled", true);
        assertThat((Map<String, Object>) basis.get("profiles")).isNotEmpty();
    }

    @Test
    @DisplayName("a plan that does not include own keys is quoted the platform estimate, on the same table the run reads")
    void aPlanBelowTheBarIsNeverQuotedTheFee() {
        CredentialRepository repo = mock(CredentialRepository.class);
        PlanFeatureRequirementService gate = gateAnswering(false);
        PlanLimitService plans = plan("FREE");

        Map<String, Object> basis = serviceWith(repo, FEES, gate, plans).buildBasis("42");

        assertThat(basis).doesNotContainKey("ownKey");
        // Asked about the right feature, for the right caller...
        verify(gate).allows("FREE", PlanFeatureKeys.OWN_LLM_KEY);
        // ...and the saved keys are nobody's business once the plan has said no.
        verify(repo, never()).findUsableLlmIntegrations(anyString());
    }

    @Test
    @DisplayName("self-hosted and dedicated cloud are never gated, so the plan table cannot silence the fee there")
    void singleTenantEditionsAreNeverGated() {
        // Both editions meter credits and both run on the tenant key whatever the plan says
        // (OwnKeyFeatureGate short-circuits on them). The PRO row V507 seeds exists on every
        // install and these tenants routinely hold no subscription, so a gate without this
        // branch would quote the platform estimate for a turn billed the flat fee.
        for (boolean selfHosted : new boolean[] { true, false }) {
            CredentialRepository repo = mock(CredentialRepository.class);
            when(repo.findUsableLlmIntegrations("42")).thenReturn(List.of("llm_openai"));
            AppEditionProvider edition = mock(AppEditionProvider.class);
            when(edition.isSelfHosted()).thenReturn(selfHosted);
            when(edition.isDedicatedCloud()).thenReturn(!selfHosted);
            PlanFeatureRequirementService refusing = gateAnswering(false);

            Map<String, Object> basis =
                    serviceWith(repo, FEES, refusing, plan("__NONE__"), edition).buildBasis("42");

            assertThat(ownKeyOf(basis)).as("selfHosted=%s", selfHosted).isNotNull();
            verify(refusing, never()).allows(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("a half-wired plan cannot ask the question, so it answers like the run: the fee stands")
    void aHalfWiredPlanQuotesTheFee() {
        CredentialRepository repo = mock(CredentialRepository.class);
        when(repo.findUsableLlmIntegrations("42")).thenReturn(List.of("llm_openai"));

        assertThat(ownKeyOf(serviceWith(repo, FEES, gateAnswering(false), null).buildBasis("42"))).isNotNull();
        assertThat(ownKeyOf(serviceWith(repo, FEES, null, plan("FREE")).buildBasis("42"))).isNotNull();

        // The plan LOOKUP throwing is the same case as the requirement lookup throwing, and it
        // is a different call in the same try block, so it needs its own assertion.
        PlanLimitService brokenPlans = mock(PlanLimitService.class);
        when(brokenPlans.getPlanCode(anyString())).thenThrow(new IllegalStateException("subscriptions down"));
        assertThat(ownKeyOf(serviceWith(repo, FEES, gateAnswering(false), brokenPlans).buildBasis("42"))).isNotNull();
    }

    @Test
    @DisplayName("the frontend spells the feature key exactly as the backend does, or the lock never matches the gate")
    void theFrontendSpellsTheFeatureKeyTheSameWay() throws Exception {
        // Surefire runs with CWD = module basedir; skip rather than fail where the frontend is
        // not checked out beside it, like the other cross-file pins in this module.
        Path shared = Path.of("..", "..", "frontend", "lib", "billing", "ownKeyFeature.ts");
        Assumptions.assumeTrue(Files.exists(shared), "frontend not reachable from this working directory");

        assertThat(Files.readString(shared))
                .as("frontend/lib/billing/ownKeyFeature.ts must hold PlanFeatureKeys.OWN_LLM_KEY verbatim")
                .contains("'" + PlanFeatureKeys.OWN_LLM_KEY + "'");
    }

    @Test
    @DisplayName("an unreadable plan quotes the fee, because the run allows the key in exactly that case too")
    void anUnreadablePlanFailsTheSameWayTheRunDoes() {
        CredentialRepository repo = mock(CredentialRepository.class);
        when(repo.findUsableLlmIntegrations("42")).thenReturn(List.of("llm_openai"));
        PlanFeatureRequirementService gate = mock(PlanFeatureRequirementService.class);
        when(gate.allows(anyString(), anyString())).thenThrow(new IllegalStateException("plan table down"));

        Map<String, Object> basis = serviceWith(repo, FEES, gate, plan("PRO")).buildBasis("42");

        assertThat(ownKeyOf(basis)).isNotNull();
    }

    @Test
    @DisplayName("an install with no plan service wired is single-tenant: never gated, so never silent about the fee")
    void noPlanServiceWiredQuotesTheFee() {
        CredentialRepository repo = mock(CredentialRepository.class);
        when(repo.findUsableLlmIntegrations("42")).thenReturn(List.of("llm_openai"));

        Map<String, Object> basis = serviceWith(repo, FEES, null, null).buildBasis("42");

        assertThat(ownKeyOf(basis)).isNotNull();
    }

    @Test
    @DisplayName("an install that meters nothing (CE) publishes no basis at all, own key or not")
    void unlimitedPublishesNothing() {
        CredentialRepository repo = mock(CredentialRepository.class);
        PlanFeatureRequirementService gate = mock(PlanFeatureRequirementService.class);
        CreditService unlimited = mock(CreditService.class);
        when(unlimited.isUnlimited()).thenReturn(true);
        LlmCostEstimateService service =
                new LlmCostEstimateService(pricing(), unlimited, repo, gate, plan("CE"), managedCloud());
        ReflectionTestUtils.setField(service, "ownKeyTurnPricing", FEES);

        Map<String, Object> basis = service.buildBasis("42");

        assertThat(basis).containsEntry("enabled", false)
                .doesNotContainKey("ownKey")
                // Nor the price list: an install that meters nothing has no price to advertise.
                .doesNotContainKey("ownKeyFeeByTier");
        verify(repo, never()).findUsableLlmIntegrations(anyString());
        verify(gate, never()).allows(anyString(), anyString());
    }
}
