package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.domain.UserSkillOverrideEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.repository.UserSkillOverrideRepository;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V275/V276 (2026-05-21) - coverage for the DB-backed default-active layer
 * and its per-user override.
 *
 * <p>Each test asserts ONE behavior: either the new flag flips, the override
 * upserts, the effective resolver picks the right value, or the visibility
 * gate rejects a hostile request. The names encode the contract that was
 * broken without V276 (legacy localStorage seed couldn't survive multi-device
 * use and couldn't express "this user opts out of the global default").
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService - V275 default-active + V276 per-user override")
class SkillServiceUserOverrideTest {

    @Mock private SkillRepository skillRepository;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AuthClient authClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private UserSkillOverrideRepository userSkillOverrideRepository;

    @InjectMocks private SkillService skillService;

    private static final String USER = "user_42";
    private static final String ORG = "org_X";
    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID SKILL_ID_2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(skillService, "maxSkillsPerAgent", 10);
        // The authClient is FIELD-injected in production (optional bean) -
        // @InjectMocks only does constructor injection when a constructor
        // exists, so wire the mock in manually.
        ReflectionTestUtils.setField(skillService, "authClient", authClient);
        // V276 read paths require an active workspace, but every method under
        // test here takes the organizationId as a direct parameter - so the
        // production currentRequestOrganizationId() lookup is bypassed and no
        // thread-local rigging is needed.
    }

    private SkillEntity skillRow(UUID id, boolean isDefaultActive, boolean isGlobal) {
        SkillEntity s = new SkillEntity("owner", "S", "D", "icon", "i", true);
        s.setId(id);
        s.setOrganizationId(ORG);
        s.setIsDefaultActive(isDefaultActive);
        s.setIsGlobal(isGlobal);
        return s;
    }

    // ===== updateSkill(isDefaultActive) =====

    @Test
    @DisplayName("updateSkill flips is_default_active when the new 11-arg overload is called")
    void updateSkillTogglesDefaultActive() {
        SkillEntity existing = skillRow(SKILL_ID, false, false);
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG))
                .thenReturn(Optional.of(existing));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillEntity saved = skillService.updateSkill(
                SKILL_ID, "owner", ORG,
                null, null, null, null,
                null,        // isActive
                null,        // isGlobal
                true,        // isDefaultActive ← flip
                false        // callerIsAdmin
        );

        assertThat(saved.getIsDefaultActive()).isTrue();
    }

    @Test
    @DisplayName("updateSkill leaves is_default_active untouched when the parameter is null")
    void updateSkillNullDefaultActivePreservesValue() {
        SkillEntity existing = skillRow(SKILL_ID, true, false);
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG))
                .thenReturn(Optional.of(existing));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillEntity saved = skillService.updateSkill(
                SKILL_ID, "owner", ORG,
                "renamed", null, null, null,
                null, null, null, false
        );

        assertThat(saved.getIsDefaultActive()).isTrue();  // unchanged
    }

    // ===== setUserSkillOverride / clearUserSkillOverride =====

    @Test
    @DisplayName("setUserSkillOverride upserts the row with the requested active value")
    void setUserSkillOverrideUpserts() {
        SkillEntity visible = skillRow(SKILL_ID, true, false);
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG))
                .thenReturn(Optional.of(visible));
        when(userSkillOverrideRepository.findByUserIdAndSkillId(USER, SKILL_ID))
                .thenReturn(Optional.empty());
        when(userSkillOverrideRepository.save(any(UserSkillOverrideEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        skillService.setUserSkillOverride(USER, SKILL_ID, false, ORG);

        ArgumentCaptor<UserSkillOverrideEntity> captor = ArgumentCaptor.forClass(UserSkillOverrideEntity.class);
        verify(userSkillOverrideRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER);
        assertThat(captor.getValue().getSkillId()).isEqualTo(SKILL_ID);
        assertThat(captor.getValue().getActive()).isFalse();
    }

    @Test
    @DisplayName("setUserSkillOverride rejects when the user cannot see the skill - no row leaks")
    void setUserSkillOverrideRejectsHiddenSkill() {
        // Strict-scope misses (different org) + global fallback misses → not visible.
        when(skillRepository.findByIdAndOrganizationIdStrict(SKILL_ID, ORG))
                .thenReturn(Optional.empty());
        when(skillRepository.findById(SKILL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                skillService.setUserSkillOverride(USER, SKILL_ID, true, ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill not found");

        verify(userSkillOverrideRepository, never()).save(any());
    }

    @Test
    @DisplayName("clearUserSkillOverride is idempotent - no-op when nothing to delete")
    void clearUserSkillOverrideIsIdempotent() {
        when(userSkillOverrideRepository.deleteByUserIdAndSkillId(USER, SKILL_ID)).thenReturn(0);

        skillService.clearUserSkillOverride(USER, SKILL_ID);

        verify(userSkillOverrideRepository).deleteByUserIdAndSkillId(USER, SKILL_ID);
    }

    // ===== listEffectiveDefaultActiveForUser =====

    @Test
    @DisplayName("listEffectiveDefaultActiveForUser uses override when present (override.active=false wins over is_default_active=true)")
    void effectiveResolverOverrideWinsOverDefault() {
        SkillEntity defaultOn = skillRow(SKILL_ID, true, false);   // default true
        SkillEntity defaultOff = skillRow(SKILL_ID_2, false, false); // default false
        when(skillRepository.findVisibleForOrganization(ORG))
                .thenReturn(List.of(defaultOn, defaultOff));
        UserSkillOverrideEntity override = new UserSkillOverrideEntity(USER, SKILL_ID, false);
        when(userSkillOverrideRepository.findByUserId(USER)).thenReturn(List.of(override));

        List<SkillEntity> effective = skillService.listEffectiveDefaultActiveForUser(USER, USER, ORG);

        // defaultOn → overridden to false → excluded.
        // defaultOff → no override → false → excluded.
        assertThat(effective).isEmpty();
    }

    @Test
    @DisplayName("listEffectiveDefaultActiveForUser uses override when present (override.active=true wins over is_default_active=false)")
    void effectiveResolverOverrideOptsIn() {
        SkillEntity defaultOff = skillRow(SKILL_ID, false, false);
        when(skillRepository.findVisibleForOrganization(ORG))
                .thenReturn(List.of(defaultOff));
        UserSkillOverrideEntity override = new UserSkillOverrideEntity(USER, SKILL_ID, true);
        when(userSkillOverrideRepository.findByUserId(USER)).thenReturn(List.of(override));

        List<SkillEntity> effective = skillService.listEffectiveDefaultActiveForUser(USER, USER, ORG);

        assertThat(effective).extracting(SkillEntity::getId).containsExactly(SKILL_ID);
    }

    @Test
    @DisplayName("listEffectiveDefaultActiveForUser falls back to is_default_active when no override row exists")
    void effectiveResolverFallsBackToSkillDefault() {
        SkillEntity defaultOn = skillRow(SKILL_ID, true, false);
        when(skillRepository.findVisibleForOrganization(ORG))
                .thenReturn(List.of(defaultOn));
        when(userSkillOverrideRepository.findByUserId(USER)).thenReturn(List.of());

        List<SkillEntity> effective = skillService.listEffectiveDefaultActiveForUser(USER, USER, ORG);

        assertThat(effective).extracting(SkillEntity::getId).containsExactly(SKILL_ID);
    }

    @Test
    @DisplayName("getUserOverrides returns a (skillId → active) map keyed by the user's rows")
    void getUserOverridesShapesAsMap() {
        UserSkillOverrideEntity row1 = new UserSkillOverrideEntity(USER, SKILL_ID, true);
        UserSkillOverrideEntity row2 = new UserSkillOverrideEntity(USER, SKILL_ID_2, false);
        when(userSkillOverrideRepository.findByUserId(USER)).thenReturn(List.of(row1, row2));

        Map<UUID, Boolean> overrides = skillService.getUserOverrides(USER);

        assertThat(overrides).containsEntry(SKILL_ID, true).containsEntry(SKILL_ID_2, false).hasSize(2);
    }

    // ===== seedDefaultSkills - dormant while no built-in defaults are registered =====
    //
    // The "Deep Research" built-in was removed (DefaultSkillsProvider registers
    // nothing now; global skills come from the cloud skill bundle), so the whole
    // seeding path is a no-op until a built-in is re-added. These tests pin that
    // dormant contract; the design guard in the first test fails loudly if a
    // built-in reappears, so the richer per-row seed coverage gets restored then.

    @Test
    @DisplayName("seedDefaultSkills is a no-op (returns 0, writes nothing) while DefaultSkillsProvider registers no built-in defaults")
    void seedDefaultSkillsIsNoOpWhileNoBuiltInsRegistered() {
        // Design guard: no built-in default is registered. If one is re-added,
        // this fails so the seed coverage (default-active, personal-org stamp,
        // concurrent-insert skip) is revisited instead of silently dropped.
        assertThat(com.apimarketplace.agent.skills.DefaultSkillsProvider.getAll()).isEmpty();

        int seeded = skillService.seedDefaultSkills("tenant_new");

        assertThat(seeded).isZero();
        verify(skillRepository, never()).save(any(SkillEntity.class));
        verify(skillRepository, never()).saveAndFlush(any(SkillEntity.class));
    }

    @Test
    @DisplayName("listSkills does NOT seed on the org branch while no built-in defaults are registered - it just lists the org's visible skills")
    void listSkillsDoesNotSeedWhileNoBuiltInsRegistered() {
        // expectedDefaults = DefaultSkillsProvider.getAll().size() = 0, so the
        // seed branch (existingDefaults < expectedDefaults) can never fire.
        when(skillRepository.countByTenantIdAndDefaultKeyIsNotNull(USER)).thenReturn(0L);
        when(skillRepository.findVisibleForOrganization(ORG)).thenReturn(List.of());

        skillService.listSkills(USER, ORG);

        verify(skillRepository, never()).save(any(SkillEntity.class));
        verify(skillRepository).findVisibleForOrganization(ORG);
    }
}
