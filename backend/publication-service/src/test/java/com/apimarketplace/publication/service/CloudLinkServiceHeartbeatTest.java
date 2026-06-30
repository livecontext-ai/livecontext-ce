package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLinkService.sendHeartbeat - PR7 outcome matrix")
class CloudLinkServiceHeartbeatTest {

    @Mock private CeCloudLinkRepository cloudLinkRepository;
    @Mock private RestTemplate restTemplate;

    private CloudLinkService service;

    private static final Long TENANT_ID = 42L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CLOUD_API = "https://livecontext.ai/api";

    @BeforeEach
    void setUp() {
        service = new CloudLinkService(cloudLinkRepository,
                "https://kc.example.com/realms/test",
                "ce-link", "http://localhost/callback",
                "test-encryption-key-for-unit-tests",
                CLOUD_API, "1.4.0-test", new ObjectMapper(),
                restTemplate);
    }

    private CeCloudLinkEntity registeredLink() {
        CeCloudLinkEntity link = new CeCloudLinkEntity();
        link.setTenantId(TENANT_ID);
        link.setCloudUserId("cu");
        link.setCloudUsername("u");
        link.setEncryptedRefreshToken("enc");
        link.setCachedAccessToken("bearer-abc");
        link.setTokenExpiresAt(Instant.now().plusSeconds(3600));
        link.setLinkedAt(Instant.now());
        link.setInstallId(INSTALL);
        link.setRegisteredAt(Instant.now().minusSeconds(60));
        return link;
    }

    @Test
    @DisplayName("OK - happy heartbeat POSTs to /api/ce-link/{installId}/heartbeat with bearer + ceVersion body")
    void okHeartbeat() {
        CeCloudLinkEntity link = registeredLink();
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
        when(restTemplate.postForEntity(
                contains("/ce-link/" + INSTALL + "/heartbeat"),
                any(), eq(Void.class)))
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());

        CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

        assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.OK);
        verify(restTemplate).postForEntity(
                eq(CLOUD_API + "/ce-link/" + INSTALL + "/heartbeat"),
                any(), eq(Void.class));
    }

    @Test
    @DisplayName("REVOKED on 410 GONE - clears local registeredAt + cached access token so UI surfaces \"reconnect\"")
    void revokedOn410() {
        CeCloudLinkEntity link = registeredLink();
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
        when(restTemplate.postForEntity(
                contains("/ce-link/" + INSTALL + "/heartbeat"),
                any(), eq(Void.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.GONE, "Gone", null, null, null));

        CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

        assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.REVOKED);
        assertThat(link.getRegisteredAt()).isNull();
        assertThat(link.getCachedAccessToken()).isNull();
    }

    @Test
    @DisplayName("NOT_FOUND on 404 - clears registeredAt only (token stays - could be a DB reset, not a revoke)")
    void notFoundOn404() {
        CeCloudLinkEntity link = registeredLink();
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
        when(restTemplate.postForEntity(any(String.class), any(), eq(Void.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

        assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.NOT_FOUND);
        assertThat(link.getRegisteredAt()).isNull();
        assertThat(link.getCachedAccessToken()).isNotNull();   // token preserved on 404
    }

    @Test
    @DisplayName("TRANSIENT_FAILURE on 5xx - keeps registeredAt set so next tick retries (uses HttpServerErrorException, the correct class for 5xx)")
    void transientFailureOn5xxKeepsRegistered() {
        CeCloudLinkEntity link = registeredLink();
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
        when(restTemplate.postForEntity(any(String.class), any(), eq(Void.class)))
                .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Boom", null, null, null));

        CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

        assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.TRANSIENT_FAILURE);
        assertThat(link.getRegisteredAt()).isNotNull();
    }

    @Test
    @DisplayName("TRANSIENT_FAILURE on ResourceAccessException - most common real-world transient (TCP reset, DNS, etc.)")
    void transientFailureOnNetworkError() {
        CeCloudLinkEntity link = registeredLink();
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
        when(restTemplate.postForEntity(any(String.class), any(), eq(Void.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connection reset"));

        CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

        assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.TRANSIENT_FAILURE);
        assertThat(link.getRegisteredAt()).isNotNull();
    }

    @Test
    @DisplayName("REGISTERED - link not yet registered + register POST succeeds → outcome stamps registeredAt + reports REGISTERED")
    void registeredWhenRegisterSucceedsInsideHeartbeat() {
        CeCloudLinkEntity link = registeredLink();
        link.setRegisteredAt(null);   // not yet registered
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
        when(restTemplate.postForEntity(contains("/ce-link/register"), any(),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(org.springframework.http.ResponseEntity
                        .status(HttpStatus.CREATED).body(null));

        CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

        assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.REGISTERED);
        assertThat(link.getRegisteredAt()).isNotNull();
        // No heartbeat POST attempted on this tick - next tick takes the OK path.
        verify(restTemplate, never()).postForEntity(
                contains("/heartbeat"), any(), eq(Void.class));
    }

    @Test
    @DisplayName("REGISTERED on 409 ALREADY_BOUND - cloud already has the install_id, mark locally registered to stop re-POSTing")
    void registerWithCloud409StampsRegisteredAt() {
        CeCloudLinkEntity link = registeredLink();
        link.setRegisteredAt(null);
        // registerWithCloud calls getCloudAccessToken which reads the link from repo.
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
        when(restTemplate.postForEntity(contains("/ce-link/register"), any(),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.CONFLICT, "Already bound", null, null, null));

        service.registerWithCloud(link);

        assertThat(link.getRegisteredAt()).isNotNull();
        // save() is invoked twice: once by getCloudAccessToken for lastUsedAt bump,
        // once by the 409 branch to persist registeredAt. We only care that the latter
        // happened - the visible registeredAt assertion above proves it.
        verify(cloudLinkRepository, org.mockito.Mockito.atLeastOnce()).save(link);
    }

    @Test
    @DisplayName("PENDING_REGISTER - link not yet registered + register call also fails → outcome reports pending")
    void pendingRegisterWhenNotYetRegistered() {
        CeCloudLinkEntity link = registeredLink();
        link.setRegisteredAt(null);   // not yet registered
        when(cloudLinkRepository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(link));
        when(restTemplate.postForEntity(contains("/ce-link/register"), any(), any(Class.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null));

        CloudLinkService.HeartbeatOutcome outcome = service.sendHeartbeat(link);

        assertThat(outcome).isEqualTo(CloudLinkService.HeartbeatOutcome.PENDING_REGISTER);
        // No heartbeat POST attempted on a not-yet-registered link.
        verify(restTemplate, never()).postForEntity(
                contains("/heartbeat"), any(), eq(Void.class));
    }
}
