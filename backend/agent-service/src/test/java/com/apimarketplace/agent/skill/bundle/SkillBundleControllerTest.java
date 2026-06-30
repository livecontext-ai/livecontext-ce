package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleSigner;
import com.apimarketplace.agent.domain.SkillBundleEntity;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
import com.apimarketplace.auth.client.AuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST surface contract: admin endpoints gated by {@code X-User-Roles}, CE downloads gated by
 * an active cloud link ({@code userOwnsActiveCeLink}), and the {@code sync-now} 503 on an
 * instance without the scheduler bean (i.e. cloud).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillBundleController")
class SkillBundleControllerTest {

    @Mock private SkillBundleService bundleService;
    @Mock private CatalogBundleSigner signer;
    @Mock private SkillBundleSyncStatusRepository syncStatusRepo;
    @Mock private ObjectProvider<SkillBundleSyncScheduler> schedulerProvider;
    @Mock private AuthClient authClient;

    private SkillBundleController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillBundleController(bundleService, signer, syncStatusRepo, schedulerProvider, authClient);
    }

    private SkillBundleEntity entity() {
        SkillBundleEntity e = new SkillBundleEntity();
        e.setId(1L);
        e.setVersion(100L);
        e.setSchemaVersion(1);
        e.setChecksum("c");
        e.setSigningKeyId("k");
        e.setIssuer("i");
        e.setSkillCount(3);
        e.setRawBytesSize(50);
        e.setActive(false);
        return e;
    }

    @Test
    @DisplayName("build: non-admin caller is 403")
    void buildDeniedForNonAdmin() {
        ResponseEntity<?> resp = controller.buildBundle("USER");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(bundleService, never()).buildBundle();
    }

    @Test
    @DisplayName("build: admin caller builds and gets the bundle view")
    void buildAllowedForAdmin() {
        when(bundleService.buildBundle()).thenReturn(entity());
        ResponseEntity<?> resp = controller.buildBundle("USER,ADMIN");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("build: an empty/unconfigured snapshot is surfaced as 400, not 500")
    void buildBadRequestOnIllegalState() {
        when(bundleService.buildBundle()).thenThrow(new IllegalStateException("no global skills"));
        ResponseEntity<?> resp = controller.buildBundle("ADMIN");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("latest: no cloud identity -> 401")
    void latestUnauthenticated() {
        ResponseEntity<?> resp = controller.latestSignedBundle(null, "install-1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(bundleService, never()).getActiveSignedBundle();
    }

    @Test
    @DisplayName("latest: a cloud user that does not own an active link -> 403")
    void latestNotLinked() {
        when(authClient.userOwnsActiveCeLink("cloud-u", "install-1")).thenReturn(false);
        ResponseEntity<?> resp = controller.latestSignedBundle("cloud-u", "install-1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(bundleService, never()).getActiveSignedBundle();
    }

    @Test
    @DisplayName("latest: a linked install gets the active signed bundle (200)")
    void latestLinkedServesBundle() {
        when(authClient.userOwnsActiveCeLink("cloud-u", "install-1")).thenReturn(true);
        when(bundleService.getActiveSignedBundle())
                .thenReturn(Optional.of(new SignedSkillBundle(100, 1, "c", "s", "k", "i", 3, 50, "p")));
        ResponseEntity<?> resp = controller.latestSignedBundle("cloud-u", "install-1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("latest: a linked install with no active bundle -> 404")
    void latestLinkedNoBundle() {
        when(authClient.userOwnsActiveCeLink("cloud-u", "install-1")).thenReturn(true);
        when(bundleService.getActiveSignedBundle()).thenReturn(Optional.empty());
        ResponseEntity<?> resp = controller.latestSignedBundle("cloud-u", "install-1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("signing-key: public; returns the pinned key material, 503 when unconfigured")
    void signingKey() {
        when(signer.publicKeyBase64()).thenReturn("PUBKEY");
        lenient().when(signer.keyId()).thenReturn("k1");
        lenient().when(signer.issuer()).thenReturn("issuer");
        assertThat(controller.signingKey().getStatusCode()).isEqualTo(HttpStatus.OK);

        when(signer.publicKeyBase64()).thenReturn(null);
        assertThat(controller.signingKey().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("version download: no cloud identity -> 401 (same gate as /latest)")
    void versionGateUnauthenticated() {
        ResponseEntity<?> resp = controller.signedBundleByVersion(null, "install-1", 5L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(bundleService, never()).getSignedBundleByVersion(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("activate: unknown bundle id -> 404")
    void activateNotFound() {
        when(bundleService.activateBundle(9L)).thenThrow(new IllegalArgumentException("Bundle not found: 9"));
        ResponseEntity<?> resp = controller.activateBundle("ADMIN", 9L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("sync-now: admin on a CE instance triggers the scheduler tick and returns the status (200)")
    void syncNowTriggersTick() {
        SkillBundleSyncScheduler scheduler = org.mockito.Mockito.mock(SkillBundleSyncScheduler.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);
        when(syncStatusRepo.findById(com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.syncNow("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(scheduler).tick();
    }

    @Test
    @DisplayName("sync-now: 503 on an instance where the scheduler bean is absent (cloud)")
    void syncNowNoScheduler() {
        when(schedulerProvider.getIfAvailable()).thenReturn(null);
        ResponseEntity<?> resp = controller.syncNow("ADMIN");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("sync-now: non-admin caller is 403")
    void syncNowDeniedForNonAdmin() {
        ResponseEntity<?> resp = controller.syncNow("USER");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
