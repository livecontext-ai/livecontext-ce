package com.apimarketplace.auth.analytics;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The identity / persona / plan events: what lands on the PostHog person, and
 * what must never leave the server (display name, email, free text).
 */
class AuthAnalyticsEmitterTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> props, String key) {
        return (Map<String, Object>) props.get(key);
    }

    private static AuthAnalyticsEmitter active(PostHogAnalyticsClient client) {
        when(client.isActive()).thenReturn(true);
        AuthAnalyticsEmitter emitter = new AuthAnalyticsEmitter();
        ReflectionTestUtils.setField(emitter, "postHog", client);
        return emitter;
    }

    @Nested
    @DisplayName("auth_registered / auth_login_succeeded")
    class Identity {
        @Test
        @DisplayName("registration stamps the signup method and date ONCE on the person")
        void registered() {
            Map<String, Object> p = AuthAnalyticsEmitter.buildRegisteredProps("google", false, NOW);
            assertEquals("google", p.get("method"));
            assertEquals(false, p.get("first_user"));
            assertEquals("backend", p.get("surface"));
            Map<String, Object> once = sub(p, "$set_once");
            assertEquals("google", once.get("signup_method"));
            assertEquals("2026-09-02T10:00:00Z", once.get("signup_at"));
            assertFalse(p.containsKey("$set"));
        }

        @Test
        @DisplayName("login updates the last-login person properties")
        void login() {
            Map<String, Object> p = AuthAnalyticsEmitter.buildLoginProps("keycloak", NOW);
            assertEquals("keycloak", p.get("method"));
            Map<String, Object> set = sub(p, "$set");
            assertEquals("keycloak", set.get("last_login_method"));
            assertEquals("2026-09-02T10:00:00Z", set.get("last_login_at"));
        }

        @Test
        @DisplayName("the distinct_id is the internal numeric user id, as a string")
        void distinctId() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            AuthAnalyticsEmitter emitter = active(client);
            emitter.registered(42L, "local", true);
            verify(client).capture(eq("42"), eq("auth_registered"), any());
            emitter.registered(null, "local", true);
            verify(client, never()).capture(eq("null"), anyString(), any());
        }
    }

    @Nested
    @DisplayName("onboarding_completed (the persona)")
    class Persona {
        private UserOnboarding onboarding() {
            User user = new User();
            UserOnboarding o = new UserOnboarding(user, "Jane Doe Display");
            o.setProfession("Head of Growth, jane@acme.com");
            o.setCompanySize("small");
            o.setExperienceLevel("intermediate");
            o.setInterests(List.of("automation", "my custom interest", "sales-crm"));
            o.setUseCases(List.of("lead-generation"));
            o.setPrimaryGoal("email-follow-ups");
            o.setToolsUsed(List.of("gmail", "hubspot", "Our CRM"));
            o.setPreviousTool("zapier-make");
            o.setReferralSource("github");
            return o;
        }

        @Test
        @DisplayName("declared persona is re-bucketed and set on the person; free text and display name never leave")
        void props() {
            Map<String, Object> p = AuthAnalyticsEmitter.buildOnboardingCompletedProps(onboarding(), "org-1", NOW);

            assertEquals("other", p.get("profession"));
            assertEquals("small", p.get("company_size"));
            assertEquals("intermediate", p.get("experience_level"));
            assertEquals(List.of("automation", "sales-crm", "other"), p.get("interests"));
            assertEquals(List.of("lead-generation"), p.get("use_cases"));
            assertEquals(3, p.get("interest_count"));
            assertEquals(1, p.get("custom_interest_count"));
            assertEquals(0, p.get("custom_use_case_count"));
            assertEquals("org-1", p.get("organization_id"));
            assertEquals(Map.of("organization", "org-1"), p.get("$groups"));

            assertEquals("email-follow-ups", p.get("primary_goal"));
            assertEquals(List.of("gmail", "hubspot", "other"), p.get("tools_used"));
            assertEquals(3, p.get("tools_count"));
            assertEquals("zapier-make", p.get("previous_tool"));
            assertEquals("github", p.get("referral_source"));

            Map<String, Object> set = sub(p, "$set");
            assertEquals("other", set.get("profession"));
            assertEquals(List.of("automation", "sales-crm", "other"), set.get("interests"));
            assertEquals("email-follow-ups", set.get("primary_goal"));
            assertEquals("zapier-make", set.get("previous_tool"));
            assertEquals(Map.of("referral_source_first", "github"), p.get("$set_once"));
            assertFalse(p.toString().contains("Our CRM"), "free-text tool leaked");
            assertEquals(true, set.get("onboarding_completed"));
            assertEquals("2026-09-02T10:00:00Z", set.get("onboarding_completed_at"));

            String flat = p.toString();
            assertFalse(flat.contains("Jane"), "display name leaked");
            assertFalse(flat.contains("acme.com"), "free-text profession leaked");
            assertFalse(flat.contains("custom interest"), "free-text interest leaked");
        }

        @Test
        @DisplayName("unanswered fields stay null rather than 'other'")
        void unanswered() {
            UserOnboarding o = new UserOnboarding(new User(), "x");
            Map<String, Object> p = AuthAnalyticsEmitter.buildOnboardingCompletedProps(o, null, NOW);
            assertNull(p.get("profession"));
            assertNull(p.get("company_size"));
            assertEquals(List.of(), p.get("interests"));
            assertNull(p.get("primary_goal"));
            assertEquals(List.of(), p.get("tools_used"));
            assertEquals(0, p.get("tools_count"));
            assertNull(p.get("previous_tool"));
            assertNull(p.get("referral_source"));
            assertFalse(p.containsKey("$set_once"), "no referral answered: nothing is stamped once");
            assertFalse(p.containsKey("organization_id"));
            assertFalse(p.containsKey("$groups"));
        }

        @Test
        @DisplayName("a skip marks the person and names the step")
        void skipped() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            AuthAnalyticsEmitter emitter = active(client);
            emitter.onboardingSkipped(5L, 2, "org-9");
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(client).capture(eq("5"), eq("onboarding_skipped"), captor.capture());
            assertEquals(2, captor.getValue().get("skipped_at_step"));
            assertEquals(true, sub(captor.getValue(), "$set").get("onboarding_skipped"));
            assertEquals("org-9", captor.getValue().get("organization_id"));
        }

        @Test
        @DisplayName("a step save without a step number emits nothing")
        void stepWithoutNumber() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            AuthAnalyticsEmitter emitter = active(client);
            emitter.onboardingStepSaved(5L, null);
            verify(client, never()).capture(anyString(), anyString(), any());
            emitter.onboardingStepSaved(5L, 1);
            verify(client).capture(eq("5"), eq("onboarding_step_completed"), any());
        }
    }

    @Nested
    @DisplayName("plan / money")
    class Plan {
        @Test
        @DisplayName("a subscription change sets plan_code on the person and says whether the plan moved")
        void changed() {
            Map<String, Object> p = AuthAnalyticsEmitter.buildSubscriptionChangedProps("FREE", "PRO", "active", 500, "stripe");
            assertEquals("FREE", p.get("previous_plan_code"));
            assertEquals("PRO", p.get("plan_code"));
            assertEquals(true, p.get("plan_changed"));
            assertEquals(500, p.get("credit_quantity"));
            assertEquals("stripe", p.get("source"));
            assertEquals("PRO", sub(p, "$set").get("plan_code"));
            assertEquals("active", sub(p, "$set").get("subscription_status"));

            Map<String, Object> same = AuthAnalyticsEmitter.buildSubscriptionChangedProps("PRO", "PRO", "active", 500, "stripe");
            assertEquals(false, same.get("plan_changed"));
        }

        @Test
        @DisplayName("cancellation drops the person back to FREE; a top-up and an admin grant are counted")
        void cancelTopupGrant() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            AuthAnalyticsEmitter emitter = active(client);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

            emitter.subscriptionCancelled(5L, "TEAM");
            verify(client).capture(eq("5"), eq("subscription_cancelled"), captor.capture());
            assertEquals("FREE", sub(captor.getValue(), "$set").get("plan_code"));
            assertEquals("TEAM", captor.getValue().get("plan_code"));

            emitter.creditsPurchased(5L, new BigDecimal("1000"), "medium");
            verify(client).capture(eq("5"), eq("credits_purchased"), captor.capture());
            assertEquals(new BigDecimal("1000"), captor.getValue().get("credits"));
            assertEquals("medium", captor.getValue().get("tier"));

            emitter.planGranted(5L, "FREE", "STARTER");
            verify(client).capture(eq("5"), eq("plan_granted"), captor.capture());
            assertEquals("admin", captor.getValue().get("source"));
            assertEquals("STARTER", sub(captor.getValue(), "$set").get("plan_code"));
        }
    }

    @Nested
    @DisplayName("organization")
    class Org {
        @Test
        @DisplayName("creation and invitation acceptance carry the org as property AND group, never its name")
        void orgEvents() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            AuthAnalyticsEmitter emitter = active(client);
            Organization org = new Organization("Jane's Workspace", "janes-workspace", true, new User());
            UUID id = UUID.randomUUID();
            org.setId(id);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

            emitter.organizationCreated(5L, org, true);
            verify(client).capture(eq("5"), eq("organization_created"), captor.capture());
            assertEquals(id.toString(), captor.getValue().get("organization_id"));
            assertEquals(true, captor.getValue().get("personal"));
            assertEquals(Map.of("organization", id.toString()), captor.getValue().get("$groups"));
            assertFalse(captor.getValue().toString().contains("Jane"));

            emitter.invitationAccepted(6L, org, OrganizationRole.ADMIN);
            verify(client).capture(eq("6"), eq("invitation_accepted"), captor.capture());
            assertEquals("ADMIN", captor.getValue().get("role"));
            assertTrue(captor.getValue().containsKey("organization_id"));

            emitter.organizationCreated(5L, null, true); // no org: nothing, no throw
        }
    }

    @Test
    @DisplayName("without an active client nothing is captured and nothing throws")
    void gating() {
        new AuthAnalyticsEmitter().registered(1L, "local", false);
        PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
        when(client.isActive()).thenReturn(false);
        AuthAnalyticsEmitter inactive = new AuthAnalyticsEmitter();
        ReflectionTestUtils.setField(inactive, "postHog", client);
        inactive.loginSucceeded(1L, "local");
        verify(client, never()).capture(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("isActive mirrors whether an event would be captured")
    void isActiveMirrorsGating() {
        assertFalse(new AuthAnalyticsEmitter().isActive());
        PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
        when(client.isActive()).thenReturn(false);
        AuthAnalyticsEmitter emitter = new AuthAnalyticsEmitter();
        ReflectionTestUtils.setField(emitter, "postHog", client);
        assertFalse(emitter.isActive());
        when(client.isActive()).thenReturn(true);
        assertTrue(emitter.isActive());
    }

    @Nested
    @DisplayName("credit_alert_sent / lifecycle_event_sent / marketing_consent_changed / sso_member_joined")
    class NotificationsAndSso {
        @SuppressWarnings("unchecked")
        private Map<String, Object> captured(PostHogAnalyticsClient client, String distinctId, String event) {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(client).capture(eq(distinctId), eq(event), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("credit_alert_sent: the level is lowercased and the payer's workspace is the group")
        void creditAlert() {
            Map<String, Object> p = AuthAnalyticsEmitter.buildCreditAlertSentProps("EXHAUSTED", "org-3");
            assertEquals("exhausted", p.get("level"));
            assertEquals("backend", p.get("surface"));
            assertEquals("org-3", p.get("organization_id"));
            assertEquals(Map.of("organization", "org-3"), p.get("$groups"));
            assertEquals("low", AuthAnalyticsEmitter.buildCreditAlertSentProps("LOW", null).get("level"));
            assertFalse(AuthAnalyticsEmitter.buildCreditAlertSentProps("LOW", null).containsKey("$groups"));

            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            active(client).creditAlertSent(12L, "org-3", "LOW");
            assertEquals("low", captured(client, "12", "credit_alert_sent").get("level"));
        }

        @Test
        @DisplayName("lifecycle_event_sent: only the event name and the outcome, never the payload")
        void lifecycleEvent() {
            Map<String, Object> p = AuthAnalyticsEmitter.buildLifecycleEventSentProps("user.signed_up", false);
            assertEquals("user.signed_up", p.get("lifecycle_event"));
            assertEquals(false, p.get("delivered"));
            assertEquals("backend", p.get("surface"));
            assertEquals(3, p.size(), "surface, lifecycle_event, delivered and nothing else");

            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            AuthAnalyticsEmitter emitter = active(client);
            emitter.lifecycleEventSent(7L, null, true);
            verify(client, never()).capture(anyString(), anyString(), any());
            emitter.lifecycleEventSent(7L, "recap.monthly", true);
            assertEquals(true, captured(client, "7", "lifecycle_event_sent").get("delivered"));
        }

        @Test
        @DisplayName("marketing_consent_changed carries the new value and no source (the backend cannot tell the pages apart)")
        void consent() {
            Map<String, Object> p = AuthAnalyticsEmitter.buildMarketingConsentChangedProps(true);
            assertEquals(true, p.get("consent"));
            assertFalse(p.containsKey("source"));

            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            active(client).marketingConsentChanged(9L, false);
            assertEquals(false, captured(client, "9", "marketing_consent_changed").get("consent"));
        }

        @Test
        @DisplayName("sso_member_joined: role only on joined, reason only on rejected, org as group")
        void sso() {
            Map<String, Object> joined = AuthAnalyticsEmitter.buildSsoMemberJoinedProps(
                    "org-1", "joined", "domain_not_verified", OrganizationRole.MEMBER);
            assertEquals("joined", joined.get("outcome"));
            assertEquals("member", joined.get("role"));
            assertFalse(joined.containsKey("reason"), "a reason on a join is noise");
            assertEquals(Map.of("organization", "org-1"), joined.get("$groups"));

            Map<String, Object> rejected = AuthAnalyticsEmitter.buildSsoMemberJoinedProps(
                    "org-1", "rejected", "member_limit", OrganizationRole.MEMBER);
            assertEquals("member_limit", rejected.get("reason"));
            assertFalse(rejected.containsKey("role"), "nobody was granted a role");

            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            active(client).ssoMemberJoined(42L, "org-1", "joined", null, OrganizationRole.MEMBER);
            assertEquals("member", captured(client, "42", "sso_member_joined").get("role"));
        }

        @Test
        @DisplayName("none of the new events is captured without an active client")
        void gatedAndNoUser() {
            PostHogAnalyticsClient client = mock(PostHogAnalyticsClient.class);
            when(client.isActive()).thenReturn(false);
            AuthAnalyticsEmitter inactive = new AuthAnalyticsEmitter();
            ReflectionTestUtils.setField(inactive, "postHog", client);
            inactive.creditAlertSent(1L, "o", "LOW");
            inactive.lifecycleEventSent(1L, "user.signed_up", true);
            inactive.marketingConsentChanged(1L, true);
            inactive.ssoMemberJoined(1L, "o", "joined", null, OrganizationRole.MEMBER);
            verify(client, never()).capture(anyString(), anyString(), any());

            PostHogAnalyticsClient on = mock(PostHogAnalyticsClient.class);
            AuthAnalyticsEmitter emitter = active(on);
            emitter.creditAlertSent(null, "o", "LOW");
            emitter.ssoMemberJoined(null, "o", "rejected", "plan_not_team", null);
            verify(on, never()).capture(anyString(), anyString(), any());
        }
    }
}
