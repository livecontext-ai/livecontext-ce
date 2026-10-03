package com.apimarketplace.publication.service;

import com.apimarketplace.agent.cloud.CloudLlmSource;
import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Any cloud plan may link a self-hosted install, but every CLOUD source is a paid relay: the CE
 * must never switch an unpaid account's install to CLOUD (the relay would refuse every call and
 * the chat it had on its own keys would break), and it must handle the cloud's
 * 403 CLOUD_LINK_ONBOARDING_REQUIRED on register as a known, retried refusal.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLinkService - a FREE link stays on BYOK, CLOUD needs a paid plan, onboarding refusal handled")
class CloudLinkServiceFreeLinkTest {

    @Mock private CeCloudLinkRepository cloudLinkRepository;
    @Mock private RestTemplate restTemplate;

    private static final Long TENANT_ID = 42L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CLOUD_API = "https://livecontext.ai/api";
    private static final String REGISTER_URL = CLOUD_API + "/ce-link/register";
    private static final String ENTITLEMENTS_URL = CLOUD_API + "/ce-link/" + INSTALL + "/entitlements";
    private static final String ONBOARDING_REQUIRED_BODY =
            "{\"error\":\"CLOUD_LINK_ONBOARDING_REQUIRED\",\"message\":\"Finish setting up your account.\"}";

