package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CapScope;
import com.apimarketplace.auth.domain.PartnerOffer;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.repository.PartnerOfferRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.common.plan.PlanTier;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PartnerOfferServiceTest {

    private static final long PARTNER = 42L;

    private PartnerOfferRepository offers;
    private RewardCodeRepository codes;
    private UserRepository users;
    private UserService userService;
    private PartnerOfferAppsClient apps;
    private PlanFeatureRequirementService planFeatures;
    private RewardRedemptionRepository redemptions;
    private PlanRepository plans;
    private PartnerOfferService service;
    private RewardCode code;

    @BeforeEach
    void setUp() {
        offers = mock(PartnerOfferRepository.class);
        codes = mock(RewardCodeRepository.class);
        users = mock(UserRepository.class);
        userService = mock(UserService.class);
        apps = mock(PartnerOfferAppsClient.class);
        planFeatures = mock(PlanFeatureRequirementService.class);
        redemptions = mock(RewardRedemptionRepository.class);
        when(redemptions.findByRedeemerUserIdAndProgram(anyLong(), eq(RewardProgram.PARTNER))).thenReturn(Optional.empty());
        plans = mock(PlanRepository.class);
        service = new PartnerOfferService(offers, codes, users, userService, apps, planFeatures, redemptions, plans,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        code = new RewardCode();
        code.setId(400L);
        code.setCode("NORTHWIND");
        code.setProgram(RewardProgram.PARTNER);
        code.setOwnerUserId(PARTNER);
        code.setBenefitAmount(8000);
        code.setActive(true);
        code.setValidFrom(Instant.now().minus(1, ChronoUnit.DAYS));
        when(codes.findByOwnerUserIdAndProgram(PARTNER, RewardProgram.PARTNER)).thenReturn(Optional.of(code));
        when(codes.findById(400L)).thenReturn(Optional.of(code));
        when(offers.save(any(PartnerOffer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("an offer on the partner's own code, with a short unguessable token")
        void createsOnOwnCode() {
            var outcome = service.create(PARTNER, "pro", 5, "Monthly", "  For Acme  ", null);

            assertThat(outcome.succeeded()).isTrue();
            PartnerOffer o = outcome.offer();
            assertThat(o.getPartnerUserId()).isEqualTo(PARTNER);
            assertThat(o.getRewardCodeId()).isEqualTo(400L);
            assertThat(o.getPlanCode()).isEqualTo("PRO");
            assertThat(o.getCreditTierIndex()).isEqualTo(5);
            assertThat(o.getBillingCycle()).isEqualTo("monthly");
            assertThat(o.getLabel()).isEqualTo("For Acme");
            assertThat(o.getToken()).matches("[A-HJ-NP-Za-km-z2-9]{10}");
        }

        @Test
        @DisplayName("a blank note is stored as none")
        void blankLabelIsNull() {
            assertThat(service.create(PARTNER, "TEAM", 7, "yearly", "   ", null).offer().getLabel()).isNull();
        }

        @Test
        @DisplayName("refused without a partner code, or with one that cannot bring a sign-up now")
        void refusedWithoutLiveCode() {
            assertThat(service.create(7L, "PRO", 5, "monthly", null, null).error()).isEqualTo("not_partner");
            assertThat(service.create(null, "PRO", 5, "monthly", null, null).error()).isEqualTo("unauthorized");

            code.setActive(false);
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, null).error()).isEqualTo("code_inactive");

            code.setActive(true);
            code.setValidUntil(Instant.now().minus(1, ChronoUnit.HOURS));
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, null).error()).isEqualTo("code_inactive");

            code.setValidUntil(null);
            code.setCapScope(CapScope.GLOBAL);
            code.setCapLimit(3);
            code.setCurrentRedemptions(3);
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, null).error()).isEqualTo("code_inactive");
            verify(offers, never()).save(any());
        }

        @Test
        @DisplayName("refused on a plan, credit tier or cycle the price list does not sell")
        void refusedOnInvalidChoice() {
            assertThat(service.create(PARTNER, "ENTERPRISE", 5, "monthly", null, null).error()).isEqualTo("invalid_plan");
            assertThat(service.create(PARTNER, null, 5, "monthly", null, null).error()).isEqualTo("invalid_plan");
            assertThat(service.create(PARTNER, "PRO", -1, "monthly", null, null).error()).isEqualTo("invalid_credits");
            assertThat(service.create(PARTNER, "PRO", 10, "monthly", null, null).error()).isEqualTo("invalid_credits");
            assertThat(service.create(PARTNER, "PRO", null, "monthly", null, null).error()).isEqualTo("invalid_credits");
            // Starter stops at 100K credits (tier 4).
            assertThat(service.create(PARTNER, "STARTER", 5, "monthly", null, null).error()).isEqualTo("invalid_credits");
            assertThat(service.create(PARTNER, "STARTER", 4, "monthly", null, null).succeeded()).isTrue();
            assertThat(service.create(PARTNER, "PRO", 5, "weekly", null, null).error()).isEqualTo("invalid_cycle");
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", "x".repeat(121), null).error()).isEqualTo("too_long");
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", "x".repeat(120), null).succeeded()).isTrue();
        }

        @Test
        @DisplayName("refused past the live-offer cap, and the last one under it is still created")
        void refusedPastCap() {
            when(offers.countByPartnerUserIdAndActiveTrue(PARTNER)).thenReturn((long) PartnerOfferService.MAX_ACTIVE_OFFERS - 1);
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, null).succeeded()).isTrue();

            when(offers.countByPartnerUserIdAndActiveTrue(PARTNER)).thenReturn((long) PartnerOfferService.MAX_ACTIVE_OFFERS);
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, null).error()).isEqualTo("too_many_offers");
        }

        @Test
        @DisplayName("a token space that keeps colliding stops after eight draws and saves nothing")
        void tokenDrawsAreBounded() {
            when(offers.existsByToken(anyString())).thenReturn(true);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(PARTNER, "PRO", 5, "monthly", null, null))
                    .isInstanceOf(IllegalStateException.class);
            verify(offers, times(8)).existsByToken(anyString());
            verify(offers, never()).save(any());
        }

        @Test
        @DisplayName("a token already taken is drawn again")
        void tokenCollisionRedraws() {
            when(offers.existsByToken(anyString())).thenReturn(true, false);

            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, null).succeeded()).isTrue();
            verify(offers, times(2)).existsByToken(anyString());
        }
    }

    @Nested
    @DisplayName("create with applications")
    class CreateWithApps {

        private static final String APP_A = "6f1c0d2e-0000-4000-8000-00000000000a";
        private static final String APP_B = "6f1c0d2e-0000-4000-8000-00000000000b";

        @Test
        @DisplayName("the partner's offerable apps are kept, each once, in the order chosen")
        void keepsOfferableApps() {
            when(apps.offerable(PARTNER, List.of(APP_B, APP_A))).thenReturn(List.of(Map.of("id", APP_B), Map.of("id", APP_A)));

            var outcome = service.create(PARTNER, "PRO", 5, "monthly", null, List.of(" " + APP_B.toUpperCase() + " ", APP_A, APP_B));

            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.offer().getAppPublicationIds()).containsExactly(APP_B, APP_A);
        }

        @Test
        @DisplayName("no apps (absent or empty) asks publication-service nothing")
        void noApps() {
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, List.of()).offer().getAppPublicationIds()).isEmpty();
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, null).offer().getAppPublicationIds()).isEmpty();
            verifyNoInteractions(apps);
        }

        @Test
        @DisplayName("refused when one app is not the partner's to give: the offer would promise what it cannot deliver")
        void refusedWhenOneIsNotOfferable() {
            when(apps.offerable(PARTNER, List.of(APP_A, APP_B))).thenReturn(List.of(Map.of("id", APP_A)));

            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, List.of(APP_A, APP_B)).error()).isEqualTo("invalid_apps");
            verify(offers, never()).save(any());
        }

        @Test
        @DisplayName("regression: refused when an app uses vector search and the offer's plan is below the one an admin set for it, since its install would then be refused after the client paid")
        void refusedWhenThePlanCannotRunAnApp() {
            when(apps.offerable(PARTNER, List.of(APP_A, APP_B))).thenReturn(List.of(
                    Map.of("id", APP_A, "features", List.of()),
                    Map.of("id", APP_B, "features", List.of("VECTOR_SEARCH"))));
            when(planFeatures.allows("STARTER", "feature:vector_search")).thenReturn(false);
            when(planFeatures.allows("PRO", "feature:vector_search")).thenReturn(true);

            assertThat(service.create(PARTNER, "STARTER", 2, "monthly", null, List.of(APP_A, APP_B)).error())
                    .isEqualTo("apps_need_higher_plan");
            verify(offers, never()).save(any());

            // The same apps on a plan that includes it.
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, List.of(APP_A, APP_B)).succeeded()).isTrue();
        }

        @Test
        @DisplayName("an app without priced features, or whose card names none, needs no plan check")
        void noPricedFeatureNoCheck() {
            when(apps.offerable(PARTNER, List.of(APP_A, APP_B))).thenReturn(List.of(
                    Map.of("id", APP_A),
                    Map.of("id", APP_B, "features", List.of("SOMETHING_ELSE"))));

            assertThat(service.create(PARTNER, "STARTER", 2, "monthly", null, List.of(APP_A, APP_B)).succeeded()).isTrue();
            verifyNoInteractions(planFeatures);
        }

        @Test
        @DisplayName("regression: refused when the apps bring more interfaces, or more apps, than the recommended plan holds")
        void refusedWhenThePlanCannotHoldTheApps() {
            quotas("STARTER", 15, 10);
            when(apps.offerable(PARTNER, List.of(APP_A, APP_B))).thenReturn(List.of(
                    Map.of("id", APP_A, "interfaceCount", 6), Map.of("id", APP_B, "interfaceCount", 5)));
            assertThat(service.create(PARTNER, "STARTER", 2, "monthly", null, List.of(APP_A, APP_B)).error()).isEqualTo("apps_exceed_plan");

            quotas("STARTER", 1, 10);
            when(apps.offerable(PARTNER, List.of(APP_A, APP_B))).thenReturn(List.of(
                    Map.of("id", APP_A, "interfaceCount", 1), Map.of("id", APP_B, "interfaceCount", 1)));
            assertThat(service.create(PARTNER, "STARTER", 2, "monthly", null, List.of(APP_A, APP_B)).error()).isEqualTo("apps_exceed_plan");
            verify(offers, never()).save(any());

            // Within the quotas (and an unlimited one), created.
            quotas("STARTER", 15, 10);
            when(apps.offerable(PARTNER, List.of(APP_A, APP_B))).thenReturn(List.of(
                    Map.of("id", APP_A, "interfaceCount", 5), Map.of("id", APP_B, "interfaceCount", 5)));
            assertThat(service.create(PARTNER, "STARTER", 2, "monthly", null, List.of(APP_A, APP_B)).succeeded()).isTrue();
            quotas("TEAM", null, null);
            assertThat(service.create(PARTNER, "TEAM", 2, "monthly", null, List.of(APP_A, APP_B)).succeeded()).isTrue();
        }

        @Test
        @DisplayName("refused on a malformed id, past ten apps, or when the apps cannot be checked")
        void refusedOnBadInput() {
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, List.of("not-an-id")).error()).isEqualTo("invalid_apps");
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, java.util.Arrays.asList(APP_A, null)).error()).isEqualTo("invalid_apps");

            List<String> eleven = java.util.stream.IntStream.range(0, 11)
                    .mapToObj(i -> String.format("6f1c0d2e-0000-4000-8000-%012d", i)).toList();
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, eleven).error()).isEqualTo("too_many_apps");
            verifyNoInteractions(apps);

            when(apps.offerable(eq(PARTNER), anyList())).thenThrow(new org.springframework.web.client.ResourceAccessException("down"));
            assertThat(service.create(PARTNER, "PRO", 5, "monthly", null, List.of(APP_A)).error()).isEqualTo("apps_unavailable");
            verify(offers, never()).save(any());
        }
    }

    /** Vector search open from {@code minPlan} up, as the requirement an admin sets. */
    private void vectorFrom(String minPlan) {
        when(planFeatures.allows(anyString(), eq("feature:vector_search")))
                .thenAnswer(inv -> PlanTier.meets(inv.getArgument(0), minPlan));
    }

    private void quotas(String code, Integer maxApps, Integer maxInterfaces) {
        Plan plan = new Plan();
        plan.setCode(code);
        plan.setMaxApplications(maxApps);
        plan.setMaxInterfaces(maxInterfaces);
        when(plans.findByCode(code)).thenReturn(Optional.of(plan));
    }

    @Nested
    @DisplayName("deactivate")
    class Deactivate {

        @Test
        @DisplayName("only the partner's own offer, and an unknown token reads the same as someone else's")
        void ownOnly() {
            PartnerOffer mine = offer("MINE000001", PARTNER);
            PartnerOffer theirs = offer("THEIRS0001", 9L);
            when(offers.findByToken("MINE000001")).thenReturn(Optional.of(mine));
            when(offers.findByToken("THEIRS0001")).thenReturn(Optional.of(theirs));

            assertThat(service.deactivate(PARTNER, "THEIRS0001")).isFalse();
            assertThat(theirs.isActive()).isTrue();
            assertThat(service.deactivate(PARTNER, "NOPE")).isFalse();
            assertThat(service.deactivate(PARTNER, "MINE000001")).isTrue();
            assertThat(mine.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("public offer")
    class Public {

        @Test
        @DisplayName("the plan, the code's credits and the partner's public identity")
        void liveOffer() {
            PartnerOffer o = offer("ABCDEFGHJK", PARTNER);
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(o));
            User partnerUser = new User();
            when(users.findById(PARTNER)).thenReturn(Optional.of(partnerUser));
            PublicProfileDto profile = new PublicProfileDto(PARTNER, "Northwind Automation", "northwind", "/a.png", null,
                    LocalDateTime.now(), false, false, true, "gold");
            when(userService.getPublicProfile(partnerUser)).thenReturn(Optional.of(profile));

            var view = service.publicOffer("ABCDEFGHJK").orElseThrow();

            assertThat(view.code()).isEqualTo("NORTHWIND");
            assertThat(view.credits()).isEqualTo(8000);
            assertThat(view.planCode()).isEqualTo("PRO");
            assertThat(view.creditTierIndex()).isEqualTo(5);
            assertThat(view.billingCycle()).isEqualTo("monthly");
            assertThat(view.partner()).isSameAs(profile);
        }

        @Test
        @DisplayName("a partner with a private profile: the offer still shows, without naming them")
        void privateProfile() {
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(offer("ABCDEFGHJK", PARTNER)));
            when(users.findById(PARTNER)).thenReturn(Optional.of(new User()));
            when(userService.getPublicProfile(any())).thenReturn(Optional.empty());

            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().partner()).isNull();
        }

        @Test
        @DisplayName("a partner whose account row is gone: the offer still shows, without naming anyone")
        void partnerRowMissing() {
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(offer("ABCDEFGHJK", PARTNER)));
            when(users.findById(PARTNER)).thenReturn(Optional.empty());

            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().partner()).isNull();
            verify(userService, never()).getPublicProfile(any());
        }

        @Test
        @DisplayName("nothing for an unknown or deactivated offer, or when the code can no longer bring a sign-up")
        void nothingWhenNotLive() {
            assertThat(service.publicOffer("UNKNOWN123")).isEmpty();
            assertThat(service.publicOffer(null)).isEmpty();
            assertThat(service.publicOffer("X".repeat(17))).isEmpty();

            PartnerOffer off = offer("ABCDEFGHJK", PARTNER);
            off.setActive(false);
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(off));
            assertThat(service.publicOffer("ABCDEFGHJK")).isEmpty();

            off.setActive(true);
            code.setValidUntil(Instant.now().minus(1, ChronoUnit.HOURS));
            assertThat(service.publicOffer("ABCDEFGHJK")).isEmpty();

            // Every allowed sign-up taken: the page would promise credits the code now refuses.
            code.setValidUntil(null);
            code.setCapScope(CapScope.GLOBAL);
            code.setCapLimit(3);
            code.setCurrentRedemptions(3);
            assertThat(service.publicOffer("ABCDEFGHJK")).isEmpty();
        }

        @Test
        @DisplayName("the apps it gives come as cards, only those that can still be given; none when they cannot be read")
        void apps() {
            PartnerOffer o = offer("ABCDEFGHJK", PARTNER);
            o.setAppPublicationIds(List.of("6f1c0d2e-0000-4000-8000-00000000000a", "6f1c0d2e-0000-4000-8000-00000000000b"));
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(o));
            Map<String, Object> card = Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000b", "title", "Invoice chaser");
            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenReturn(List.of(card));

            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().apps()).containsExactly(card);

            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenThrow(new org.springframework.web.client.ResourceAccessException("down"));
            var view = service.publicOffer("ABCDEFGHJK").orElseThrow();
            assertThat(view.apps()).isEmpty();
            assertThat(view.planCode()).isEqualTo("PRO");
        }

        @Test
        @DisplayName("regression: the page is told the smallest plan the offered apps install on, the one an admin set for vector search; none when no app needs one")
        void appsPlan() {
            PartnerOffer o = offer("ABCDEFGHJK", PARTNER);
            o.setAppPublicationIds(List.of("6f1c0d2e-0000-4000-8000-00000000000a"));
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(o));
            vectorFrom("TEAM");
            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenReturn(List.of(
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "features", List.of("VECTOR_SEARCH"))));

            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().appsPlan()).isEqualTo("TEAM");

            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenReturn(List.of(
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "features", List.of())));
            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().appsPlan()).isNull();

            // Vector search open to every plan (no requirement row): nothing to require.
            vectorFrom("FREE");
            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenReturn(List.of(
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "features", List.of("VECTOR_SEARCH"))));
            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().appsPlan()).isNull();
        }

        @Test
        @DisplayName("regression: a checkout's plan is checked against the apps as they are now; an offer without apps, or apps that cannot be read, never block it")
        void appsRunOn() {
            PartnerOffer o = offer("ABCDEFGHJK", PARTNER);
            assertThat(service.appsRunOn(o, "STARTER")).isTrue();
            verifyNoInteractions(apps);

            o.setAppPublicationIds(List.of("6f1c0d2e-0000-4000-8000-00000000000a"));
            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenReturn(List.of(
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "features", List.of("VECTOR_SEARCH"))));
            when(planFeatures.allows("STARTER", "feature:vector_search")).thenReturn(false);
            when(planFeatures.allows("PRO", "feature:vector_search")).thenReturn(true);
            assertThat(service.appsRunOn(o, "STARTER")).isFalse();
            assertThat(service.appsRunOn(o, "PRO")).isTrue();

            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenThrow(new org.springframework.web.client.ResourceAccessException("down"));
            assertThat(service.appsRunOn(o, "STARTER")).isTrue();
        }

        @Test
        @DisplayName("regression: a client already attributed to the partner still reads the offer once the code is used up or expired, without the code's credits; nobody else does")
        void attributedClientReadsAnOfferWhoseCodeIsDone() {
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(offer("ABCDEFGHJK", PARTNER)));
            when(users.findById(PARTNER)).thenReturn(Optional.empty());
            code.setCapScope(CapScope.GLOBAL);
            code.setCapLimit(3);
            code.setCurrentRedemptions(3);
            RewardRedemption ours = new RewardRedemption();
            ours.setOwnerUserId(PARTNER);
            when(redemptions.findByRedeemerUserIdAndProgram(7L, RewardProgram.PARTNER)).thenReturn(Optional.of(ours));
            RewardRedemption theirs = new RewardRedemption();
            theirs.setOwnerUserId(PARTNER + 1);
            when(redemptions.findByRedeemerUserIdAndProgram(8L, RewardProgram.PARTNER)).thenReturn(Optional.of(theirs));

            var view = service.publicOffer("ABCDEFGHJK", 7L).orElseThrow();
            assertThat(view.credits()).isZero();
            assertThat(view.planCode()).isEqualTo("PRO");

            assertThat(service.publicOffer("ABCDEFGHJK", 8L)).isEmpty();
            assertThat(service.publicOffer("ABCDEFGHJK", 9L)).isEmpty();
            assertThat(service.publicOffer("ABCDEFGHJK", null)).isEmpty();
            assertThat(service.publicOffer("ABCDEFGHJK")).isEmpty();

            // A live code: everyone reads it, with its credits.
            code.setCurrentRedemptions(1);
            assertThat(service.publicOffer("ABCDEFGHJK", 7L).orElseThrow().credits()).isEqualTo(8000);
        }

        @Test
        @DisplayName("an offer whose apps need a plan no offer sells (above Team) is not offered: it could never be paid for")
        void appsBeyondTheSoldPlans() {
            PartnerOffer o = offer("ABCDEFGHJK", PARTNER);
            o.setAppPublicationIds(List.of("6f1c0d2e-0000-4000-8000-00000000000a"));
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(o));
            vectorFrom("ENTERPRISE");
            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenReturn(List.of(
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "features", List.of("VECTOR_SEARCH"))));

            assertThat(service.publicOffer("ABCDEFGHJK")).isEmpty();
        }

        @Test
        @DisplayName("regression: the plan the apps need also counts their interfaces against each plan's quota (a Starter offer whose apps bring more interfaces than Starter holds needs Pro)")
        void appsPlanCountsInterfaces() {
            PartnerOffer o = offer("ABCDEFGHJK", PARTNER);
            o.setAppPublicationIds(List.of("6f1c0d2e-0000-4000-8000-00000000000a", "6f1c0d2e-0000-4000-8000-00000000000b"));
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(o));
            quotas("STARTER", 15, 10);
            quotas("PRO", 50, 50);
            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenReturn(List.of(
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "interfaceCount", 6),
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000b", "interfaceCount", 6)));

            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().appsPlan()).isEqualTo("PRO");
            assertThat(service.appsRunOn(o, "STARTER")).isFalse();
            assertThat(service.appsRunOn(o, "PRO")).isTrue();

            when(apps.offerable(PARTNER, o.getAppPublicationIds())).thenReturn(List.of(
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000a", "interfaceCount", 5),
                    Map.of("id", "6f1c0d2e-0000-4000-8000-00000000000b", "interfaceCount", 5)));
            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().appsPlan()).isNull();
            assertThat(service.appsRunOn(o, "STARTER")).isTrue();
        }

        @Test
        @DisplayName("an offer without apps asks publication-service nothing")
        void noAppsNoCall() {
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(offer("ABCDEFGHJK", PARTNER)));

            assertThat(service.publicOffer("ABCDEFGHJK").orElseThrow().apps()).isEmpty();
            verifyNoInteractions(apps);
        }

        @Test
        @DisplayName("the code the offer points at must still be a partner code")
        void onlyPartnerCode() {
            when(offers.findByToken("ABCDEFGHJK")).thenReturn(Optional.of(offer("ABCDEFGHJK", PARTNER)));
            code.setProgram(RewardProgram.PROMO);

            assertThat(service.publicOffer("ABCDEFGHJK")).isEmpty();
        }
    }

    @Test
    @DisplayName("list: the partner's live offers, from the repository's newest-first query")
    void list() {
        when(offers.findByPartnerUserIdAndActiveTrueOrderByCreatedAtDescIdDesc(PARTNER)).thenReturn(List.of(offer("A123456789", PARTNER)));

        assertThat(service.list(PARTNER)).hasSize(1);
        assertThat(service.list(null)).isEmpty();
    }

    private static PartnerOffer offer(String token, long partner) {
        PartnerOffer o = new PartnerOffer();
        o.setToken(token);
        o.setPartnerUserId(partner);
        o.setRewardCodeId(400L);
        o.setPlanCode("PRO");
        o.setCreditTierIndex(5);
        o.setBillingCycle("monthly");
        o.setActive(true);
        return o;
    }
}
