package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationService Tests")
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationMemberRepository memberRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Captor
    private ArgumentCaptor<Organization> organizationCaptor;

    @Captor
    private ArgumentCaptor<OrganizationMember> memberCaptor;

    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationService(organizationRepository, memberRepository);
        // storageService is field-injected in production (@Autowired(required=false));
        // wire the mock in so the workspace-avatar methods can be exercised.
        ReflectionTestUtils.setField(organizationService, "storageService", storageService);
        // subscriptionRepository is @Autowired(required=false) in production - wire the
        // mock so resolveMaxWorkspaces (workspace cap) resolves in reconcile tests.
        ReflectionTestUtils.setField(organizationService, "subscriptionRepository", subscriptionRepository);
    }

    // ===== Helper methods =====

    private User createTestUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setEmail("user" + id + "@test.com");
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        return user;
    }

    private Organization createTestOrganization(UUID id, String name, String slug, boolean isPersonal, User owner) {
        Organization org = new Organization(name, slug, isPersonal, owner);
        org.setId(id);
        return org;
    }

    // ===== createPersonalOrganization =====

    @Nested
    @DisplayName("createPersonalOrganization")
    class CreatePersonalOrganization {

        @Test
        @DisplayName("should create personal org with correct defaults")
        void shouldCreatePersonalOrgWithCorrectDefaults() {
            User user = createTestUser(1L);

            when(organizationRepository.findByOwnerIdAndIsPersonalTrue(1L)).thenReturn(Optional.empty());
            when(organizationRepository.existsBySlug(anyString())).thenReturn(false);
            when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
                Organization org = invocation.getArgument(0);
                org.setId(UUID.randomUUID());
                return org;
            });
            when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Organization result = organizationService.createPersonalOrganization(user, "John Doe");

            assertThat(result).isNotNull();

            verify(organizationRepository).save(organizationCaptor.capture());
            Organization savedOrg = organizationCaptor.getValue();
            assertThat(savedOrg.getName()).isEqualTo("John Doe's Workspace");
            assertThat(savedOrg.getSlug()).isEqualTo("john-doe");
            assertThat(savedOrg.isPersonal()).isTrue();
            assertThat(savedOrg.getOwner()).isEqualTo(user);

            verify(memberRepository).save(memberCaptor.capture());
            OrganizationMember savedMember = memberCaptor.getValue();
            assertThat(savedMember.getUser()).isEqualTo(user);
            assertThat(savedMember.getRole()).isEqualTo(OrganizationRole.OWNER);
            assertThat(savedMember.isDefault()).isTrue();
        }

        @Test
        @DisplayName("should return existing personal org if already exists (idempotent)")
        void shouldReturnExistingPersonalOrg() {
            User user = createTestUser(1L);
            UUID orgId = UUID.randomUUID();
            Organization existingOrg = createTestOrganization(orgId, "Existing Workspace", "existing", true, user);

            when(organizationRepository.findByOwnerIdAndIsPersonalTrue(1L)).thenReturn(Optional.of(existingOrg));

            Organization result = organizationService.createPersonalOrganization(user, "John Doe");

            assertThat(result.getId()).isEqualTo(orgId);
            assertThat(result.getName()).isEqualTo("Existing Workspace");

            verify(organizationRepository, never()).save(any());
            verify(memberRepository, never()).save(any());
        }

        @Test
        @DisplayName("should handle slug collision by appending counter")
        void shouldHandleSlugCollision() {
            User user = createTestUser(1L);

            when(organizationRepository.findByOwnerIdAndIsPersonalTrue(1L)).thenReturn(Optional.empty());
            when(organizationRepository.existsBySlug("john-doe")).thenReturn(true);
            when(organizationRepository.existsBySlug("john-doe-1")).thenReturn(false);
            when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
                Organization org = invocation.getArgument(0);
                org.setId(UUID.randomUUID());
                return org;
            });
            when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

            organizationService.createPersonalOrganization(user, "John Doe");

            verify(organizationRepository).save(organizationCaptor.capture());
            assertThat(organizationCaptor.getValue().getSlug()).isEqualTo("john-doe-1");
        }

        @Test
        @DisplayName("should handle blank display name by using default slug")
        void shouldHandleBlankDisplayName() {
            User user = createTestUser(1L);

            when(organizationRepository.findByOwnerIdAndIsPersonalTrue(1L)).thenReturn(Optional.empty());
            when(organizationRepository.existsBySlug(anyString())).thenReturn(false);
            when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
                Organization org = invocation.getArgument(0);
                org.setId(UUID.randomUUID());
                return org;
            });
            when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

            organizationService.createPersonalOrganization(user, "   ");

            verify(organizationRepository).save(organizationCaptor.capture());
            assertThat(organizationCaptor.getValue().getSlug()).isEqualTo("workspace");
        }

        @Test
        @DisplayName("should handle accented characters in display name")
        void shouldHandleAccentedCharacters() {
            User user = createTestUser(1L);

            when(organizationRepository.findByOwnerIdAndIsPersonalTrue(1L)).thenReturn(Optional.empty());
            when(organizationRepository.existsBySlug(anyString())).thenReturn(false);
            when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
                Organization org = invocation.getArgument(0);
                org.setId(UUID.randomUUID());
                return org;
            });
            when(memberRepository.save(any(OrganizationMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

            organizationService.createPersonalOrganization(user, "Rene Dupre");

            verify(organizationRepository).save(organizationCaptor.capture());
            assertThat(organizationCaptor.getValue().getSlug()).isEqualTo("rene-dupre");
        }
    }

    // ===== getDefaultOrganization =====

    @Nested
    @DisplayName("getDefaultOrganization")
    class GetDefaultOrganization {

        @Test
        @DisplayName("should return default organization when exists")
        void shouldReturnDefaultOrg() {
            User user = createTestUser(1L);
            UUID orgId = UUID.randomUUID();
            Organization org = createTestOrganization(orgId, "Default Workspace", "default", true, user);
            OrganizationMember member = new OrganizationMember(org, user, OrganizationRole.OWNER, true);

            // Default resolution now filters soft-deleted orgs (prod bug 2026-06-06).
            when(memberRepository.findActiveDefaultByUserId(1L)).thenReturn(Optional.of(member));

            Optional<Organization> result = organizationService.getDefaultOrganization(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(orgId);
        }

        @Test
        @DisplayName("should return empty when no default organization")
        void shouldReturnEmptyWhenNoDefault() {
            when(memberRepository.findActiveDefaultByUserId(1L)).thenReturn(Optional.empty());

            Optional<Organization> result = organizationService.getDefaultOrganization(1L);

            assertThat(result).isEmpty();
        }
    }

    // ===== getDefaultOrganizationId =====

    @Nested
    @DisplayName("getDefaultOrganizationId")
    class GetDefaultOrganizationId {

        @Test
        @DisplayName("should return default organization ID as string")
        void shouldReturnDefaultOrgIdAsString() {
            User user = createTestUser(1L);
            UUID orgId = UUID.randomUUID();
            Organization org = createTestOrganization(orgId, "Default", "default", true, user);
            OrganizationMember member = new OrganizationMember(org, user, OrganizationRole.OWNER, true);

            when(memberRepository.findActiveDefaultByUserId(1L)).thenReturn(Optional.of(member));

            Optional<String> result = organizationService.getDefaultOrganizationId(1L);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(orgId.toString());
        }

        @Test
        @DisplayName("should return empty when no default org exists")
        void shouldReturnEmptyWhenNoDefaultOrg() {
            when(memberRepository.findActiveDefaultByUserId(999L)).thenReturn(Optional.empty());

            Optional<String> result = organizationService.getDefaultOrganizationId(999L);

            assertThat(result).isEmpty();
        }
    }

    // ===== getUserOrganizations =====

    @Nested
    @DisplayName("getUserOrganizations")
    class GetUserOrganizations {

        @Test
        @DisplayName("should return all user organizations")
        void shouldReturnAllUserOrganizations() {
            User user = createTestUser(1L);
            Organization org1 = createTestOrganization(UUID.randomUUID(), "Personal", "personal", true, user);
            Organization org2 = createTestOrganization(UUID.randomUUID(), "Team", "team", false, user);

            OrganizationMember member1 = new OrganizationMember(org1, user, OrganizationRole.OWNER, true);
            OrganizationMember member2 = new OrganizationMember(org2, user, OrganizationRole.MEMBER, false);

            when(memberRepository.findByUserIdWithOrganization(1L)).thenReturn(List.of(member1, member2));

            List<Organization> result = organizationService.getUserOrganizations(1L);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(Organization::getName).containsExactly("Personal", "Team");
        }

        @Test
        @DisplayName("should return empty list when user has no organizations")
        void shouldReturnEmptyListWhenNoOrganizations() {
            when(memberRepository.findByUserIdWithOrganization(999L)).thenReturn(List.of());

            List<Organization> result = organizationService.getUserOrganizations(999L);

            assertThat(result).isEmpty();
        }
    }

    // ===== findById =====

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("should find organization by UUID")
        void shouldFindOrganizationById() {
            UUID orgId = UUID.randomUUID();
            User user = createTestUser(1L);
            Organization org = createTestOrganization(orgId, "Found Org", "found-org", false, user);

            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

            Optional<Organization> result = organizationService.findById(orgId);

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("Found Org");
        }

        @Test
        @DisplayName("should return empty when organization not found")
        void shouldReturnEmptyWhenNotFound() {
            UUID orgId = UUID.randomUUID();
            when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

            Optional<Organization> result = organizationService.findById(orgId);

            assertThat(result).isEmpty();
        }
    }

    // ===== findBySlug =====

    @Nested
    @DisplayName("findBySlug")
    class FindBySlug {

        @Test
        @DisplayName("should find organization by slug")
        void shouldFindBySlug() {
            User user = createTestUser(1L);
            Organization org = createTestOrganization(UUID.randomUUID(), "My Org", "my-org", false, user);

            when(organizationRepository.findBySlug("my-org")).thenReturn(Optional.of(org));

            Optional<Organization> result = organizationService.findBySlug("my-org");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("My Org");
        }

        @Test
        @DisplayName("should return empty when slug not found")
        void shouldReturnEmptyWhenSlugNotFound() {
            when(organizationRepository.findBySlug("nonexistent")).thenReturn(Optional.empty());

            Optional<Organization> result = organizationService.findBySlug("nonexistent");

            assertThat(result).isEmpty();
        }
    }

    // ===== isMember =====

    @Nested
    @DisplayName("isMember")
    class IsMember {

        @Test
        @DisplayName("should return true when user is member")
        void shouldReturnTrueWhenMember() {
            UUID orgId = UUID.randomUUID();
            when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, 1L)).thenReturn(true);

            boolean result = organizationService.isMember(orgId, 1L);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when user is not member")
        void shouldReturnFalseWhenNotMember() {
            UUID orgId = UUID.randomUUID();
            when(memberRepository.existsByOrganization_IdAndUser_Id(orgId, 1L)).thenReturn(false);

            boolean result = organizationService.isMember(orgId, 1L);

            assertThat(result).isFalse();
        }
    }

    // ===== getMembership =====

    @Nested
    @DisplayName("getMembership")
    class GetMembership {

        @Test
        @DisplayName("should return membership when exists")
        void shouldReturnMembershipWhenExists() {
            UUID orgId = UUID.randomUUID();
            User user = createTestUser(1L);
            Organization org = createTestOrganization(orgId, "Org", "org", false, user);
            OrganizationMember member = new OrganizationMember(org, user, OrganizationRole.ADMIN, false);

            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 1L)).thenReturn(Optional.of(member));

            Optional<OrganizationMember> result = organizationService.getMembership(orgId, 1L);

            assertThat(result).isPresent();
            assertThat(result.get().getRole()).isEqualTo(OrganizationRole.ADMIN);
        }

        @Test
        @DisplayName("should return empty when membership not found")
        void shouldReturnEmptyWhenMembershipNotFound() {
            UUID orgId = UUID.randomUUID();
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 999L)).thenReturn(Optional.empty());

            Optional<OrganizationMember> result = organizationService.getMembership(orgId, 999L);

            assertThat(result).isEmpty();
        }
    }

    // ===== default org / membership (soft-delete safety) =====

    @Nested
    @DisplayName("default org resolution excludes soft-deleted workspaces")
    class DefaultMembershipSoftDeleteSafety {

        // Regression - prod bug 2026-06-06. getDefaultMembership feeds
        // UserResolutionResponse.defaultOrganizationId, which the gateway stamps as
        // the active org downstream. It MUST read the deleted-filtered finder
        // (findActiveDefaultByUserId), NOT the raw findByUser_IdAndIsDefaultTrue -
        // otherwise a soft-deleted org that still carried is_default would be served
        // as the user's active workspace and strand them on a deleted workspace.
        @Test
        @DisplayName("getDefaultMembership reads the deleted-filtered finder, never the raw one")
        void getDefaultMembershipUsesDeletedFilteredFinder() {
            User user = createTestUser(1L);
            Organization personal = createTestOrganization(UUID.randomUUID(), "Personal", "personal", true, user);
            OrganizationMember active = new OrganizationMember(personal, user, OrganizationRole.OWNER, true);
            when(memberRepository.findActiveDefaultByUserId(1L)).thenReturn(Optional.of(active));

            Optional<OrganizationMember> result = organizationService.getDefaultMembership(1L);

            assertThat(result).contains(active);
            verify(memberRepository).findActiveDefaultByUserId(1L);
            verify(memberRepository, never()).findByUser_IdAndIsDefaultTrue(any());
        }

        @Test
        @DisplayName("getDefaultOrganization reads the deleted-filtered finder, never the raw one")
        void getDefaultOrganizationUsesDeletedFilteredFinder() {
            User user = createTestUser(1L);
            Organization personal = createTestOrganization(UUID.randomUUID(), "Personal", "personal", true, user);
            OrganizationMember active = new OrganizationMember(personal, user, OrganizationRole.OWNER, true);
            when(memberRepository.findActiveDefaultByUserId(1L)).thenReturn(Optional.of(active));

            Optional<Organization> result = organizationService.getDefaultOrganization(1L);

            assertThat(result).contains(personal);
            verify(memberRepository).findActiveDefaultByUserId(1L);
            verify(memberRepository, never()).findByUser_IdAndIsDefaultTrue(any());
        }

        @Test
        @DisplayName("getDefaultMembership is empty when the only default is soft-deleted (finder returns none)")
        void getDefaultMembershipEmptyWhenDefaultSoftDeleted() {
            // The deleted-filtered finder returns empty when the user's is_default
            // membership points at a soft-deleted org; the service must surface that
            // empty so the gateway falls back to the personal workspace.
            when(memberRepository.findActiveDefaultByUserId(1L)).thenReturn(Optional.empty());

            Optional<OrganizationMember> result = organizationService.getDefaultMembership(1L);

            assertThat(result).isEmpty();
            verify(memberRepository, never()).findByUser_IdAndIsDefaultTrue(any());
        }
    }

    // ===== workspace avatar =====

    @Nested
    @DisplayName("workspace avatar")
    class WorkspaceAvatar {

        @Test
        @DisplayName("uploadAvatar stores the image, drops the previous one, and points avatar_url at the new storage UUID")
        void uploadAvatarStoresAndReplaces() {
            UUID orgId = UUID.randomUUID();
            UUID oldStorageId = UUID.randomUUID();
            UUID newStorageId = UUID.randomUUID();
            Organization org = createTestOrganization(orgId, "Acme's Workspace", "acme", false, createTestUser(1L));
            org.setAvatarUrl(oldStorageId.toString());

            StorageEntity old = mock(StorageEntity.class);
            when(old.getId()).thenReturn(oldStorageId);
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(storageService.listByTenantAndSourceType(orgId.toString(), "ORG_AVATAR")).thenReturn(List.of(old));
            when(storageService.saveBinary(eq(orgId.toString()), any(), anyString(), eq("image/png"), isNull(), eq("ORG_AVATAR")))
                    .thenReturn(newStorageId);

            String result = organizationService.uploadAvatar(orgId, new byte[]{1, 2, 3}, "image/png", "logo.png");

            assertThat(result).isEqualTo(newStorageId.toString());
            verify(storageService).deleteById(oldStorageId, orgId.toString());
            verify(organizationRepository).save(organizationCaptor.capture());
            assertThat(organizationCaptor.getValue().getAvatarUrl()).isEqualTo(newStorageId.toString());
        }

        @Test
        @DisplayName("uploadAvatar rejects an oversized image before touching storage")
        void uploadAvatarRejectsOversized() {
            UUID orgId = UUID.randomUUID();
            byte[] tooBig = new byte[5 * 1024 * 1024 + 1];

            assertThatThrownBy(() -> organizationService.uploadAvatar(orgId, tooBig, "image/png", "big.png"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("5MB");
            verify(storageService, never()).saveBinary(anyString(), any(), anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("uploadAvatar rejects an unsupported mime type")
        void uploadAvatarRejectsBadType() {
            UUID orgId = UUID.randomUUID();

            assertThatThrownBy(() -> organizationService.uploadAvatar(orgId, new byte[]{1}, "application/pdf", "x.pdf"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported");
        }

        @Test
        @DisplayName("deleteAvatar clears avatar_url and removes the stored image")
        void deleteAvatarClears() {
            UUID orgId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            Organization org = createTestOrganization(orgId, "Acme", "acme", false, createTestUser(1L));
            org.setAvatarUrl(storageId.toString());

            StorageEntity stored = mock(StorageEntity.class);
            when(stored.getId()).thenReturn(storageId);
            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(storageService.listByTenantAndSourceType(orgId.toString(), "ORG_AVATAR")).thenReturn(List.of(stored));

            organizationService.deleteAvatar(orgId);

            verify(storageService).deleteById(storageId, orgId.toString());
            verify(organizationRepository).save(organizationCaptor.capture());
            assertThat(organizationCaptor.getValue().getAvatarUrl()).isNull();
        }

        @Test
        @DisplayName("getAvatarEntity is NOT read-only - StorageService.getEntityById bumps accessed_at (a read-only tx 500'd the avatar GET)")
        void getAvatarEntityIsNotReadOnly() throws Exception {
            org.springframework.transaction.annotation.Transactional tx =
                    OrganizationService.class.getMethod("getAvatarEntity", UUID.class)
                            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertThat(tx).isNotNull();
            assertThat(tx.readOnly()).isFalse();
        }

        @Test
        @DisplayName("getAvatarEntity resolves the stored image by the avatar_url UUID")
        void getAvatarEntityResolvesByUuid() {
            UUID orgId = UUID.randomUUID();
            UUID storageId = UUID.randomUUID();
            Organization org = createTestOrganization(orgId, "Acme", "acme", false, createTestUser(1L));
            org.setAvatarUrl(storageId.toString());
            StorageEntity stored = mock(StorageEntity.class);

            when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(storageService.getEntityById(storageId, orgId.toString())).thenReturn(Optional.of(stored));

            Optional<StorageEntity> result = organizationService.getAvatarEntity(orgId);

            assertThat(result).containsSame(stored);
        }
    }

    @Nested
    @DisplayName("reconcileWorkspacePauseState (downgrade/upgrade workspace cap)")
    class ReconcileWorkspacePauseStateTests {

        private static final Long OWNER = 7L;
        private User owner;

        @org.junit.jupiter.api.BeforeEach
        void each() { owner = createTestUser(OWNER); }

        private void mockCap(Integer cap) {
            Plan plan = new Plan();
            plan.setCode("X");
            plan.setMaxWorkspaces(cap);
            Subscription sub = new Subscription();
            sub.setPlan(plan);
            when(subscriptionRepository.findActiveByUserId(OWNER)).thenReturn(Optional.of(sub));
        }

        private Organization ws(String name, boolean personal, boolean paused) {
            Organization org = createTestOrganization(UUID.randomUUID(), name, name + "-slug", personal, owner);
            if (paused) org.setPausedAt(LocalDateTime.now());
            return org;
        }

        private void ownedOldestFirst(Organization... orgs) {
            when(organizationRepository.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtAsc(OWNER))
                    .thenReturn(List.of(orgs));
        }

        @Test
        @DisplayName("Downgrade to Starter (cap 1): keep personal, pause every non-personal")
        void downgradeToStarterPausesAllNonPersonal() {
            mockCap(1);
            Organization personal = ws("personal", true, false);
            Organization a = ws("a", false, false);
            Organization b = ws("b", false, false);
            ownedOldestFirst(personal, a, b);

            organizationService.reconcileWorkspacePauseState(OWNER);

            assertThat(personal.isPaused()).isFalse();
            assertThat(a.isPaused()).isTrue();
            assertThat(b.isPaused()).isTrue();
            verify(organizationRepository, times(2)).save(any(Organization.class));
        }

        @Test
        @DisplayName("Downgrade to Pro (cap 3): keep personal + the 2 OLDEST non-personal, pause the most-recent excess")
        void downgradeToProKeepsOldestNonPersonal() {
            mockCap(3);
            Organization personal = ws("personal", true, false);
            Organization oldest = ws("oldest", false, false);
            Organization mid = ws("mid", false, false);
            Organization newer = ws("newer", false, false);
            Organization newest = ws("newest", false, false);
            ownedOldestFirst(personal, oldest, mid, newer, newest);

            organizationService.reconcileWorkspacePauseState(OWNER);

            assertThat(personal.isPaused()).as("personal").isFalse();
            assertThat(oldest.isPaused()).as("oldest kept").isFalse();
            assertThat(mid.isPaused()).as("2nd-oldest kept").isFalse();
            assertThat(newer.isPaused()).as("excess paused").isTrue();
            assertThat(newest.isPaused()).as("excess paused").isTrue();
        }

        @Test
        @DisplayName("Upgrade (cap 10): un-pauses formerly-paused workspaces")
        void upgradeUnpauses() {
            mockCap(10);
            Organization personal = ws("personal", true, false);
            Organization a = ws("a", false, true);
            Organization b = ws("b", false, true);
            ownedOldestFirst(personal, a, b);

            organizationService.reconcileWorkspacePauseState(OWNER);

            assertThat(a.isPaused()).isFalse();
            assertThat(b.isPaused()).isFalse();
            verify(organizationRepository, times(2)).save(any(Organization.class));
        }

        @Test
        @DisplayName("Unlimited cap (null): un-pauses everything")
        void unlimitedUnpauses() {
            mockCap(null);
            Organization personal = ws("personal", true, false);
            Organization a = ws("a", false, true);
            ownedOldestFirst(personal, a);

            organizationService.reconcileWorkspacePauseState(OWNER);

            assertThat(a.isPaused()).isFalse();
        }

        @Test
        @DisplayName("Idempotent: already-correct state writes nothing")
        void idempotentNoWrite() {
            mockCap(1);
            Organization personal = ws("personal", true, false);
            Organization a = ws("a", false, true); // already paused - correct for cap 1
            ownedOldestFirst(personal, a);

            organizationService.reconcileWorkspacePauseState(OWNER);

            verify(organizationRepository, never()).save(any(Organization.class));
        }

        @Test
        @DisplayName("Mixed transition: in ONE pass un-pauses a now-kept oldest, pauses a now-excess, leaves correct rows alone")
        void mixedTransition() {
            mockCap(3); // keep personal + 2 oldest non-personal
            Organization personal = ws("personal", true, false);
            Organization a = ws("a", false, true);   // 1st non-personal, currently paused → un-pause
            Organization b = ws("b", false, false);  // 2nd, active → stays active (no write)
            Organization c = ws("c", false, false);  // 3rd, active but over budget → pause
            Organization d = ws("d", false, true);   // 4th, already paused over budget → stays paused (no write)
            ownedOldestFirst(personal, a, b, c, d);

            organizationService.reconcileWorkspacePauseState(OWNER);

            assertThat(a.isPaused()).as("oldest un-paused").isFalse();
            assertThat(b.isPaused()).as("2nd kept").isFalse();
            assertThat(c.isPaused()).as("3rd newly paused").isTrue();
            assertThat(d.isPaused()).as("4th stays paused").isTrue();
            assertThat(personal.isPaused()).isFalse();
            // Exactly two state changes were written (a un-pause, c pause).
            verify(organizationRepository, times(2)).save(any(Organization.class));
        }

        @Test
        @DisplayName("Null owner is a no-op")
        void nullOwnerNoOp() {
            organizationService.reconcileWorkspacePauseState(null);
            verify(organizationRepository, never()).findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtAsc(any());
        }
    }
}
