package com.apimarketplace.publication.service;

import com.apimarketplace.agent.cloud.CloudLlmSource;
import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLinkService")
class CloudLinkServiceTest {

    @Mock
    private CeCloudLinkRepository cloudLinkRepository;
    @Mock
    private RestTemplate restTemplate;

    private CloudLinkService service;
    private MutableClock clock;

    private static final Long TENANT_ID = 42L;
    private static final String CLOUD_USER_ID = "cloud-user-123";
    private static final String CLOUD_USERNAME = "testuser";
    private static final String ENCRYPTION_KEY = "test-encryption-key-for-unit-tests";

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-05-22T10:00:00Z"));
        service = new CloudLinkService(
                cloudLinkRepository,
                "https://keycloak.example.com/realms/test",
                "test-client-id",
                "http://localhost:3000/callback",
                ENCRYPTION_KEY,
                "https://livecontext.ai/api",
                "1.4.0-test",
                new ObjectMapper(),
                restTemplate,
                clock
        );
    }

    @Nested
    @DisplayName("generateAuthUrl")
    class GenerateAuthUrl {

        @Test
        @DisplayName("Should return auth URL with PKCE parameters and state")
        void shouldReturnAuthUrlWithPkceAndState() {
            Map<String, String> result = service.generateAuthUrl();

            assertThat(result).containsKey("authUrl");
            assertThat(result).containsKey("state");
            assertThat(result.get("authUrl")).contains("keycloak.example.com");
            assertThat(result.get("authUrl")).contains("client_id=test-client-id");
            assertThat(result.get("authUrl")).contains("code_challenge=");
            assertThat(result.get("authUrl")).contains("code_challenge_method=S256");
            assertThat(result.get("authUrl")).contains("response_type=code");
            assertThat(result.get("authUrl")).contains("scope=openid");
            assertThat(result.get("state")).isNotBlank();
        }

        @Test
        @DisplayName("Should generate unique states for each call")
        void shouldGenerateUniqueStates() {
            Map<String, String> result1 = service.generateAuthUrl();
            Map<String, String> result2 = service.generateAuthUrl();

            assertThat(result1.get("state")).isNotEqualTo(result2.get("state"));
        }

        @Test
        @DisplayName("Should bind generated state to tenant when tenant id is provided")
        void shouldBindGeneratedStateToTenant() {
            Map<String, String> result = service.generateAuthUrl(TENANT_ID);

            assertThat(result.get("state")).isNotBlank();
        }

        @Test
        @DisplayName("Should bind onboarding return path to generated callback state")
        void shouldBindOnboardingReturnPathToGeneratedCallbackState() {
            String state = service.generateAuthUrl(TENANT_ID, "/en/ce-setup").get("state");

            String returnPath = service.receiveCallback("callback-code-123", state);

            assertThat(returnPath).isEqualTo("/en/ce-setup");
        }

        @Test
        @DisplayName("Should accept the marketplace return path so a CE returns there after linking")
        void shouldAcceptMarketplaceReturnPath() {
            // Locale-prefixed form - what the marketplace connect CTA sends.
            String localeState = service.generateAuthUrl(TENANT_ID, "/en/app/marketplace").get("state");
            assertThat(service.receiveCallback("callback-code-123", localeState))
                    .isEqualTo("/en/app/marketplace");

            // Bare form - parity with the default cloud-account callback path.
            String bareState = service.generateAuthUrl(TENANT_ID, "/app/marketplace").get("state");
            assertThat(service.receiveCallback("callback-code-456", bareState))
                    .isEqualTo("/app/marketplace");
        }

        @Test
        @DisplayName("Should reject external frontend return paths")
        void shouldRejectExternalFrontendReturnPaths() {
            assertThatThrownBy(() -> service.generateAuthUrl(TENANT_ID, "https://evil.example/callback"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid cloud-link return path");
        }

        @Test
        @DisplayName("Should reject unsupported frontend return paths")
        void shouldRejectUnsupportedFrontendReturnPaths() {
            assertThatThrownBy(() -> service.generateAuthUrl(TENANT_ID, "/app/settings/profile"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported cloud-link return path");

            // Near-misses of the marketplace allowlist entries must still be rejected:
            // the regex is anchored, so no sub-path and no suffix slips through.
            assertThatThrownBy(() -> service.generateAuthUrl(TENANT_ID, "/app/marketplace/foo"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported cloud-link return path");
            assertThatThrownBy(() -> service.generateAuthUrl(TENANT_ID, "/en/app/marketplaceX"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported cloud-link return path");
        }
    }

    @Nested
    @DisplayName("linkAccount")
    class LinkAccount {

        @Test
        @DisplayName("Should reject invalid state parameter")
        void shouldRejectInvalidState() {
            assertThatThrownBy(() -> service.linkAccount(TENANT_ID, "invalid-state"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid or expired state");
        }

        @Test
        @DisplayName("Should exchange server-side callback code when connect omits authCode")
        @SuppressWarnings("unchecked")
        void shouldExchangeServerSideCallbackCodeWhenConnectOmitsAuthCode() {
            String state = service.generateAuthUrl(TENANT_ID).get("state");
            service.receiveCallback("callback-code-123", state);
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.exchange(
                    eq("https://keycloak.example.com/realms/test/protocol/openid-connect/token"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenAnswer(invocation -> {
                        HttpEntity<MultiValueMap<String, String>> entity = invocation.getArgument(2);
                        assertThat(entity.getBody().getFirst("code")).isEqualTo("callback-code-123");
                        assertThat(entity.getBody().getFirst("code_verifier")).isNotBlank();
                        return ResponseEntity.ok(Map.of(
                                "access_token", jwtWithClaims(CLOUD_USER_ID, CLOUD_USERNAME),
                                "refresh_token", "refresh-token-123",
                                "expires_in", 300
                        ));
                    });

            service.linkAccount(TENANT_ID, state);

            ArgumentCaptor<CeCloudLinkEntity> saved = ArgumentCaptor.forClass(CeCloudLinkEntity.class);
            verify(cloudLinkRepository, atLeastOnce()).save(saved.capture());
            assertThat(saved.getAllValues().get(0).getCloudUserId()).isEqualTo(CLOUD_USER_ID);
            assertThat(saved.getAllValues().get(0).getCloudUsername()).isEqualTo(CLOUD_USERNAME);
            assertThat(saved.getAllValues().get(0).getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("Should select Cloud only after cloud registration succeeds")
        @SuppressWarnings("unchecked")
        void shouldSelectCloudOnlyAfterRegistrationSucceeds() {
            String state = service.generateAuthUrl(TENANT_ID).get("state");
            service.receiveCallback("callback-code-123", state);
            AtomicReference<CeCloudLinkEntity> stored = new AtomicReference<>();
            when(cloudLinkRepository.findByTenantId(TENANT_ID))
                    .thenAnswer(inv -> Optional.ofNullable(stored.get()));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> {
                CeCloudLinkEntity link = inv.getArgument(0);
                if (link.getInstallId() == null) {
                    link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
                }
                stored.set(link);
                return link;
            });
            when(restTemplate.exchange(
                    eq("https://keycloak.example.com/realms/test/protocol/openid-connect/token"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            "access_token", jwtWithClaims(CLOUD_USER_ID, CLOUD_USERNAME),
                            "refresh_token", "refresh-token-123",
                            "expires_in", 300
                    )));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok().build());

            service.linkAccount(TENANT_ID, state);

            assertThat(stored.get()).isNotNull();
            assertThat(stored.get().getRegisteredAt()).isNotNull();
            assertThat(stored.get().getLlmSource()).isEqualTo("CLOUD");
        }

        @Test
        @DisplayName("Should reject callback completion from a different tenant without consuming the state")
        void shouldRejectDifferentTenantForCallbackState() {
            String state = service.generateAuthUrl(TENANT_ID).get("state");
            service.receiveCallback("callback-code-123", state);

            assertThatThrownBy(() -> service.linkAccount(99L, state))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should reject connect before backend callback stores the auth code")
        void shouldRejectConnectBeforeCallbackStoresCode() {
            String state = service.generateAuthUrl(TENANT_ID).get("state");

            assertThatThrownBy(() -> service.linkAccount(TENANT_ID, state))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("callback has not completed");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should reject duplicate backend callback code for the same state")
        void shouldRejectDuplicateBackendCallbackCodeForSameState() {
            String state = service.generateAuthUrl(TENANT_ID).get("state");
            service.receiveCallback("callback-code-123", state);

            assertThatThrownBy(() -> service.receiveCallback("replacement-code-456", state))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("callback already completed");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Should reject expired pending callback state")
        void shouldRejectExpiredPendingCallbackState() {
            String state = service.generateAuthUrl(TENANT_ID).get("state");
            service.receiveCallback("callback-code-123", state);
            clock.advance(Duration.ofMinutes(31));

            assertThatThrownBy(() -> service.linkAccount(TENANT_ID, state))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid or expired state");
            verifyNoInteractions(restTemplate);
        }
    }

    @Nested
    @DisplayName("getLinkStatus")
    class GetLinkStatus {

        @Test
        @DisplayName("Should return linked=false when no link exists")
        void shouldReturnUnlinkedWhenNoLink() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("linked", false);
            assertThat(status).containsEntry("registered", false);
            assertThat(status).doesNotContainKey("cloudUsername");
        }

        @Test
        @DisplayName("Should return linked status with username when link exists")
        void shouldReturnLinkedStatusWithUsername() {
            CeCloudLinkEntity link = buildLink();
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("linked", true);
            assertThat(status).containsEntry("registered", false);
            assertThat(status).containsEntry("cloudUsername", CLOUD_USERNAME);
            assertThat(status).containsEntry("llmSource", "BYOK");
            assertThat(status).containsKey("linkedAt");
        }

        @Test
        @DisplayName("Status map carries catalogSource alongside llmSource, each reflecting its own toggle")
        void statusCarriesCatalogSourceAlongsideLlmSource() {
            CeCloudLinkEntity link = buildLink();
            link.setLlmSource(CloudLlmSource.BYOK.name());
            link.setCatalogSource(CloudLlmSource.CLOUD.name());
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("llmSource", "BYOK");
            assertThat(status).containsEntry("catalogSource", "CLOUD");
        }

        @Test
        @DisplayName("Should include registered flag and install id when link is registered")
        void shouldIncludeRegisteredFlagAndInstallId() {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(Instant.now());
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("linked", true);
            assertThat(status).containsEntry("registered", true);
            assertThat(status).containsEntry("installId", installId.toString());
        }

        @Test
        @DisplayName("CLOUD-sourced registered link exposes the governing cloudPlanCode for frontend gates")
        void cloudSourcedStatusIncludesCloudPlanCode() throws Exception {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setLlmSource(CloudLlmSource.CLOUD.name());
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            com.fasterxml.jackson.databind.JsonNode body =
                    new ObjectMapper().readTree("{\"planCode\":\"TEAM\",\"userId\":42}");
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(body));

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("cloudPlanCode", "TEAM");
        }

        @Test
        @DisplayName("All-BYOK link (both toggles) omits cloudPlanCode (local plan governs, no entitlements fetch)")
        void byokStatusOmitsCloudPlanCode() {
            CeCloudLinkEntity link = buildLink(); // both llmSource and catalogSource BYOK by default
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).doesNotContainKey("cloudPlanCode");
            // No cloud round-trip at all: neither toggle draws on the cloud account.
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("regression: llmSource=BYOK + catalogSource=CLOUD still exposes cloudPlanCode (catalog upsell keys on it)")
        void catalogOnlyCloudSourcedStatusIncludesCloudPlanCode() throws Exception {
            // Pre-fix, the entitlement fetch was gated on llmSource==CLOUD only, so a
            // catalog-relaying install on a PAID plan showed a false "subscription
            // required" note in the Settings > AI Providers catalogSource pill.
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setLlmSource(CloudLlmSource.BYOK.name());
            link.setCatalogSource(CloudLlmSource.CLOUD.name());
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            com.fasterxml.jackson.databind.JsonNode body =
                    new ObjectMapper().readTree("{\"planCode\":\"TEAM\",\"userId\":42}");
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(body));

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("cloudPlanCode", "TEAM");
        }

        @Test
        @DisplayName("Unlinked install: installLinked=false (no cloud activation for a member to inherit)")
        void unlinkedInstallReportsInstallLinkedFalse() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.empty());

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("linked", false);
            assertThat(status).containsEntry("installLinked", false);
            assertThat(status).doesNotContainKey("installCloudPlanCode");
        }

        @Test
        @DisplayName("CE member (no own link) inherits the install's CLOUD activation: installLinked=true + installCloudPlanCode, but linked=false (no management)")
        void memberInheritsInstallCloudActivation() throws Exception {
            long memberTenant = 777L;
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            CeCloudLinkEntity adminLink = buildLink();
            adminLink.setTenantId(TENANT_ID);
            adminLink.setLlmSource(CloudLlmSource.CLOUD.name());
            adminLink.setInstallId(installId);
            adminLink.setRegisteredAt(clock.instant());
            adminLink.setCachedAccessToken("cached-access-token");
            adminLink.setTokenExpiresAt(clock.instant().plusSeconds(300));
            // The member's own tenant has NO link → no management surface...
            when(cloudLinkRepository.findByTenantId(memberTenant)).thenReturn(Optional.empty());
            // ...but the install carries the admin's active registered link (install-global).
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.of(adminLink));
            // getCloudAccessToken(adminLink.tenantId) re-reads the admin link; cached token valid → no refresh.
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(adminLink));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            com.fasterxml.jackson.databind.JsonNode body =
                    new ObjectMapper().readTree("{\"planCode\":\"TEAM\",\"userId\":1}");
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(body));

            Map<String, Object> status = service.getLinkStatus(memberTenant);

            assertThat(status).containsEntry("linked", false);            // member gets no management surface
            assertThat(status).containsEntry("installLinked", true);      // inherits the install's cloud visibility
            assertThat(status).containsEntry("installCloudPlanCode", "TEAM");
            assertThat(status).doesNotContainKey("cloudPlanCode");        // the per-user (management) plan stays absent
        }

        @Test
        @DisplayName("Owner: installLinked=true but installCloudPlanCode omitted (owner already exposes the per-user cloudPlanCode)")
        void ownerInstallLinkedWithoutDuplicateInstallPlan() throws Exception {
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            CeCloudLinkEntity link = buildLink();
            link.setLlmSource(CloudLlmSource.CLOUD.name());
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            com.fasterxml.jackson.databind.JsonNode body =
                    new ObjectMapper().readTree("{\"planCode\":\"TEAM\",\"userId\":42}");
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(body));

            Map<String, Object> status = service.getLinkStatus(TENANT_ID);

            assertThat(status).containsEntry("linked", true);
            assertThat(status).containsEntry("cloudPlanCode", "TEAM");
            assertThat(status).containsEntry("installLinked", true);
            assertThat(status).doesNotContainKey("installCloudPlanCode");
            // Pin the no-redundant-round-trip invariant: the owner already exposes cloudPlanCode from
            // the per-user branch, so the install-global branch must NOT fetch the entitlement again.
            verify(restTemplate, org.mockito.Mockito.times(1)).exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class));
        }

        @Test
        @DisplayName("CE member inherits a BYOK install: installLinked=true but no installCloudPlanCode (local plan governs, no cloud fetch)")
        void memberInheritsByokInstallWithoutCloudPlan() {
            long memberTenant = 778L;
            CeCloudLinkEntity byokInstallLink = buildLink(); // BYOK by default
            byokInstallLink.setRegisteredAt(clock.instant());
            when(cloudLinkRepository.findByTenantId(memberTenant)).thenReturn(Optional.empty());
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.of(byokInstallLink));

            Map<String, Object> status = service.getLinkStatus(memberTenant);

            assertThat(status).containsEntry("linked", false);
            assertThat(status).containsEntry("installLinked", true);
            assertThat(status).doesNotContainKey("installCloudPlanCode"); // BYOK install → no cloud plan to inherit
            verifyNoInteractions(restTemplate);
        }
    }

    @Nested
    @DisplayName("getActiveInstallRuntime (install-global, for the model-catalog bundle sync)")
    class ActiveInstallRuntime {

        @Test
        @DisplayName("Returns the install's active-link creds even on a BYOK link - being linked entitles the install to catalog updates")
        void returnsCredsForRegisteredByokLink() {
            CeCloudLinkEntity link = buildLink(); // BYOK by default
            link.setTenantId(TENANT_ID);
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.of(link));
            // getCloudAccessToken re-fetches by tenantId; cached token is still valid → no refresh.
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            org.mockito.Mockito.lenient().when(cloudLinkRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            CloudLinkService.CloudRuntimeStatus status = service.getActiveInstallRuntime();

            assertThat(status.cloudReady()).isTrue();
            assertThat(status.accessToken()).isEqualTo("cached-access-token");
            assertThat(status.installId()).isEqualTo(installId.toString());
            // llmSource is reported as-is (BYOK here) - it does NOT gate the bundle entitlement.
            assertThat(status.source()).isEqualTo(CloudLlmSource.BYOK);
        }

        @Test
        @DisplayName("byok()/not-ready when this install has no registered link (no link, no updates)")
        void byokWhenNoRegisteredLink() {
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.empty());

            CloudLinkService.CloudRuntimeStatus status = service.getActiveInstallRuntime();

            assertThat(status.cloudReady()).isFalse();
            assertThat(status.accessToken()).isNull();
        }

        @Test
        @DisplayName("notReady (cloudReady=false, no creds) when the link exists but its access token can't be obtained")
        void notReadyWhenTokenUnavailable() {
            CeCloudLinkEntity link = buildLink();
            link.setTenantId(TENANT_ID);
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("expired-token");
            link.setTokenExpiresAt(clock.instant().minusSeconds(60)); // expired → forces a refresh
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.of(link));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            org.mockito.Mockito.lenient().when(cloudLinkRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            // The token refresh to Keycloak fails → getCloudAccessToken throws → getActiveInstallRuntime
            // catches it and reports notReady (never returns half-built creds). Lenient so the test is
            // robust whether getCloudAccessToken throws at the refresh call or earlier (decrypt).
            org.mockito.Mockito.lenient().when(restTemplate.exchange(
                            eq("https://keycloak.example.com/realms/test/protocol/openid-connect/token"),
                            eq(HttpMethod.POST),
                            any(HttpEntity.class),
                            eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.RestClientException("keycloak refresh failed"));

            CloudLinkService.CloudRuntimeStatus status = service.getActiveInstallRuntime();

            assertThat(status.cloudReady()).isFalse();
            assertThat(status.accessToken()).isNull();
            // The link's source is still reported (BYOK here), but the install is not ready to sync.
            assertThat(status.source()).isEqualTo(CloudLlmSource.BYOK);
        }
    }

    @Nested
    @DisplayName("llmSource")
    class LlmSource {

        @Test
        @DisplayName("Should default to BYOK when no cloud link exists")
        void shouldDefaultToByokWhenNoLink() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThat(service.getLlmSource(TENANT_ID)).isEqualTo(CloudLlmSource.BYOK);
            assertThat(service.setLlmSource(TENANT_ID, CloudLlmSource.BYOK)).isEqualTo(CloudLlmSource.BYOK);
            verify(cloudLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should reject Cloud source when no cloud link exists")
        void shouldRejectCloudWhenNoLink() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(CloudLinkService.CloudAccountNotLinkedException.class)
                    .hasMessageContaining("No cloud account linked");
            verify(cloudLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should reject Cloud (CLOUD_LINK_NOT_READY) when linked but cloud registration fails - never persists CLOUD")
        void shouldRejectCloudWhenLinkedButRegistrationFails() {
            // A linked-but-unregistered install asked to switch to CLOUD: setLlmSource attempts
            // registerWithCloud; the cloud responds non-2xx so registeredAt stays null, and the
            // service must throw (→ controller 409 CLOUD_LINK_NOT_READY) WITHOUT persisting CLOUD.
            CeCloudLinkEntity link = buildLink(); // BYOK, registeredAt=null by default
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // registerWithCloud POSTs to /ce-link/register; a non-2xx response leaves registeredAt null.
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.status(503).build());

            assertThatThrownBy(() -> service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not registered");

            // The defect this guards: CLOUD must NEVER be persisted on the failed-registration path
            // (a relay against an unregistered install would 401 at runtime).
            verify(cloudLinkRepository, never()).save(argThat(s -> "CLOUD".equals(s.getLlmSource())));
            assertThat(link.getLlmSource()).isNotEqualTo("CLOUD");
            assertThat(link.getRegisteredAt()).isNull();
        }

        @Test
        @DisplayName("Should persist selected source on an existing registered link")
        void shouldPersistSelectedSource() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant());
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CloudLlmSource selected = service.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD);

            assertThat(selected).isEqualTo(CloudLlmSource.CLOUD);
            ArgumentCaptor<CeCloudLinkEntity> saved = ArgumentCaptor.forClass(CeCloudLinkEntity.class);
            verify(cloudLinkRepository).save(saved.capture());
            assertThat(saved.getValue().getLlmSource()).isEqualTo("CLOUD");
        }

        @Test
        @DisplayName("Should return Cloud runtime credentials only when Cloud source is selected")
        void shouldReturnCloudRuntimeCredentialsOnlyWhenCloudSelected() {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setLlmSource("CLOUD");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CloudLinkService.CloudRuntimeStatus status = service.getCloudRuntimeStatus(TENANT_ID);

            assertThat(status.source()).isEqualTo(CloudLlmSource.CLOUD);
            assertThat(status.cloudReady()).isTrue();
            assertThat(status.accessToken()).isEqualTo("cached-access-token");
            assertThat(status.installId()).isEqualTo(installId.toString());
            assertThat(status.cloudApiUrl()).isEqualTo("https://livecontext.ai/api");
        }

        @Test
        @DisplayName("Should not mark Cloud runtime ready when cloud registration fails")
        void shouldNotReturnReadyRuntimeWhenRegistrationFails() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setLlmSource("CLOUD");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.status(503).build());

            CloudLinkService.CloudRuntimeStatus status = service.getCloudRuntimeStatus(TENANT_ID);

            assertThat(status.source()).isEqualTo(CloudLlmSource.CLOUD);
            assertThat(status.cloudReady()).isFalse();
            assertThat(status.accessToken()).isNull();
            assertThat(status.installId()).isNull();
        }
    }

    @Nested
    @DisplayName("catalogSource")
    class CatalogSource {

        @Test
        @DisplayName("Should default to BYOK when no cloud link exists")
        void shouldDefaultToByokWhenNoLink() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThat(service.getCatalogSource(TENANT_ID)).isEqualTo(CloudLlmSource.BYOK);
            assertThat(service.setCatalogSource(TENANT_ID, CloudLlmSource.BYOK)).isEqualTo(CloudLlmSource.BYOK);
            verify(cloudLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should reject Cloud catalog source when no cloud link exists")
        void shouldRejectCloudWhenNoLink() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setCatalogSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(CloudLinkService.CloudAccountNotLinkedException.class)
                    .hasMessageContaining("No cloud account linked");
            verify(cloudLinkRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should trigger cloud registration when switching an unregistered link to CLOUD, and persist on success")
        void shouldRegisterWhenUnregisteredAndPersistOnSuccess() {
            CeCloudLinkEntity link = buildLink(); // registeredAt=null by default
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // registerWithCloud POSTs to /ce-link/register; 2xx stamps registeredAt.
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok().build());

            CloudLlmSource selected = service.setCatalogSource(TENANT_ID, CloudLlmSource.CLOUD);

            assertThat(selected).isEqualTo(CloudLlmSource.CLOUD);
            assertThat(link.getRegisteredAt()).isNotNull();
            verify(cloudLinkRepository, atLeastOnce())
                    .save(argThat(s -> "CLOUD".equals(s.getCatalogSource())));
        }

        @Test
        @DisplayName("Should reject Cloud (CLOUD_LINK_NOT_READY) when linked but cloud registration fails - never persists CLOUD")
        void shouldRejectCloudWhenLinkedButRegistrationFails() {
            // Exact mirror of the llmSource guard: a linked-but-unregistered install asked to
            // switch the CATALOG toggle to CLOUD attempts registerWithCloud; the cloud responds
            // non-2xx so registeredAt stays null, and the service must throw WITHOUT persisting CLOUD.
            CeCloudLinkEntity link = buildLink(); // BYOK, registeredAt=null by default
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.status(503).build());

            assertThatThrownBy(() -> service.setCatalogSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not registered");

            verify(cloudLinkRepository, never()).save(argThat(s -> "CLOUD".equals(s.getCatalogSource())));
            assertThat(link.getCatalogSource()).isNotEqualTo("CLOUD");
            assertThat(link.getRegisteredAt()).isNull();
        }

        @Test
        @DisplayName("Should persist selected catalog source on an existing registered link without touching llmSource")
        void shouldPersistSelectedCatalogSource() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant());
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CloudLlmSource selected = service.setCatalogSource(TENANT_ID, CloudLlmSource.CLOUD);

            assertThat(selected).isEqualTo(CloudLlmSource.CLOUD);
            ArgumentCaptor<CeCloudLinkEntity> saved = ArgumentCaptor.forClass(CeCloudLinkEntity.class);
            verify(cloudLinkRepository).save(saved.capture());
            assertThat(saved.getValue().getCatalogSource()).isEqualTo("CLOUD");
            // The LLM toggle is independent and must stay untouched.
            assertThat(saved.getValue().getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("getCatalogRuntimeStatus returns byok() when catalogSource=BYOK even if llmSource=CLOUD (toggle independence)")
        void catalogRuntimeStaysByokWhenOnlyLlmSourceIsCloud() {
            // Regression guard: the catalog relay must key on catalogSource, never on the
            // (independent) llmSource toggle - a tenant relaying LLM calls has NOT opted in
            // to relaying catalog tool executions.
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant());
            link.setLlmSource("CLOUD");
            link.setCatalogSource("BYOK");
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            CloudLinkService.CloudRuntimeStatus status = service.getCatalogRuntimeStatus(TENANT_ID);

            assertThat(status.source()).isEqualTo(CloudLlmSource.BYOK);
            assertThat(status.cloudReady()).isFalse();
            assertThat(status.accessToken()).isNull();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("getCloudRuntimeStatus (LLM) stays byok() when only catalogSource=CLOUD (toggle independence, other direction)")
        void llmRuntimeStaysByokWhenOnlyCatalogSourceIsCloud() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant());
            link.setLlmSource("BYOK");
            link.setCatalogSource("CLOUD");
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            CloudLinkService.CloudRuntimeStatus status = service.getCloudRuntimeStatus(TENANT_ID);

            assertThat(status.source()).isEqualTo(CloudLlmSource.BYOK);
            assertThat(status.cloudReady()).isFalse();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("getCatalogRuntimeStatus returns ready credentials when catalogSource=CLOUD on a registered link")
        void catalogRuntimeReadyWhenCatalogSourceCloud() {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setLlmSource("BYOK"); // independent toggle stays BYOK
            link.setCatalogSource("CLOUD");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CloudLinkService.CloudRuntimeStatus status = service.getCatalogRuntimeStatus(TENANT_ID);

            assertThat(status.source()).isEqualTo(CloudLlmSource.CLOUD);
            assertThat(status.cloudReady()).isTrue();
            assertThat(status.accessToken()).isEqualTo("cached-access-token");
            assertThat(status.installId()).isEqualTo(installId.toString());
            assertThat(status.cloudApiUrl()).isEqualTo("https://livecontext.ai/api");
        }

        @Test
        @DisplayName("getCatalogRuntimeStatus is notReady when catalogSource=CLOUD but cloud registration fails")
        void catalogRuntimeNotReadyWhenRegistrationFails() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setCatalogSource("CLOUD");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.status(503).build());

            CloudLinkService.CloudRuntimeStatus status = service.getCatalogRuntimeStatus(TENANT_ID);

            assertThat(status.source()).isEqualTo(CloudLlmSource.CLOUD);
            assertThat(status.cloudReady()).isFalse();
            assertThat(status.accessToken()).isNull();
            assertThat(status.installId()).isNull();
        }
    }

    @Nested
    @DisplayName("getActiveInstallCatalogRuntime (install-global, for the auth-side public-info delegation)")
    class ActiveInstallCatalogRuntime {

        @Test
        @DisplayName("Returns the install's active-link creds reporting the link's catalogSource (not its llmSource)")
        void returnsCredsReportingCatalogSource() {
            CeCloudLinkEntity link = buildLink();
            link.setTenantId(TENANT_ID);
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setLlmSource("BYOK");
            link.setCatalogSource("CLOUD");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.of(link));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            org.mockito.Mockito.lenient().when(cloudLinkRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            CloudLinkService.CloudRuntimeStatus status = service.getActiveInstallCatalogRuntime();

            assertThat(status.cloudReady()).isTrue();
            assertThat(status.accessToken()).isEqualTo("cached-access-token");
            assertThat(status.installId()).isEqualTo(installId.toString());
            // The CATALOG toggle is reported, not the (BYOK) llm toggle.
            assertThat(status.source()).isEqualTo(CloudLlmSource.CLOUD);
        }

        @Test
        @DisplayName("byok()/not-ready when this install has no registered link")
        void byokWhenNoRegisteredLink() {
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.empty());

            CloudLinkService.CloudRuntimeStatus status = service.getActiveInstallCatalogRuntime();

            assertThat(status.cloudReady()).isFalse();
            assertThat(status.accessToken()).isNull();
        }

        @Test
        @DisplayName("regression: NOT ready when the active link's catalogSource=BYOK, even with llmSource=CLOUD (relay is opt-in)")
        void notReadyWhenCatalogSourceByokDespiteCloudLlmSource() {
            // Pre-fix, this returned cloudReady=true regardless of catalogSource, so the
            // public-info delegation advertised available=true for an install whose admin
            // left the catalog toggle on BYOK - unlocking a builder toggle that could
            // never execute (the relay itself filters on catalogSource=CLOUD).
            CeCloudLinkEntity link = buildLink();
            link.setTenantId(TENANT_ID);
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant());
            link.setLlmSource("CLOUD");
            link.setCatalogSource("BYOK");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.of(link));

            CloudLinkService.CloudRuntimeStatus status = service.getActiveInstallCatalogRuntime();

            assertThat(status.cloudReady()).isFalse();
            assertThat(status.accessToken()).isNull();
            assertThat(status.installId()).isNull();
            assertThat(status.source()).isEqualTo(CloudLlmSource.BYOK);
        }

        @Test
        @DisplayName("notReady (cloudReady=false, no creds) when the link exists but its access token can't be obtained")
        void notReadyWhenTokenUnavailable() {
            CeCloudLinkEntity link = buildLink();
            link.setTenantId(TENANT_ID);
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant());
            link.setCatalogSource("CLOUD");
            link.setCachedAccessToken("expired-token");
            link.setTokenExpiresAt(clock.instant().minusSeconds(60)); // expired → forces a refresh
            when(cloudLinkRepository.findFirstByRegisteredAtNotNullOrderByLinkedAtDesc())
                    .thenReturn(Optional.of(link));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            org.mockito.Mockito.lenient().when(cloudLinkRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            org.mockito.Mockito.lenient().when(restTemplate.exchange(
                            eq("https://keycloak.example.com/realms/test/protocol/openid-connect/token"),
                            eq(HttpMethod.POST),
                            any(HttpEntity.class),
                            eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.RestClientException("keycloak refresh failed"));

            CloudLinkService.CloudRuntimeStatus status = service.getActiveInstallCatalogRuntime();

            assertThat(status.cloudReady()).isFalse();
            assertThat(status.accessToken()).isNull();
            assertThat(status.source()).isEqualTo(CloudLlmSource.CLOUD);
        }
    }

    @Nested
    @DisplayName("sendHeartbeat")
    class SendHeartbeat {

        @Test
        @DisplayName("Should keep BYOK source when pending cloud registration is not confirmed")
        void shouldKeepByokWhenPendingRegistrationIsNotConfirmed() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setLlmSource("BYOK");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.status(503).build());

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.PENDING_REGISTER);
            assertThat(link.getRegisteredAt()).isNull();
            assertThat(link.getLlmSource()).isEqualTo("BYOK");
        }

        @Test
        @DisplayName("Should promote source to Cloud when pending registration succeeds")
        void shouldPromoteSourceWhenPendingRegistrationSucceeds() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setLlmSource("BYOK");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok().build());

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.REGISTERED);
            assertThat(link.getRegisteredAt()).isNotNull();
            assertThat(link.getLlmSource()).isEqualTo("CLOUD");
        }

        @Test
        @DisplayName("Registered link + cloud 2xx heartbeat → OK, stays registered, lastUsedAt refreshed")
        void registeredHeartbeatOkKeepsLinkActive() {
            CeCloudLinkEntity link = registeredCloudLink();
            Instant registeredAt = link.getRegisteredAt();
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(Void.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.OK));

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.OK);
            assertThat(link.getRegisteredAt()).isEqualTo(registeredAt);
            assertThat(link.getLlmSource()).isEqualTo("CLOUD");
            assertThat(link.getLastUsedAt()).isEqualTo(clock.instant());
            // The refreshed lastUsedAt is PERSISTED (the prod path re-fetches + saves a fresh row).
            verify(cloudLinkRepository, atLeastOnce())
                    .save(argThat(s -> s.getRegisteredAt() != null && s.getLastUsedAt() != null));
        }

        @Test
        @DisplayName("Registered link + cloud 410 GONE → REVOKED: clears registration + token, demotes to BYOK")
        void registeredHeartbeat410RevokesAndDemotesToByok() {
            CeCloudLinkEntity link = registeredCloudLink();
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(Void.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.GONE, "GONE",
                            HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.REVOKED);
            assertThat(link.getRegisteredAt()).isNull();
            assertThat(link.getCachedAccessToken()).isNull();
            assertThat(link.getTokenExpiresAt()).isNull();
            assertThat(link.getLlmSource()).isEqualTo("BYOK");
            // The revoked/demoted state must be PERSISTED, not just mutated in memory.
            verify(cloudLinkRepository, atLeastOnce()).save(argThat(s ->
                    s.getRegisteredAt() == null && "BYOK".equals(s.getLlmSource()) && s.getCachedAccessToken() == null));
        }

        @Test
        @DisplayName("Registered link + cloud 404 → NOT_FOUND: clears registration + demotes, but KEEPS the cached token for re-link")
        void registeredHeartbeat404ClearsRegistrationButKeepsToken() {
            CeCloudLinkEntity link = registeredCloudLink();
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(Void.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "NOT FOUND",
                            HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.NOT_FOUND);
            assertThat(link.getRegisteredAt()).isNull();
            assertThat(link.getLlmSource()).isEqualTo("BYOK");
            // 404 keeps the cached token so the user can re-link without a fresh OAuth round-trip.
            assertThat(link.getCachedAccessToken()).isEqualTo("cached-access-token");
            // Persisted: registration cleared + BYOK, but the cached token is deliberately retained.
            verify(cloudLinkRepository, atLeastOnce()).save(argThat(s ->
                    s.getRegisteredAt() == null && "BYOK".equals(s.getLlmSource())
                            && "cached-access-token".equals(s.getCachedAccessToken())));
        }

        @Test
        @DisplayName("Registered link + cloud 5xx → TRANSIENT_FAILURE: no local mutation, link stays registered for retry")
        void registeredHeartbeat5xxIsTransientAndKeepsLinkIntact() {
            CeCloudLinkEntity link = registeredCloudLink();
            Instant registeredAt = link.getRegisteredAt();
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(Void.class)))
                    .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "DOWN",
                            HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.TRANSIENT_FAILURE);
            assertThat(link.getRegisteredAt()).isEqualTo(registeredAt);
            assertThat(link.getLlmSource()).isEqualTo("CLOUD");
            assertThat(link.getCachedAccessToken()).isEqualTo("cached-access-token");
            assertThat(link.getTokenExpiresAt()).isEqualTo(clock.instant().plusSeconds(300));
            // A transient blip must NEVER persist a cleared/demoted state (no false revoke).
            verify(cloudLinkRepository, never()).save(argThat(s ->
                    s.getRegisteredAt() == null || "BYOK".equals(s.getLlmSource())));
        }

        @Test
        @DisplayName("Registered link but cloud token cannot be obtained → TOKEN_UNAVAILABLE, no heartbeat POST, link untouched")
        void registeredHeartbeatTokenUnavailableSkipsCall() {
            CeCloudLinkEntity link = registeredCloudLink();
            Instant registeredAt = link.getRegisteredAt();
            // No valid cached token → getCloudAccessToken must refresh; the stored refresh token is
            // not decryptable here → it throws → sendHeartbeat short-circuits to TOKEN_UNAVAILABLE.
            link.setCachedAccessToken(null);
            link.setTokenExpiresAt(null);
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.TOKEN_UNAVAILABLE);
            // No mutation, no cloud POST - the link is left intact for the next tick.
            assertThat(link.getRegisteredAt()).isEqualTo(registeredAt);
            assertThat(link.getLlmSource()).isEqualTo("CLOUD");
            verify(restTemplate, never()).postForEntity(any(String.class), any(HttpEntity.class), eq(Void.class));
        }

        @Test
        @DisplayName("Registered link + cloud 410 GONE also resets catalogSource to BYOK (same revocation as llmSource)")
        void registeredHeartbeat410AlsoResetsCatalogSource() {
            CeCloudLinkEntity link = registeredCloudLink();
            link.setCatalogSource("CLOUD");
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(Void.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.GONE, "GONE",
                            HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.REVOKED);
            assertThat(link.getCatalogSource()).isEqualTo("BYOK");
            verify(cloudLinkRepository, atLeastOnce()).save(argThat(s ->
                    "BYOK".equals(s.getCatalogSource()) && "BYOK".equals(s.getLlmSource())));
        }

        @Test
        @DisplayName("Registered link + cloud 404 also resets catalogSource to BYOK (same revocation as llmSource)")
        void registeredHeartbeat404AlsoResetsCatalogSource() {
            CeCloudLinkEntity link = registeredCloudLink();
            link.setCatalogSource("CLOUD");
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(Void.class)))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "NOT FOUND",
                            HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

            assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.NOT_FOUND);
            assertThat(link.getCatalogSource()).isEqualTo("BYOK");
            verify(cloudLinkRepository, atLeastOnce()).save(argThat(s ->
                    "BYOK".equals(s.getCatalogSource()) && "BYOK".equals(s.getLlmSource())));
        }

        /** A fully-registered CLOUD link with a still-valid cached token (skips OAuth refresh). */
        private CeCloudLinkEntity registeredCloudLink() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant().minusSeconds(600));
            link.setLlmSource("CLOUD");
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            return link;
        }
    }

    @Nested
    @DisplayName("unlinkAccount")
    class UnlinkAccount {

        @Test
        @DisplayName("Should delete existing link")
        void shouldDeleteExistingLink() {
            CeCloudLinkEntity link = buildLink();
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            service.unlinkAccount(TENANT_ID);

            verify(cloudLinkRepository).delete(link);
        }

        @Test
        @DisplayName("Should do nothing when no link exists")
        void shouldDoNothingWhenNoLink() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            service.unlinkAccount(TENANT_ID);

            verify(cloudLinkRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("isLinked")
    class IsLinked {

        @Test
        @DisplayName("Should return true when link exists")
        void shouldReturnTrueWhenLinked() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(buildLink()));

            assertThat(service.isLinked(TENANT_ID)).isTrue();
        }

        @Test
        @DisplayName("Should return false when no link exists")
        void shouldReturnFalseWhenNotLinked() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThat(service.isLinked(TENANT_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("getCloudAccessToken")
    class GetCloudAccessToken {

        @Test
        @DisplayName("Should throw CloudAccountNotLinkedException when no link exists")
        void shouldThrowWhenNoLink() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCloudAccessToken(TENANT_ID))
                    .isInstanceOf(CloudLinkService.CloudAccountNotLinkedException.class)
                    .hasMessageContaining("No cloud account linked");
        }

        @Test
        @DisplayName("Should return cached token when still valid")
        void shouldReturnCachedTokenWhenValid() {
            CeCloudLinkEntity link = buildLink();
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(Instant.now().plusSeconds(300)); // valid for 5 more minutes
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            String token = service.getCloudAccessToken(TENANT_ID);

            assertThat(token).isEqualTo("cached-access-token");
            // Should update lastUsedAt and save
            ArgumentCaptor<CeCloudLinkEntity> captor = ArgumentCaptor.forClass(CeCloudLinkEntity.class);
            verify(cloudLinkRepository).save(captor.capture());
            assertThat(captor.getValue().getLastUsedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Encryption")
    class Encryption {

        @Test
        @DisplayName("Should round-trip encrypt and decrypt correctly")
        void shouldRoundTripEncryptDecrypt() throws Exception {
            // Use reflection to test private encrypt/decrypt methods
            java.lang.reflect.Method encryptMethod = CloudLinkService.class
                    .getDeclaredMethod("encrypt", String.class);
            encryptMethod.setAccessible(true);
            java.lang.reflect.Method decryptMethod = CloudLinkService.class
                    .getDeclaredMethod("decrypt", String.class);
            decryptMethod.setAccessible(true);

            String original = "my-secret-refresh-token";
            String encrypted = (String) encryptMethod.invoke(service, original);
            String decrypted = (String) decryptMethod.invoke(service, encrypted);

            assertThat(encrypted).isNotEqualTo(original);
            assertThat(decrypted).isEqualTo(original);
        }

        @Test
        @DisplayName("Should reject blank encryption key")
        void shouldRejectBlankEncryptionKey() {
            assertThatThrownBy(() -> new CloudLinkService(
                    cloudLinkRepository,
                    "https://keycloak.example.com/realms/test",
                    "test-client-id",
                    "http://localhost:3000/callback",
                    "",
                    "https://livecontext.ai/api",
                    "1.4.0-test",
                    new ObjectMapper(),
                    restTemplate,
                    clock
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cloud-link.encryption-key");
        }

        @Test
        @DisplayName("Should reject the shipped 'change-me-in-production' placeholder encryption key")
        void shouldRejectDefaultPlaceholderEncryptionKey() {
            // deriveKey rejects the insecure default placeholder (LEGACY_DEFAULT_ENCRYPTION_KEY) so a CE
            // that never configured cloud-link.encryption-key cannot boot with a known, guessable key.
            // The blank case is covered above; this pins the distinct default-key branch of the guard.
            assertThatThrownBy(() -> new CloudLinkService(
                    cloudLinkRepository,
                    "https://keycloak.example.com/realms/test",
                    "test-client-id",
                    "http://localhost:3000/callback",
                    "change-me-in-production",
                    "https://livecontext.ai/api",
                    "1.4.0-test",
                    new ObjectMapper(),
                    restTemplate,
                    clock
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cloud-link.encryption-key");
        }

        @Test
        @DisplayName("Should produce different ciphertext for same plaintext (random IV)")
        void shouldProduceDifferentCiphertext() throws Exception {
            java.lang.reflect.Method encryptMethod = CloudLinkService.class
                    .getDeclaredMethod("encrypt", String.class);
            encryptMethod.setAccessible(true);

            String original = "same-token";
            String encrypted1 = (String) encryptMethod.invoke(service, original);
            String encrypted2 = (String) encryptMethod.invoke(service, original);

            assertThat(encrypted1).isNotEqualTo(encrypted2);
        }
    }

    // ========== Helpers ==========

    @Nested
    @DisplayName("fetchCloudPlanCode")
    class FetchCloudPlanCode {

        @Test
        @DisplayName("Inherits the bound cloud account's plan code from the ce-link entitlements")
        void inheritsCloudPlanCode() throws Exception {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            com.fasterxml.jackson.databind.JsonNode body =
                    new ObjectMapper().readTree("{\"planCode\":\"PRO\",\"userId\":42}");
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(body));

            String planCode = service.fetchCloudPlanCode(link);

            assertThat(planCode).isEqualTo("PRO");
        }

        @Test
        @DisplayName("Returns null (CE falls back to its local plan) when the install isn't registered yet")
        void nullWhenNotRegistered() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.randomUUID());
            // registeredAt deliberately left null - nothing to inherit yet

            String planCode = service.fetchCloudPlanCode(link);

            assertThat(planCode).isNull();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Serves the last-known-good plan on a transient cloud outage so the cap never flaps to FREE")
        void servesCachedPlanOnTransientFailure() throws Exception {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            com.fasterxml.jackson.databind.JsonNode body =
                    new ObjectMapper().readTree("{\"planCode\":\"PRO\"}");
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(body))
                    .thenThrow(new org.springframework.web.client.RestClientException("cloud down"));

            assertThat(service.fetchCloudPlanCode(link)).isEqualTo("PRO"); // success primes the cache
            assertThat(service.fetchCloudPlanCode(link)).isEqualTo("PRO"); // outage → served from cache, no collapse to null
        }

        @Test
        @DisplayName("A 2xx response without a plan code is authoritative - returns null and drops the cached value")
        void clearsCacheWhenCloudReportsNoPlan() throws Exception {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            com.fasterxml.jackson.databind.JsonNode withPlan =
                    new ObjectMapper().readTree("{\"planCode\":\"PRO\"}");
            com.fasterxml.jackson.databind.JsonNode noPlan = new ObjectMapper().readTree("{}");
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(withPlan))
                    .thenReturn(ResponseEntity.ok(noPlan));

            assertThat(service.fetchCloudPlanCode(link)).isEqualTo("PRO"); // primes the cache
            assertThat(service.fetchCloudPlanCode(link)).isNull();         // cloud authoritatively reports no plan → cache dropped
        }
    }

    @Nested
    @DisplayName("governingCloudPlanCode")
    class GoverningCloudPlanCode {

        @Test
        @DisplayName("CLOUD-sourced + registered: returns the bound cloud account's governing plan")
        void cloudSourcedReturnsCloudPlan() throws Exception {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setLlmSource(CloudLlmSource.CLOUD.name());
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            com.fasterxml.jackson.databind.JsonNode body =
                    new ObjectMapper().readTree("{\"planCode\":\"PRO\",\"userId\":42}");
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/ce-link/" + installId + "/entitlements"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(body));

            assertThat(service.governingCloudPlanCode(TENANT_ID)).contains("PRO");
        }

        @Test
        @DisplayName("BYOK-sourced: empty so the local plan governs - never calls the cloud")
        void byokSourcedReturnsEmpty() {
            CeCloudLinkEntity link = buildLink();
            link.setLlmSource(CloudLlmSource.BYOK.name());
            link.setRegisteredAt(clock.instant());
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            assertThat(service.governingCloudPlanCode(TENANT_ID)).isEmpty();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("No cloud link: empty - never calls the cloud")
        void noLinkReturnsEmpty() {
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

            assertThat(service.governingCloudPlanCode(TENANT_ID)).isEmpty();
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("CLOUD-sourced but not registered yet: empty (fail-safe to the local plan)")
        void cloudNotRegisteredReturnsEmpty() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.randomUUID());
            link.setLlmSource(CloudLlmSource.CLOUD.name());
            // registeredAt left null → fetchCloudPlanCode returns null without calling the cloud
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            assertThat(service.governingCloudPlanCode(TENANT_ID)).isEmpty();
            verifyNoInteractions(restTemplate);
        }
    }

    @Nested
    @DisplayName("fetchCloudUsageSummary")
    class FetchCloudUsageSummary {

        @Test
        @DisplayName("Reduces to ONLY the relay slice: 30-day total + breakdown become CE_LLM_RELAY-only, balance kept")
        void reducesSummaryToRelaySlice() {
            CeCloudLinkEntity link = buildLink();
            UUID installId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            link.setInstallId(installId);
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            Map<String, Object> breakdown = new HashMap<>();
            breakdown.put("CE_LLM_RELAY", Map.of("count", 5, "credits", 1234));
            // The cloud account's OTHER activity - must NOT leak into the CE view.
            breakdown.put("CHAT_CONVERSATION", Map.of("count", 99, "credits", 999999));
            Map<String, Object> cloudSummary = new HashMap<>();
            cloudSummary.put("balance", 50000);
            cloudSummary.put("totalConsumedLast30Days", 1001233); // full-account sum
            cloudSummary.put("breakdownByType", breakdown);
            cloudSummary.put("delinquent", false);
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/credits/summary"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok((Map) cloudSummary));

            Map<String, Object> result = service.fetchCloudUsageSummary(TENANT_ID);

            assertThat(result.get("totalConsumedLast30Days")).isEqualTo(1234); // relay only, NOT 1001233
            assertThat(result.get("balance")).isEqualTo(50000); // relay budget kept
            @SuppressWarnings("unchecked")
            Map<String, Object> resultBreakdown = (Map<String, Object>) result.get("breakdownByType");
            assertThat(resultBreakdown).containsOnlyKeys("CE_LLM_RELAY");
        }

        @Test
        @DisplayName("Returns 0 consumed + empty breakdown when this install never relayed (no CE_LLM_RELAY rows)")
        void zeroSummaryWhenNoRelayUsage() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.randomUUID());
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            Map<String, Object> breakdown = new HashMap<>();
            breakdown.put("CHAT_CONVERSATION", Map.of("count", 99, "credits", 999999));
            Map<String, Object> cloudSummary = new HashMap<>();
            cloudSummary.put("balance", 50000);
            cloudSummary.put("totalConsumedLast30Days", 999999);
            cloudSummary.put("breakdownByType", breakdown);
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/credits/summary"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok((Map) cloudSummary));

            Map<String, Object> result = service.fetchCloudUsageSummary(TENANT_ID);

            assertThat(result.get("totalConsumedLast30Days")).isEqualTo(0);
            assertThat((Map<?, ?>) result.get("breakdownByType")).isEmpty();
        }

        @Test
        @DisplayName("Returns null (CE falls back to its local ledger) when the install isn't registered")
        void nullUsageWhenNotRegistered() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.randomUUID());
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            assertThat(service.fetchCloudUsageSummary(TENANT_ID)).isNull();
            verifyNoInteractions(restTemplate);
        }
    }

    @Nested
    @DisplayName("fetchCloudUsageHistory")
    class FetchCloudUsageHistory {

        @Test
        @DisplayName("ALWAYS scopes the cloud history query to sourceType=CE_LLM_RELAY (this install's relay slice only)")
        void scopesHistoryToRelaySourceType() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            link.setRegisteredAt(clock.instant());
            link.setCachedAccessToken("cached-access-token");
            link.setTokenExpiresAt(clock.instant().plusSeconds(300));
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
            when(cloudLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            Map<String, Object> pageBody = Map.of("content", java.util.List.of(), "totalPages", 0, "number", 0);
            when(restTemplate.exchange(
                    org.mockito.ArgumentMatchers.contains("/credits/history?page=2&size=15&sourceType=CE_LLM_RELAY"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(pageBody));

            Map<String, Object> result = service.fetchCloudUsageHistory(TENANT_ID, 2, 15);

            assertThat(result).containsEntry("totalPages", 0);
        }

        @Test
        @DisplayName("Returns null (CE falls back to local) when the install isn't registered")
        void nullHistoryWhenNotRegistered() {
            CeCloudLinkEntity link = buildLink();
            link.setInstallId(UUID.randomUUID());
            when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));

            assertThat(service.fetchCloudUsageHistory(TENANT_ID, 0, 15)).isNull();
            verifyNoInteractions(restTemplate);
        }
    }

    private CeCloudLinkEntity buildLink() {
        CeCloudLinkEntity link = new CeCloudLinkEntity();
        link.setId(UUID.randomUUID());
        link.setTenantId(TENANT_ID);
        link.setCloudUserId(CLOUD_USER_ID);
        link.setCloudUsername(CLOUD_USERNAME);
        link.setEncryptedRefreshToken("encrypted-token");
        link.setLinkedAt(Instant.now());
        return link;
    }

    private static String jwtWithClaims(String sub, String username) {
        String payload = "{\"sub\":\"" + sub + "\",\"preferred_username\":\"" + username + "\"}";
        return "header."
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".signature";
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
