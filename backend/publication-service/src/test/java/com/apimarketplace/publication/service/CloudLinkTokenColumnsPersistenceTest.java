package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit B #2: the cloud realm rotates refresh tokens (each one is spent on use). Before the fix,
 * every writer of the link row (register, heartbeat, the LLM / catalog source toggles) saved an
 * entity instance it had loaded BEFORE {@code getCloudAccessToken} refreshed, so the save put the
 * spent refresh token back and the next refresh ended the link's session.
 *
 * <p>Runs against a real EntityManager with NO surrounding transaction, so every repository call
 * gets its own persistence context and every read is a DISTINCT instance, like the heartbeat
 * scheduler and concurrent requests in production. The mocked-repository tests share one
 * instance between every read and cannot see this.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = CloudLinkTokenColumnsPersistenceTest.JpaOnly.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ce_cloud_link_tokens;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "INIT=CREATE SCHEMA IF NOT EXISTS publication",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("Cloud link token columns: a stale entity save never restores a spent refresh token")
class CloudLinkTokenColumnsPersistenceTest {

    @Configuration
    @EntityScan(basePackageClasses = CeCloudLinkEntity.class)
    @EnableJpaRepositories(
            basePackageClasses = CeCloudLinkRepository.class,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = CeCloudLinkRepository.class))
    static class JpaOnly {
    }

    private static final Long TENANT_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final String CLOUD_API = "https://livecontext.ai/api";
    private static final String TOKEN_URL = "https://keycloak.example.com/realms/test/protocol/openid-connect/token";

    @Autowired
    private CeCloudLinkRepository repository;

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private CloudLinkService service;

    /** Refresh tokens presented to the fake Keycloak, in order; a second presentation is refused. */
    private final List<String> presented = new ArrayList<>();
    private final Set<String> spent = new HashSet<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new CloudLinkService(repository,
                "https://keycloak.example.com/realms/test", "test-client-id", "http://localhost/callback",
                "test-encryption-key-for-unit-tests", CLOUD_API, "1.4.0-test", new ObjectMapper(),
                restTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
        // Keycloak with rotation: rt-N answers at-N + rt-(N+1); a spent token is refused.
        when(restTemplate.exchange(eq(TOKEN_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<MultiValueMap<String, String>> request = invocation.getArgument(2);
                    String token = request.getBody().getFirst("refresh_token");
                    presented.add(token);
                    if (!spent.add(token)) {
                        throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, "invalid_grant");
                    }
                    int generation = Integer.parseInt(token.substring("rt-".length()));
                    return ResponseEntity.ok(Map.of(
                            "access_token", "at-" + generation,
                            "refresh_token", "rt-" + (generation + 1),
                            "expires_in", 900));
                });
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    /** The row as a fresh link holds it: refresh token rt-1, cached access token already expired. */
    private void seedLink(Instant registeredAt) {
        CeCloudLinkEntity link = new CeCloudLinkEntity();
        link.setTenantId(TENANT_ID);
        link.setOrganizationId("org-test");
        link.setCloudUserId("cloud-user");
        link.setCloudUsername("cloud user");
        link.setEncryptedRefreshToken(ReflectionTestUtils.invokeMethod(service, "encrypt", "rt-1"));
        link.setCachedAccessToken("at-0");
        link.setTokenExpiresAt(NOW.minusSeconds(60));
        link.setLinkedAt(NOW.minusSeconds(3600));
        link.setRegisteredAt(registeredAt);
        repository.save(link);
    }

    private CeCloudLinkEntity row() {
        return repository.findByTenantId(TENANT_ID).orElseThrow();
    }

    private String storedRefreshToken() {
        return ReflectionTestUtils.invokeMethod(service, "decrypt", row().getEncryptedRefreshToken());
    }

    @Test
    @DisplayName("a refresh through one instance, then a save of an instance loaded before it, keeps the rotated token")
    void staleInstanceSaveDoesNotRestoreTheSpentRefreshToken() {
        seedLink(NOW.minusSeconds(600));
        CeCloudLinkEntity stale = row(); // instance B, holds rt-1

        assertThat(service.getCloudAccessToken(TENANT_ID)).isEqualTo("at-1"); // spends rt-1, stores rt-2

        stale.setLabel("renamed");
        repository.save(stale);

        CeCloudLinkEntity stored = row();
        assertThat(stored.getLabel()).as("the stale save still writes its own field").isEqualTo("renamed");
        assertThat(storedRefreshToken()).as("the rotated refresh token survives the stale save").isEqualTo("rt-2");
        assertThat(stored.getCachedAccessToken()).isEqualTo("at-1");
        assertThat(stored.getTokenExpiresAt()).isEqualTo(NOW.plusSeconds(870));
    }

    @Test
    @DisplayName("registerWithCloud on an instance loaded before its own refresh keeps the rotated token")
    void registerWithCloudKeepsTheRotatedToken() {
        seedLink(null);
        CeCloudLinkEntity stale = row();
        when(restTemplate.postForEntity(eq(CLOUD_API + "/ce-link/register"), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok().build());

        assertThat(service.registerWithCloud(stale)).isEqualTo(CloudLinkService.RegisterOutcome.REGISTERED);

        assertThat(row().getRegisteredAt()).isEqualTo(NOW);
        assertThat(storedRefreshToken()).isEqualTo("rt-2");
        // The next refresh presents the rotated token, never the spent one.
        assertThat(service.getCloudAccessToken(TENANT_ID)).isEqualTo("at-1"); // cached, still valid
        assertThat(presented).containsExactly("rt-1");
    }

    @Test
    @DisplayName("a heartbeat on a scheduler-loaded instance keeps the rotated token")
    void heartbeatKeepsTheRotatedToken() {
        seedLink(NOW.minusSeconds(600));
        CeCloudLinkEntity stale = row();
        when(restTemplate.postForEntity(any(String.class), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        assertThat(service.sendHeartbeat(stale)).isEqualTo(CloudLinkService.HeartbeatOutcome.OK);

        assertThat(storedRefreshToken()).isEqualTo("rt-2");
        assertThat(row().getLastUsedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a 410 heartbeat drops the cached access token but keeps the rotated refresh token")
    void revokedHeartbeatClearsOnlyTheCachedAccessToken() {
        seedLink(NOW.minusSeconds(600));
        CeCloudLinkEntity stale = row();
        when(restTemplate.postForEntity(any(String.class), any(), eq(Void.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.GONE, "Gone", null, null, null));

        assertThat(service.sendHeartbeat(stale)).isEqualTo(CloudLinkService.HeartbeatOutcome.REVOKED);

        CeCloudLinkEntity stored = row();
        assertThat(stored.getRegisteredAt()).isNull();
        assertThat(stored.getCachedAccessToken()).isNull();
        assertThat(stored.getTokenExpiresAt()).isNull();
        assertThat(storedRefreshToken()).isEqualTo("rt-2");
    }

    @Test
    @DisplayName("setCatalogSource(CLOUD) saves the instance it loaded before the entitlement refresh, without the spent token")
    void sourceToggleKeepsTheRotatedToken() {
        seedLink(NOW.minusSeconds(600));
        when(restTemplate.exchange(eq(CLOUD_API + "/ce-link/" + row().getInstallId() + "/entitlements"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().createObjectNode().put("planCode", "PRO")));

        service.setCatalogSource(TENANT_ID, com.apimarketplace.agent.cloud.CloudLlmSource.CLOUD);

        assertThat(row().getCatalogSource()).isEqualTo("CLOUD");
        assertThat(storedRefreshToken()).isEqualTo("rt-2");
    }

    @Test
    @DisplayName("setLlmSource(CLOUD) saves the instance it loaded before the entitlement refresh, without the spent token")
    void llmSourceToggleKeepsTheRotatedToken() {
        seedLink(NOW.minusSeconds(600));
        when(restTemplate.exchange(eq(CLOUD_API + "/ce-link/" + row().getInstallId() + "/entitlements"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().createObjectNode().put("planCode", "PRO")));

        service.setLlmSource(TENANT_ID, com.apimarketplace.agent.cloud.CloudLlmSource.CLOUD);

        assertThat(row().getLlmSource()).isEqualTo("CLOUD");
        assertThat(storedRefreshToken()).isEqualTo("rt-2");
    }
}
