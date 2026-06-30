package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.repository.UserSkillOverrideRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V374 - a global skill applied from the cloud SKILL BUNDLE ({@code source_bundle_key} is
 * NON-NULL) is read-only on a CE install: the cloud owns its content and every re-sync
 * overwrites it, so a local edit/delete must be refused (it would be silently reverted).
 * End users still hide it for themselves via the per-user override, which goes through a
 * different method and is NOT blocked here.
 *
 * <p>Each test asserts ONE branch: edit blocked, delete blocked, and that the gate fires
 * BEFORE the admin gate (so even an admin is refused) and only on bundle-owned rows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService - V374 bundle skill read-only gate")
class SkillServiceBundleReadOnlyTest {

    @Mock private SkillRepository skillRepository;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private UserSkillOverrideRepository userSkillOverrideRepository;

    @InjectMocks private SkillService skillService;

    private static final String ORG = "org_X";
    private static final UUID SKILL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(skillService, "maxSkillsPerAgent", 10);
    }

    private SkillEntity bundleSkill() {
        SkillEntity s = new SkillEntity("__skill_bundle__", "Global", "D", "icon", "i", true);
        s.setId(SKILL_ID);
        s.setOrganizationId("__skill_bundle__");
        s.setIsGlobal(true);
        s.setIsDefaultActive(true);
        s.setSourceBundleKey(SKILL_ID.toString()); // cloud-managed bundle row
        return s;
    }

    @Test
    @DisplayName("updateSkill refuses a bundle-managed global skill, even for an admin caller, before reaching the admin gate")
    void updateBlockedForBundleSkillEvenAdmin() {
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG))
                .thenReturn(Optional.of(bundleSkill()));

        assertThatThrownBy(() -> skillService.updateSkill(
                SKILL_ID, "__skill_bundle__", ORG,
                "hacked name", null, null, "hacked instructions",
                null, null, null,
                true /* callerIsAdmin */))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");

        verify(skillRepository, never()).save(any(SkillEntity.class));
    }

    @Test
    @DisplayName("deleteSkill refuses a bundle-managed global skill, even for an admin caller")
    void deleteBlockedForBundleSkillEvenAdmin() {
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG))
                .thenReturn(Optional.of(bundleSkill()));

        assertThatThrownBy(() -> skillService.deleteSkill(
                SKILL_ID, "__skill_bundle__", ORG, true /* callerIsAdmin */))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");

        verify(skillRepository, never()).delete(any(SkillEntity.class));
    }

    @Test
    @DisplayName("a user CAN still hide a read-only bundle skill for themselves - setUserSkillOverride is allowed (resolves the global via the fallback) and is NOT blocked by the read-only gate")
    void userCanHideBundleSkillViaOverride() {
        SkillEntity bundle = bundleSkill();
        // The override visibility gate uses the global fallback (strict-scope misses the
        // synthetic bundle org), so the bundle skill is reachable and the override upserts.
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG)).thenReturn(Optional.empty());
        when(skillRepository.findById(SKILL_ID)).thenReturn(Optional.of(bundle));
        when(userSkillOverrideRepository.findByUserIdAndSkillId("user_1", SKILL_ID)).thenReturn(Optional.empty());
        when(userSkillOverrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        skillService.setUserSkillOverride("user_1", SKILL_ID, false, ORG);

        org.mockito.ArgumentCaptor<com.apimarketplace.agent.domain.UserSkillOverrideEntity> captor =
                org.mockito.ArgumentCaptor.forClass(com.apimarketplace.agent.domain.UserSkillOverrideEntity.class);
        verify(userSkillOverrideRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getActive()).isFalse();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getSkillId()).isEqualTo(SKILL_ID);
    }

    @Test
    @DisplayName("updateSkill still allows editing a normal (non-bundle) skill - the gate only fires when source_bundle_key is set")
    void updateAllowedForNonBundleSkill() {
        SkillEntity normal = new SkillEntity("owner", "S", "D", "icon", "i", true);
        normal.setId(SKILL_ID);
        normal.setOrganizationId(ORG);
        // source_bundle_key stays null -> not a bundle row -> editable.
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG))
                .thenReturn(Optional.of(normal));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillEntity saved = skillService.updateSkill(
                SKILL_ID, "owner", ORG,
                "renamed", null, null, null,
                null, null, null, false);

        org.assertj.core.api.Assertions.assertThat(saved.getName()).isEqualTo("renamed");
    }
}