    private final AtomicReference<CeCloudLinkEntity> stored = new AtomicReference<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("UTC"));
    private CloudLinkService service;

    @BeforeEach
    void setUp() {
        service = new CloudLinkService(cloudLinkRepository,
                "https://kc.example.com/realms/test", "ce-link", "http://localhost/callback",
                "test-encryption-key-for-unit-tests", CLOUD_API, "1.4.0-test", new ObjectMapper(),
                restTemplate, clock);
        CloudLinkTokenColumnsFake.backByStubbedEntity(cloudLinkRepository);
        lenient().when(cloudLinkRepository.findByTenantId(TENANT_ID))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        lenient().when(cloudLinkRepository.save(any())).thenAnswer(inv -> {
            CeCloudLinkEntity link = inv.getArgument(0);
            stored.set(link);
            return link;
        });
    }

    private CeCloudLinkEntity link(boolean registered) {
        CeCloudLinkEntity link = new CeCloudLinkEntity();
        link.setTenantId(TENANT_ID);
        link.setCloudUserId("cloud-user");
        link.setCloudUsername("owner");
        link.setEncryptedRefreshToken("encrypted-refresh");
        link.setCachedAccessToken("bearer-abc");
        link.setTokenExpiresAt(clock.instant().plusSeconds(3600));
        link.setLinkedAt(clock.instant().minus(Duration.ofDays(1)));
        link.setInstallId(INSTALL);
        link.setRegisteredAt(registered ? clock.instant().minus(Duration.ofDays(1)) : null);
        link.setLlmSource(CloudLlmSource.BYOK.name());
        link.setCatalogSource(CloudLlmSource.BYOK.name());
        stored.set(link);
        return link;
    }

    private void cloudPlanIs(String planCode) throws Exception {
        JsonNode body = new ObjectMapper().readTree("{\"planCode\":\"" + planCode + "\"}");
        when(restTemplate.exchange(eq(ENTITLEMENTS_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    private void registerSucceeds() {
        when(restTemplate.postForEntity(eq(REGISTER_URL), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok().build());
    }

    private void registerRefusedOnboarding() {
        when(restTemplate.postForEntity(eq(REGISTER_URL), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                        ONBOARDING_REQUIRED_BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("auto-promotion to the cloud LLM source after register")
    class AutoPromotion {

        @Test
        @DisplayName("regression: a FREE account's install registers but its LLM source stays BYOK (the chat keeps its own keys)")
        void freeAccountStaysOnByok() throws Exception {
            CeCloudLinkEntity link = link(false);
            registerSucceeds();
            cloudPlanIs("FREE");

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.REGISTERED);
            assertThat(stored.get().getRegisteredAt()).isNotNull();
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("regression: __NONE__, what the real cloud answers for a FREE account, is not paid: stays BYOK")
        void noSubscriptionStaysOnByok() throws Exception {
            CeCloudLinkEntity link = link(false);
            registerSucceeds();
            cloudPlanIs("__NONE__");

            service.sendHeartbeat(link);

            assertThat(stored.get().getRegisteredAt()).isNotNull();
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("an unknown cloud plan (entitlements unreachable, nothing cached) is not paid: stays BYOK")
        void unknownPlanStaysOnByok() {
            CeCloudLinkEntity link = link(false);
            registerSucceeds();
            when(restTemplate.exchange(eq(ENTITLEMENTS_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

            service.sendHeartbeat(link);

            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("a paid account's install is promoted to CLOUD as before")
        void paidAccountIsPromoted() throws Exception {
            CeCloudLinkEntity link = link(false);
            registerSucceeds();
            cloudPlanIs("PRO");

            service.sendHeartbeat(link);

            assertThat(stored.get().getLlmSource()).isEqualTo("CLOUD");
        }
    }

    @Nested
    @DisplayName("selecting a CLOUD source")
    class SelectCloud {

        @Test
        @DisplayName("regression: a FREE account cannot switch the LLM source to CLOUD: plan-required refusal, nothing saved")
        void freeAccountCannotSelectCloudLlm() throws Exception {
            link(true);
            cloudPlanIs("FREE");

            assertThatThrownBy(() -> service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOfSatisfying(CloudLinkService.CloudLinkPlanRequiredException.class,
                            e -> assertThat(e.getPlanCode()).isEqualTo("FREE"));
            // Reading the token stamps lastUsedAt (a save), but CLOUD is never persisted.
            verify(cloudLinkRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
                    l -> "CLOUD".equals(l.getLlmSource()) || "CLOUD".equals(l.getCatalogSource())));
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("regression: __NONE__ (the real cloud's FREE answer) is refused too, and the refusal names FREE, not __NONE__")
        void noSubscriptionIsRefusedAsFree() throws Exception {
            link(true);
            cloudPlanIs("__NONE__");

            assertThatThrownBy(() -> service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOfSatisfying(CloudLinkService.CloudLinkPlanRequiredException.class,
                            e -> assertThat(e.getPlanCode()).isEqualTo("FREE"));
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("a FREE account cannot switch the catalog source to CLOUD either")
        void freeAccountCannotSelectCloudCatalog() throws Exception {
            link(true);
            cloudPlanIs("FREE");

            assertThatThrownBy(() -> service.setCatalogSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(CloudLinkService.CloudLinkPlanRequiredException.class);
            // Reading the token stamps lastUsedAt (a save), but CLOUD is never persisted.
            verify(cloudLinkRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
                    l -> "CLOUD".equals(l.getLlmSource()) || "CLOUD".equals(l.getCatalogSource())));
        }

        @Test
        @DisplayName("regression: a cloud outage with nothing cached is NOT_READY (409), never reported as plan required")
        void outageIsNotReadyNotPlanRequired() {
            link(true);
            when(restTemplate.exchange(eq(ENTITLEMENTS_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

            assertThatThrownBy(() -> service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CloudLinkService.CloudLinkPlanRequiredException.class);
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("a cloud outage serves the last known-good plan: a paid account can still select CLOUD")
        void outageWithCachedPaidPlanSelectsCloud() throws Exception {
            link(true);
            JsonNode paid = new ObjectMapper().readTree("{\"planCode\":\"PRO\"}");
            when(restTemplate.exchange(eq(ENTITLEMENTS_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(paid))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));
            service.fetchCloudEntitlement(stored.get()); // caches PRO as last known-good

            assertThat(service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD)).isEqualTo(CloudLlmSource.CLOUD);
            assertThat(stored.get().getLlmSource()).isEqualTo("CLOUD");
        }

        @Test
        @DisplayName("switching back to BYOK never looks at the plan")
        void byokNeedsNoPlan() {
            CeCloudLinkEntity link = link(true);
            link.setLlmSource(CloudLlmSource.CLOUD.name());

            assertThat(service.setLlmSource(TENANT_ID, CloudLlmSource.BYOK)).isEqualTo(CloudLlmSource.BYOK);
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
            verify(restTemplate, never()).exchange(eq(ENTITLEMENTS_URL), any(HttpMethod.class), any(HttpEntity.class),
                    eq(JsonNode.class));
        }
    }

    @Nested
    @DisplayName("403 CLOUD_LINK_ONBOARDING_REQUIRED on register")
    class OnboardingRequired {

        @Test
        @DisplayName("regression: register answers ONBOARDING_REQUIRED instead of throwing, and stamps nothing")
        void registerReportsOnboardingRequired() {
            CeCloudLinkEntity link = link(false);
            registerRefusedOnboarding();

            assertThat(service.registerWithCloud(link))
                    .isEqualTo(CloudLinkService.RegisterOutcome.ONBOARDING_REQUIRED);
            assertThat(stored.get().getRegisteredAt()).isNull();
            assertThat(stored.get().getPlanRequiredAt()).isNull();
        }

        @Test
        @DisplayName("the heartbeat tick keeps it pending (retried on the next tick), never registered nor suspended")
        void heartbeatStaysPending() {
            CeCloudLinkEntity link = link(false);
            registerRefusedOnboarding();

            assertThat(service.sendHeartbeat(link)).isEqualTo(CloudLinkService.HeartbeatOutcome.PENDING_REGISTER);
            assertThat(stored.get().getRegisteredAt()).isNull();
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("regression: selecting CLOUD on such a link is a typed onboarding refusal (was an unhandled 403, a 500)")
        void selectingCloudIsATypedRefusal() {
            link(false);
            registerRefusedOnboarding();

            assertThatThrownBy(() -> service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(CloudLinkService.CloudLinkOnboardingRequiredException.class)
                    .hasMessageStartingWith("CLOUD_LINK_ONBOARDING_REQUIRED");
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }
    }
}
