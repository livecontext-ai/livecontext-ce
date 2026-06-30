package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.UserResolutionResponse;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.validation.AgeValidator;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * PR6 audit B fix - pin the dual-write wiring inside
 * {@link UserResolutionService#buildResolutionResponse(User, String)}.
 *
 * <p>Audit B flagged: when {@code planResolutionService} is wired
 * ({@code @Autowired(required=false)}), the dual-write block at
 * UserResolutionService:494-501 populates {@code billingPlan} and
 * {@code activeOrgPlan} into the response. Without a test on this branch,
 * PR7 cutover would be the first thing to actually exercise it in anger.
 *
 * <p>The other unit tests use {@code @InjectMocks} / direct ctor and never
 * set the optional field - so the dual-write was dead-code in tests. This
 * test class invokes {@code buildResolutionResponse} via reflection with
 * {@code planResolutionService} ACTUALLY set, and asserts the two new
 * fields land in the response.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserResolutionService dual-write wiring (PR6 audit B fix)")
class UserResolutionServiceDualWriteTest {

    @Mock private UserRepository userRepository;
    @Mock private UsernameValidator usernameValidator;
    @Mock private AgeValidator ageValidator;
    @Mock private OnboardingService onboardingService;
    @Mock private OrganizationService organizationService;
    @Mock private CreditService creditService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private PlanRepository planRepository;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private PlanResolutionService planResolutionService;
    @Mock private AuthEventRecorder authEventRecorder;

    private UserResolutionService service;

    @BeforeEach
    void setUp() {
        service = new UserResolutionService(
                userRepository, creditService, usernameValidator, ageValidator,
                onboardingService, organizationService,
                subscriptionRepository, billingCustomerRepository, planRepository,
                creditAttributionService, new PlanStorageQuotaSyncer(null, null));
        ReflectionTestUtils.setField(service, "self", service);
        // CRITICAL - set the @Autowired(required=false) field so the
        // dual-write branch in buildResolutionResponse fires.
        ReflectionTestUtils.setField(service, "planResolutionService", planResolutionService);
        lenient().when(onboardingService.needsOnboarding(any())).thenReturn(false);
        lenient().when(organizationService.listUserMembershipsDto(anyLong()))
                .thenReturn(Collections.emptyList());
    }

    private UserResolutionResponse invokeBuild(User user) throws Exception {
        Method m = UserResolutionService.class.getDeclaredMethod(
                "buildResolutionResponse", User.class, String.class);
        m.setAccessible(true);
        return (UserResolutionResponse) m.invoke(service, user, user.getProviderId());
    }

    private User testUser() {
        User u = new User("u", "u@test.com", AuthProvider.KEYCLOAK, "kc-uuid-1");
        u.setId(42L);
        u.setUserVersion(1L);
        u.setEnabled(true);
        return u;
    }

    @Test
    @DisplayName("dual-write fires: billingPlan + activeOrgPlan populated when service wired")
    void dualWriteFiresWhenServiceWired() throws Exception {
        when(planResolutionService.resolveBillingPlan(42L)).thenReturn("PRO");
        when(planResolutionService.resolveActiveOrgTier(42L)).thenReturn("TEAM");

        UserResolutionResponse resp = invokeBuild(testUser());

        // PR7 cutover prerequisite: BOTH fields populated, distinct values
        // round-trip end-to-end (DTO ctor → setter → JSON-able field).
        assertThat(resp.getBillingPlan())
                .as("user's own subscription - what Stripe charges (per Q5=a)")
                .isEqualTo("PRO");
        assertThat(resp.getActiveOrgPlan())
                .as("active workspace tier - what FE plan badge should show (per Q1=b)")
                .isEqualTo("TEAM");
        // Post-cutover: `plan` follows activeOrgPlan (gateway X-User-Plan reflects workspace).
        assertThat(resp.getPlan()).isEqualTo("TEAM");
    }

    @Test
    @DisplayName("dual-write fails-soft: if PlanResolutionService throws, billingPlan and "
            + "activeOrgPlan stay null but resolve() still returns a valid response")
    void dualWriteFailsSoftOnException() throws Exception {
        when(planResolutionService.resolveBillingPlan(42L))
                .thenThrow(new RuntimeException("planResolution down"));

        UserResolutionResponse resp = invokeBuild(testUser());

        // Resp is built; new fields are null (per the try/catch around the
        // dual-write block). Hot path stays green - gateway falls back to
        // the legacy `plan` field.
        assertThat(resp).isNotNull();
        assertThat(resp.getBillingPlan()).isNull();
        assertThat(resp.getActiveOrgPlan()).isNull();
        assertThat(resp.getPlan()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("backward compat: when planResolutionService is NOT wired (legacy boot), "
            + "buildResolutionResponse still works - billingPlan/activeOrgPlan stay null")
    void noWireBackwardCompat() throws Exception {
        // Simulate legacy boot - drop the optional service.
        ReflectionTestUtils.setField(service, "planResolutionService", null);

        UserResolutionResponse resp = invokeBuild(testUser());

        assertThat(resp).isNotNull();
        assertThat(resp.getBillingPlan()).isNull();
        assertThat(resp.getActiveOrgPlan()).isNull();
        assertThat(resp.getPlan()).isEqualTo("FREE"); // legacy code path
    }

    @Test
    @DisplayName("plan field is overridden with activeOrgPlan (gateway X-User-Plan reflects workspace, Q1=b)")
    void activeOrgPlanOverridesLegacyPlan() throws Exception {
        when(planResolutionService.resolveBillingPlan(42L)).thenReturn("FREE");
        when(planResolutionService.resolveActiveOrgTier(42L)).thenReturn("TEAM");

        UserResolutionResponse resp = invokeBuild(testUser());

        // plan field = activeOrgPlan (the override), NOT billingPlan. Pre-cutover
        // this would have been "FREE"; post-cutover it's "TEAM" because the user
        // is now in a TEAM workspace.
        assertThat(resp.getPlan()).isEqualTo("TEAM");
        assertThat(resp.getActiveOrgPlan()).isEqualTo("TEAM");
        assertThat(resp.getBillingPlan()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("null activeOrgPlan → plan stays unchanged (defensive - never NPE the X-User-Plan header)")
    void overrideSkippedWhenActiveOrgIsNull() throws Exception {
        when(planResolutionService.resolveBillingPlan(42L)).thenReturn("PRO");
        when(planResolutionService.resolveActiveOrgTier(42L)).thenReturn(null);

        UserResolutionResponse resp = invokeBuild(testUser());

        // Override skipped because activeOrgPlan is null → keep the legacy
        // `plan` value (FREE, from the upstream subscriptionRepository
        // resolution at the top of buildResolutionResponse).
        assertThat(resp.getPlan()).isEqualTo("FREE");
    }
}
