package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.AdminPlanService.AssignPlanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPlanService")
class AdminPlanServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private PlanStorageQuotaSyncer quotaSyncer;
    @Mock private SubscriptionCacheBuster subscriptionCacheBuster;
    @Mock private OrganizationService organizationService;

    @InjectMocks private AdminPlanService service;

    private static final Long ADMIN_ID = 1L;
    private static final Long TARGET_ID = 42L;
    private static final String PROVIDER_ID = "kc-abc-123";

    private Plan plan(String code) {
        Plan p = new Plan();
        p.setId(7L);
        p.setCode(code);
        p.setName(code);
        return p;
    }

    private User mockUser() {
        User u = mock(User.class);
        lenient().when(u.getId()).thenReturn(TARGET_ID);
        lenient().when(u.getProviderId()).thenReturn(PROVIDER_ID);
        return u;
    }

    private Subscription internalSub(String planCode) {
        Subscription s = new Subscription();
        s.setId(100L);
        s.setProvider("internal");
        s.setPlan(plan(planCode));
        s.setStatus("active");
        s.setRemainingCredits(new BigDecimal("300"));
        return s;
    }

    private void stubSaveReturnsArg() {
        when(subscriptionRepository.save(any(Subscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("assigns PRO to a user with an existing internal FREE sub: sets tier, grants base, syncs quota, busts cache")
    void assignsProOverInternalFree() {
        User user = mockUser();
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO")));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserIdForUpdate(TARGET_ID))
                .thenReturn(Optional.of(internalSub("FREE")));
        stubSaveReturnsArg();

        AssignPlanResult result = service.assignPlan(TARGET_ID, "PRO", ADMIN_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.previousPlanCode()).isEqualTo("FREE");
        assertThat(result.newPlanCode()).isEqualTo("PRO");

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        Subscription saved = captor.getValue();
        assertThat(saved.getPlan().getCode()).isEqualTo("PRO");
        assertThat(saved.getProvider()).isEqualTo("internal");
        assertThat(saved.getCreditQuantity()).isZero();
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getCancelAtPeriodEnd()).isFalse();
        assertThat(saved.getCurrentPeriodEnd()).isAfter(saved.getCurrentPeriodStart());

        // Base credits granted via the renewal path; storage quota synced; cache fan-out to the
        // grantee + every org member (same pipe as the Stripe flow).
        verify(creditAttributionService).attributeOnRenewal(eq(TARGET_ID), eq(saved));
        verify(quotaSyncer).syncAfterCommit(eq(TARGET_ID), any(Plan.class));
        verify(subscriptionCacheBuster).fanOutForOwner(eq(TARGET_ID), eq("admin.plan.grant"));
        // V311: an admin plan grant reconciles the target's workspaces to the new cap.
        verify(organizationService).reconcileWorkspacePauseState(TARGET_ID);
    }

    @Test
    @DisplayName("revert to FREE sets the FREE plan on the internal sub")
    void revertToFree() {
        User user = mockUser();
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(plan("FREE")));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserIdForUpdate(TARGET_ID))
                .thenReturn(Optional.of(internalSub("PRO")));
        stubSaveReturnsArg();

        AssignPlanResult result = service.assignPlan(TARGET_ID, "free", ADMIN_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.previousPlanCode()).isEqualTo("PRO");
        assertThat(result.newPlanCode()).isEqualTo("FREE");

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getPlan().getCode()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("lowercase plan code is normalized before lookup")
    void normalizesPlanCode() {
        User user = mockUser();
        when(planRepository.findByCode("TEAM")).thenReturn(Optional.of(plan("TEAM")));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserIdForUpdate(TARGET_ID))
                .thenReturn(Optional.of(internalSub("FREE")));
        stubSaveReturnsArg();

        AssignPlanResult result = service.assignPlan(TARGET_ID, "  team ", ADMIN_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.newPlanCode()).isEqualTo("TEAM");
        verify(planRepository).findByCode("TEAM");
    }

    @Test
    @DisplayName("bootstraps an internal subscription when the user has none yet")
    void bootstrapsSubscriptionWhenMissing() {
        User user = mockUser();
        when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(plan("STARTER")));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserIdForUpdate(TARGET_ID)).thenReturn(Optional.empty());
        when(billingCustomerRepository.findByUserId(TARGET_ID)).thenReturn(Optional.empty());
        when(billingCustomerRepository.save(any(BillingCustomer.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        stubSaveReturnsArg();

        AssignPlanResult result = service.assignPlan(TARGET_ID, "STARTER", ADMIN_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.previousPlanCode()).isNull(); // brand-new sub has no prior plan
        assertThat(result.newPlanCode()).isEqualTo("STARTER");

        verify(billingCustomerRepository).save(any(BillingCustomer.class));
        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        Subscription saved = captor.getValue();
        assertThat(saved.getPlan().getCode()).isEqualTo("STARTER");
        assertThat(saved.getProvider()).isEqualTo("internal");
        assertThat(saved.getBillingCustomer()).isNotNull();
    }

    @Test
    @DisplayName("refuses to clobber an active PAID (Stripe) subscription")
    void refusesPaidStripeSubscription() {
        User user = mockUser();
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO")));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
        Subscription stripeSub = internalSub("PRO");
        stripeSub.setProvider("stripe"); // paying customer
        when(subscriptionRepository.findActiveByUserIdForUpdate(TARGET_ID))
                .thenReturn(Optional.of(stripeSub));

        AssignPlanResult result = service.assignPlan(TARGET_ID, "PRO", ADMIN_ID);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("has_paid_subscription");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(creditAttributionService, never()).attributeOnRenewal(anyLong(), any());
        verifyNoInteractions(subscriptionCacheBuster);
        // V311: a refused grant never touches workspace pause-state.
        verify(organizationService, never()).reconcileWorkspacePauseState(any());
    }

    @Test
    @DisplayName("rejects an unsupported plan code before any lookup")
    void rejectsUnsupportedPlan() {
        AssignPlanResult result = service.assignPlan(TARGET_ID, "ENTERPRISE_BASIC", ADMIN_ID);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("unsupported_plan");
        verifyNoInteractions(planRepository);
        verifyNoInteractions(subscriptionRepository);
        verifyNoInteractions(creditAttributionService);
    }

    @Test
    @DisplayName("null target → missing_target")
    void nullTarget() {
        AssignPlanResult result = service.assignPlan(null, "PRO", ADMIN_ID);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("missing_target");
        verifyNoInteractions(planRepository);
    }

    @Test
    @DisplayName("plan not seeded on this deployment → plan_not_found")
    void planNotSeeded() {
        when(planRepository.findByCode("PRO")).thenReturn(Optional.empty());

        AssignPlanResult result = service.assignPlan(TARGET_ID, "PRO", ADMIN_ID);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("plan_not_found");
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("unknown user → user_not_found")
    void unknownUser() {
        when(planRepository.findByCode("PRO")).thenReturn(Optional.of(plan("PRO")));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

        AssignPlanResult result = service.assignPlan(TARGET_ID, "PRO", ADMIN_ID);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("user_not_found");
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }
}
