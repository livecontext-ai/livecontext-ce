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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A CE link whose cloud account is not on a paid plan is SUSPENDED, never revoked: the cloud answers
 * 403 CLOUD_LINK_PLAN_REQUIRED on register and heartbeat, the CE records it (plan_required_at + plan
 * code) and keeps everything else, and the next 2xx lifts it so paying again restores the link.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLinkService - 403 CLOUD_LINK_PLAN_REQUIRED suspends the link, a 2xx restores it")
class CloudLinkServicePlanRequiredTest {

    @Mock private CeCloudLinkRepository cloudLinkRepository;
    @Mock private RestTemplate restTemplate;

    private static final Long TENANT_ID = 42L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CLOUD_API = "https://livecontext.ai/api";
    private static final String REGISTER_URL = CLOUD_API + "/ce-link/register";
    private static final String HEARTBEAT_URL = CLOUD_API + "/ce-link/" + INSTALL + "/heartbeat";
    private static final String PLAN_REQUIRED_BODY = "{\"error\":\"CLOUD_LINK_PLAN_REQUIRED\",\"planCode\":\"FREE\","
            + "\"message\":\"Linking a self-hosted install to LiveContext Cloud requires a paid plan.\"}";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-25T10:00:00Z"));
    private final AtomicReference<CeCloudLinkEntity> stored = new AtomicReference<>();
    private CloudLinkService service;

    @BeforeEach
    void setUp() {
        service = new CloudLinkService(cloudLinkRepository,
                "https://kc.example.com/realms/test", "ce-link", "http://localhost/callback",
                "test-encryption-key-for-unit-tests", CLOUD_API, "1.4.0-test", new ObjectMapper(),
                restTemplate, clock);
        // Stateful repository: every read returns what was last saved, like the real row.
        lenient().when(cloudLinkRepository.findByTenantId(TENANT_ID))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        lenient().when(cloudLinkRepository.save(any())).thenAnswer(inv -> {
            CeCloudLinkEntity link = inv.getArgument(0);
            stored.set(link);
            return link;
        });
    }

    private CeCloudLinkEntity link(boolean registered, CloudLlmSource source) {
        CeCloudLinkEntity link = new CeCloudLinkEntity();
        link.setTenantId(TENANT_ID);
        link.setCloudUserId("cloud-user");
        link.setCloudUsername("owner");
        link.setEncryptedRefreshToken("encrypted-refresh");
        link.setCachedAccessToken("bearer-abc");
        link.setTokenExpiresAt(clock.instant().plusSeconds(3600));
        link.setLinkedAt(clock.instant().minus(Duration.ofDays(3)));
        link.setInstallId(INSTALL);
        link.setRegisteredAt(registered ? clock.instant().minus(Duration.ofDays(3)) : null);
        link.setLlmSource(source.name());
        link.setCatalogSource(source.name());
        stored.set(link);
        return link;
    }

