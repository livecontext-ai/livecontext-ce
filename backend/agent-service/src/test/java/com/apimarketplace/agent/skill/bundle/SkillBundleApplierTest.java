package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.domain.SkillBundleEntity;
import com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.repository.SkillBundleRepository;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CE-side apply contract for the skill bundle: insert new global rows, update existing ones,
 * soft-remove rows whose cloud skill disappeared, idempotency on a re-applied active version,
 * and the empty-payload guard. The inserted rows must be {@code is_global=true}, carry the
 * cloud-supplied {@code is_default_active}, the {@code source_bundle_key}, and an explicit
 * (non-null) organization so the {@code OrgScopedEntityListener} never fails on the scheduler
 * thread.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillBundleApplier - apply")
class SkillBundleApplierTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillBundleRepository bundleRepo;
    @Mock private SkillBundleSyncStatusRepository syncStatusRepo;

    private SkillBundleApplier applier;

    private static final String K1 = "11111111-1111-1111-1111-111111111111";
    private static final String K2 = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void setUp() {
        applier = new SkillBundleApplier(skillRepository, bundleRepo, syncStatusRepo, new ObjectMapper());
    }

    private SignedSkillBundle bundle(long version) {
        return new SignedSkillBundle(version, 1, "checksum", "sig", "key-id", "issuer", 1, 100, "ignored");
    }

    private byte[] payload(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("inserts a new global, default-active, cloud-owned row for an unseen key")
    void insertsNewGlobalSkill() {
        when(bundleRepo.findByVersion(1L)).thenReturn(Optional.empty());
        when(skillRepository.findBySourceBundleKey(K1)).thenReturn(Optional.empty());
        when(skillRepository.findBySourceBundleKeyIsNotNull()).thenReturn(List.of());
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncStatusRepo.findById(SkillBundleSyncStatusEntity.SINGLETON_ID)).thenReturn(Optional.empty());

        byte[] body = payload("{\"skills\":[{\"key\":\"" + K1 + "\",\"name\":\"App Builder\","
                + "\"description\":\"Builds apps\",\"instructions\":\"do it\",\"isDefaultActive\":true}]}");

        SkillBundleApplier.ApplyResult r = applier.apply(bundle(1L), body, "https://cloud");

        assertThat(r.status()).isEqualTo(SkillBundleApplier.Status.APPLIED);
        assertThat(r.inserted()).isEqualTo(1);
        assertThat(r.updated()).isZero();
        assertThat(r.removed()).isZero();

        ArgumentCaptor<SkillEntity> captor = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository).save(captor.capture());
        SkillEntity saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("App Builder");
        assertThat(saved.getInstructions()).isEqualTo("do it");
        assertThat(saved.getIsGlobal()).isTrue();
        assertThat(saved.getIsDefaultActive()).isTrue();
        assertThat(saved.getSourceBundleKey()).isEqualTo(K1);
        assertThat(saved.getOrganizationId())
                .as("explicit non-null org so OrgScopedEntityListener does not fail on the scheduler thread")
                .isNotNull();
    }

    @Test
    @DisplayName("updates an existing bundle row in place (content overwritten, re-globalised) and preserves its id")
    void updatesExistingSkill() {
        UUID rowId = UUID.randomUUID();
        SkillEntity existing = new SkillEntity("__skill_bundle__", "old", "old", "old", "old", true);
        existing.setId(rowId);
        existing.setOrganizationId("__skill_bundle__");
        existing.setSourceBundleKey(K1);
        existing.setIsGlobal(false); // was soft-removed; should come back

        when(bundleRepo.findByVersion(2L)).thenReturn(Optional.empty());
        when(skillRepository.findBySourceBundleKey(K1)).thenReturn(Optional.of(existing));
        when(skillRepository.findBySourceBundleKeyIsNotNull()).thenReturn(List.of(existing));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncStatusRepo.findById(SkillBundleSyncStatusEntity.SINGLETON_ID)).thenReturn(Optional.empty());

        byte[] body = payload("{\"skills\":[{\"key\":\"" + K1 + "\",\"name\":\"new name\","
                + "\"description\":\"new\",\"instructions\":\"new\",\"isDefaultActive\":false}]}");

        SkillBundleApplier.ApplyResult r = applier.apply(bundle(2L), body, "https://cloud");

        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.inserted()).isZero();
        assertThat(existing.getId()).as("id preserved -> user overrides survive").isEqualTo(rowId);
        assertThat(existing.getName()).isEqualTo("new name");
        assertThat(existing.getIsGlobal()).isTrue();
        assertThat(existing.getIsDefaultActive()).isFalse();
    }

    @Test
    @DisplayName("soft-removes a bundle row whose cloud skill vanished from the bundle (is_global=false, never hard-deleted)")
    void softRemovesAbsentSkill() {
        SkillEntity stale = new SkillEntity("__skill_bundle__", "Gone", "i", "i", "i", true);
        stale.setId(UUID.randomUUID());
        stale.setOrganizationId("__skill_bundle__");
        stale.setSourceBundleKey(K2);  // not in the incoming bundle
        stale.setIsGlobal(true);

        when(bundleRepo.findByVersion(3L)).thenReturn(Optional.empty());
        when(skillRepository.findBySourceBundleKey(K1)).thenReturn(Optional.empty());
        when(skillRepository.findBySourceBundleKeyIsNotNull()).thenReturn(List.of(stale));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncStatusRepo.findById(SkillBundleSyncStatusEntity.SINGLETON_ID)).thenReturn(Optional.empty());

        byte[] body = payload("{\"skills\":[{\"key\":\"" + K1 + "\",\"name\":\"Kept\","
                + "\"description\":\"d\",\"instructions\":\"i\",\"isDefaultActive\":true}]}");

        SkillBundleApplier.ApplyResult r = applier.apply(bundle(3L), body, "https://cloud");

        assertThat(r.inserted()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(1);
        assertThat(stale.getIsGlobal()).as("soft-removed").isFalse();
        assertThat(stale.getIsActive()).isFalse();
        verify(skillRepository, never()).delete(any(SkillEntity.class));
    }

    @Test
    @DisplayName("re-applying the already-active version is a no-op (ALREADY_APPLIED) but still refreshes the OK heartbeat")
    void idempotentOnActiveVersion() {
        SkillBundleEntity active = new SkillBundleEntity();
        active.setVersion(5L);
        active.setActive(true);
        when(bundleRepo.findByVersion(5L)).thenReturn(Optional.of(active));
        when(syncStatusRepo.findById(SkillBundleSyncStatusEntity.SINGLETON_ID)).thenReturn(Optional.empty());

        SkillBundleApplier.ApplyResult r = applier.apply(bundle(5L), payload("{}"), "https://cloud");

        assertThat(r.status()).isEqualTo(SkillBundleApplier.Status.ALREADY_APPLIED);
        verify(skillRepository, never()).save(any(SkillEntity.class));
        verify(syncStatusRepo).save(any(SkillBundleSyncStatusEntity.class));
    }

    @Test
    @DisplayName("refuses an empty skills array - never mass-soft-removes the global set off a bad fetch")
    void refusesEmptyPayload() {
        when(bundleRepo.findByVersion(9L)).thenReturn(Optional.empty());

        SkillBundleApplier.ApplyResult r = applier.apply(bundle(9L), payload("{\"skills\":[]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(SkillBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("empty");
        verify(skillRepository, never()).save(any(SkillEntity.class));
        verify(skillRepository, never()).findBySourceBundleKeyIsNotNull();
    }

    @Test
    @DisplayName("a skill entry without a 'key' fails cleanly (the upsert key is mandatory)")
    void refusesEntryWithoutKey() {
        when(bundleRepo.findByVersion(7L)).thenReturn(Optional.empty());

        byte[] body = payload("{\"skills\":[{\"name\":\"NoKey\",\"description\":\"d\","
                + "\"instructions\":\"i\",\"isDefaultActive\":true}]}");

        SkillBundleApplier.ApplyResult r = applier.apply(bundle(7L), body, "https://cloud");

        assertThat(r.status()).isEqualTo(SkillBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("no 'key'");
        verify(skillRepository, never()).save(any(SkillEntity.class));
    }

    @Test
    @DisplayName("missing 'skills' array fails cleanly rather than NPE-ing")
    void refusesMissingSkillsArray() {
        when(bundleRepo.findByVersion(9L)).thenReturn(Optional.empty());
        // Other lookups must not run.
        lenient().when(skillRepository.findBySourceBundleKeyIsNotNull()).thenReturn(List.of());

        SkillBundleApplier.ApplyResult r = applier.apply(bundle(9L), payload("{\"other\":1}"), "https://cloud");

        assertThat(r.status()).isEqualTo(SkillBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("no 'skills'");
    }
}