    private static HttpClientErrorException forbidden(String body) {
        return HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private void registerAnswers(RuntimeException refusal) {
        when(restTemplate.postForEntity(eq(REGISTER_URL), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(refusal);
    }

    private void heartbeatAnswers(RuntimeException refusal) {
        when(restTemplate.postForEntity(eq(HEARTBEAT_URL), any(HttpEntity.class), eq(Void.class)))
                .thenThrow(refusal);
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("403 CLOUD_LINK_PLAN_REQUIRED -> PLAN_REQUIRED: not registered (unlike 409), plan code recorded, tokens kept")
        void planRequiredIsRecordedAndNotTreatedLikeAlreadyBound() {
            CeCloudLinkEntity link = link(false, CloudLlmSource.BYOK);
            registerAnswers(forbidden(PLAN_REQUIRED_BODY));

            CloudLinkService.RegisterOutcome outcome = service.registerWithCloud(link);

            assertThat(outcome).isEqualTo(CloudLinkService.RegisterOutcome.PLAN_REQUIRED);
            CeCloudLinkEntity row = stored.get();
            assertThat(row.getRegisteredAt()).isNull();
            assertThat(row.getPlanRequiredAt()).isEqualTo(clock.instant());
            assertThat(row.getPlanRequiredPlanCode()).isEqualTo("FREE");
            assertThat(row.getCachedAccessToken()).isEqualTo("bearer-abc");
            assertThat(row.getEncryptedRefreshToken()).isEqualTo("encrypted-refresh");
            // Mirrored onto the caller's instance too.
            assertThat(link.getPlanRequiredPlanCode()).isEqualTo("FREE");
        }

        @Test
        @DisplayName("A non-JSON 403 body that still names CLOUD_LINK_PLAN_REQUIRED counts, with no plan code")
        void nonJsonBodyWithTokenCounts() {
            CeCloudLinkEntity link = link(false, CloudLlmSource.BYOK);
            registerAnswers(forbidden("CLOUD_LINK_PLAN_REQUIRED"));

            assertThat(service.registerWithCloud(link)).isEqualTo(CloudLinkService.RegisterOutcome.PLAN_REQUIRED);
            assertThat(stored.get().getPlanRequiredAt()).isNotNull();
            assertThat(stored.get().getPlanRequiredPlanCode()).isNull();
        }

        @Test
        @DisplayName("Any other 403 is not a plan refusal: it propagates and records nothing")
        void otherForbiddenPropagates() {
            CeCloudLinkEntity link = link(false, CloudLlmSource.BYOK);
            registerAnswers(forbidden("{\"error\":\"CE_LINK_NOT_ACTIVE\"}"));

            assertThatThrownBy(() -> service.registerWithCloud(link))
                    .isInstanceOf(HttpClientErrorException.Forbidden.class);
            assertThat(stored.get().getPlanRequiredAt()).isNull();
            assertThat(stored.get().getRegisteredAt()).isNull();
        }

        @Test
        @DisplayName("A 2xx register clears a previous plan-required suspension")
        void successfulRegisterClearsSuspension() {
            CeCloudLinkEntity link = link(false, CloudLlmSource.BYOK);
            link.setPlanRequiredAt(clock.instant().minus(Duration.ofDays(1)));
            link.setPlanRequiredPlanCode("FREE");
            when(restTemplate.postForEntity(eq(REGISTER_URL), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenReturn(ResponseEntity.ok().build());

            assertThat(service.registerWithCloud(link)).isEqualTo(CloudLinkService.RegisterOutcome.REGISTERED);
            assertThat(stored.get().getRegisteredAt()).isEqualTo(clock.instant());
            assertThat(stored.get().getPlanRequiredAt()).isNull();
            assertThat(stored.get().getPlanRequiredPlanCode()).isNull();
        }

        @Test
        @DisplayName("Choosing CLOUD while the account needs a paid plan fails with the plan code, not CLOUD_LINK_NOT_READY")
        void setLlmSourceSurfacesPlanRequired() {
            link(false, CloudLlmSource.BYOK);
            registerAnswers(forbidden(PLAN_REQUIRED_BODY));

            assertThatThrownBy(() -> service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(CloudLinkService.CloudLinkPlanRequiredException.class)
                    .hasMessageStartingWith("CLOUD_LINK_PLAN_REQUIRED")
                    .extracting(e -> ((CloudLinkService.CloudLinkPlanRequiredException) e).getPlanCode())
                    .isEqualTo("FREE");
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("Same for the catalog toggle")
        void setCatalogSourceSurfacesPlanRequired() {
            link(false, CloudLlmSource.BYOK);
            registerAnswers(forbidden(PLAN_REQUIRED_BODY));

            assertThatThrownBy(() -> service.setCatalogSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(CloudLinkService.CloudLinkPlanRequiredException.class);
            assertThat(stored.get().getCatalogSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("No hot retry: a suspended unregistered CLOUD link does not re-POST register on every runtime resolution")
        void runtimeResolutionDoesNotRetryRegisterWhileSuspended() {
            CeCloudLinkEntity link = link(false, CloudLlmSource.CLOUD);
            link.setPlanRequiredAt(clock.instant());
            link.setPlanRequiredPlanCode("FREE");

            CloudLinkService.CloudRuntimeStatus llm = service.getCloudRuntimeStatus(TENANT_ID);
            CloudLinkService.CloudRuntimeStatus catalog = service.getCatalogRuntimeStatus(TENANT_ID);

            assertThat(llm.cloudReady()).isFalse();
            assertThat(llm.source()).isEqualTo(CloudLlmSource.CLOUD);
            assertThat(catalog.cloudReady()).isFalse();
            verifyNoInteractions(restTemplate);
        }
    }

    @Nested
    @DisplayName("heartbeat")
    class Heartbeat {

        @Test
        @DisplayName("403 CLOUD_LINK_PLAN_REQUIRED -> PLAN_REQUIRED, link kept (registration, tokens, CLOUD sources); then a 2xx clears it")
        void planRequiredSuspendsThenTwoXxRestores() {
            CeCloudLinkEntity link = link(true, CloudLlmSource.CLOUD);
            Instant registeredAt = link.getRegisteredAt();
            when(restTemplate.postForEntity(eq(HEARTBEAT_URL), any(HttpEntity.class), eq(Void.class)))
                    .thenThrow(forbidden(PLAN_REQUIRED_BODY))
                    .thenThrow(forbidden(PLAN_REQUIRED_BODY))
                    .thenReturn(ResponseEntity.noContent().build());

            assertThat(service.sendHeartbeat(link)).isEqualTo(CloudLinkService.HeartbeatOutcome.PLAN_REQUIRED);
            CeCloudLinkEntity suspended = stored.get();
            Instant since = suspended.getPlanRequiredAt();
            assertThat(since).isEqualTo(clock.instant());
            assertThat(suspended.getPlanRequiredPlanCode()).isEqualTo("FREE");
            assertThat(suspended.getRegisteredAt()).isEqualTo(registeredAt);
            assertThat(suspended.getCachedAccessToken()).isEqualTo("bearer-abc");
            assertThat(suspended.getLlmSource()).isEqualTo("CLOUD");
            assertThat(suspended.getCatalogSource()).isEqualTo("CLOUD");

            // A second refusal keeps the time the suspension STARTED.
            clock.advance(Duration.ofMinutes(5));
            assertThat(service.sendHeartbeat(link)).isEqualTo(CloudLinkService.HeartbeatOutcome.PLAN_REQUIRED);
            assertThat(stored.get().getPlanRequiredAt()).isEqualTo(since);

            // The owner paid: the next heartbeat succeeds and lifts the suspension, nothing to re-link.
            clock.advance(Duration.ofMinutes(5));
            assertThat(service.sendHeartbeat(link)).isEqualTo(CloudLinkService.HeartbeatOutcome.OK);
            assertThat(stored.get().getPlanRequiredAt()).isNull();
            assertThat(stored.get().getPlanRequiredPlanCode()).isNull();
            assertThat(stored.get().getRegisteredAt()).isEqualTo(registeredAt);
            assertThat(stored.get().getLlmSource()).isEqualTo("CLOUD");
            assertThat(link.getPlanRequiredAt()).isNull();
        }

        @Test
        @DisplayName("Any other 403 on heartbeat stays a TRANSIENT_FAILURE and records no suspension")
        void otherForbiddenStaysTransient() {
            CeCloudLinkEntity link = link(true, CloudLlmSource.CLOUD);
            heartbeatAnswers(forbidden("{\"error\":\"CE_LINK_NOT_ACTIVE\"}"));

            assertThat(service.sendHeartbeat(link)).isEqualTo(CloudLinkService.HeartbeatOutcome.TRANSIENT_FAILURE);
            assertThat(stored.get().getPlanRequiredAt()).isNull();
            assertThat(stored.get().getRegisteredAt()).isNotNull();
        }

        @Test
        @DisplayName("An unregistered link whose register is refused for the plan reports PLAN_REQUIRED and is not promoted to CLOUD")
        void pendingRegisterRefusedForPlan() {
            link(false, CloudLlmSource.BYOK);
            registerAnswers(forbidden(PLAN_REQUIRED_BODY));

            assertThat(service.sendHeartbeat(stored.get())).isEqualTo(CloudLinkService.HeartbeatOutcome.PLAN_REQUIRED);
            assertThat(stored.get().getLlmSource()).isEqualTo("BYOK");
            assertThat(stored.get().getRegisteredAt()).isNull();
            verify(restTemplate, never()).postForEntity(anyString(), any(HttpEntity.class), eq(Void.class));
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("A suspended link reports planRequired=true with the plan code, and stays linked + registered")
        void suspendedLinkReportsPlanRequired() {
            CeCloudLinkEntity link = link(true, CloudLlmSource.BYOK);
            link.setPlanRequiredAt(clock.instant());
            link.setPlanRequiredPlanCode("FREE");

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("planRequired", true)
                    .containsEntry("planRequiredPlanCode", "FREE")
                    .containsEntry("linked", true)
                    .containsEntry("registered", true);
        }

        @Test
        @DisplayName("A healthy link reports planRequired=false and a null plan code")
        void healthyLinkReportsNoSuspension() {
            link(true, CloudLlmSource.BYOK);

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("planRequired", false)
                    .containsEntry("planRequiredPlanCode", null);
        }

        @Test
        @DisplayName("No link reports planRequired=false")
        void noLinkReportsNoSuspension() {
            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("linked", false)
                    .containsEntry("planRequired", false)
                    .containsEntry("planRequiredPlanCode", null);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
