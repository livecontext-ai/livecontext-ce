package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for OrganizationRepository and OrganizationMemberRepository.
 * Tests JPA repository methods with a real H2 database context.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
@AutoConfigureTestEntityManager
@DisplayName("Organization Repository Integration Tests")
class OrganizationRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository memberRepository;

    private User ownerUser;
    private User memberUser;
    private Organization personalOrg;

    @BeforeEach
    void setUp() {
        ownerUser = new User("owner", "owner@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        ownerUser.setEnabled(true);
        ownerUser.setUserVersion(1L);
        ownerUser = entityManager.persistAndFlush(ownerUser);

        memberUser = new User("member", "member@example.com", AuthProvider.KEYCLOAK, "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        memberUser.setEnabled(true);
        memberUser.setUserVersion(1L);
        memberUser = entityManager.persistAndFlush(memberUser);

        personalOrg = new Organization("Owner's Workspace", "owner-workspace", true, ownerUser);
        personalOrg = entityManager.persistAndFlush(personalOrg);
    }

    @Nested
    @DisplayName("Organization CRUD")
    class OrganizationCrud {

        @Test
        @DisplayName("Should save and retrieve organization")
        void shouldSaveAndRetrieveOrganization() {
            Optional<Organization> found = organizationRepository.findById(personalOrg.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("Owner's Workspace");
            assertThat(found.get().getSlug()).isEqualTo("owner-workspace");
            assertThat(found.get().isPersonal()).isTrue();
        }

        @Test
        @DisplayName("Should find organization by slug")
        void shouldFindBySlug() {
            Optional<Organization> found = organizationRepository.findBySlug("owner-workspace");

            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(personalOrg.getId());
        }

        @Test
        @DisplayName("Should return empty for non-existent slug")
        void shouldReturnEmptyForNonExistentSlug() {
            Optional<Organization> found = organizationRepository.findBySlug("nonexistent");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("Should check slug existence")
        void shouldCheckSlugExistence() {
            assertThat(organizationRepository.existsBySlug("owner-workspace")).isTrue();
            assertThat(organizationRepository.existsBySlug("nonexistent")).isFalse();
        }
    }

    @Nested
    @DisplayName("Personal Organization Queries")
    class PersonalOrganizationQueries {

        @Test
        @DisplayName("Should find personal organization by owner")
        void shouldFindPersonalOrgByOwner() {
            Optional<Organization> found = organizationRepository.findByOwnerIdAndIsPersonalTrue(ownerUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getSlug()).isEqualTo("owner-workspace");
        }

        @Test
        @DisplayName("Should not find personal org for user without one")
        void shouldNotFindPersonalOrgForUserWithoutOne() {
            Optional<Organization> found = organizationRepository.findByOwnerIdAndIsPersonalTrue(memberUser.getId());

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("Should check personal organization existence")
        void shouldCheckPersonalOrgExistence() {
            assertThat(organizationRepository.existsByOwnerIdAndIsPersonalTrue(ownerUser.getId())).isTrue();
            assertThat(organizationRepository.existsByOwnerIdAndIsPersonalTrue(memberUser.getId())).isFalse();
        }

        @Test
        @DisplayName("Should find all organizations owned by user")
        void shouldFindAllOrganizationsByOwner() {
            Organization teamOrg = new Organization("Team Org", "team-org", false, ownerUser);
            entityManager.persistAndFlush(teamOrg);

            List<Organization> orgs = organizationRepository.findByOwnerId(ownerUser.getId());

            assertThat(orgs).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Organization Member Operations")
    class OrganizationMemberOperations {

        @BeforeEach
        void setUpMembers() {
            OrganizationMember ownerMember = new OrganizationMember(personalOrg, ownerUser, OrganizationRole.OWNER, true);
            entityManager.persistAndFlush(ownerMember);
        }

        @Test
        @DisplayName("Should find memberships for a user")
        void shouldFindMembershipsByUserId() {
            List<OrganizationMember> memberships = memberRepository.findByUser_Id(ownerUser.getId());

            assertThat(memberships).hasSize(1);
            assertThat(memberships.get(0).getRole()).isEqualTo(OrganizationRole.OWNER);
        }

        @Test
        @DisplayName("Should find specific membership")
        void shouldFindSpecificMembership() {
            Optional<OrganizationMember> found = memberRepository.findByOrganization_IdAndUser_Id(
                    personalOrg.getId(), ownerUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getRole()).isEqualTo(OrganizationRole.OWNER);
            assertThat(found.get().isDefault()).isTrue();
        }

        @Test
        @DisplayName("Regression: active membership queries fetch organization before controller DTO mapping")
        void activeMembershipQueriesFetchOrganizationBeforeControllerDtoMapping() {
            entityManager.clear();

            Optional<OrganizationMember> found = memberRepository.findActiveByOrganizationIdAndUserId(
                    personalOrg.getId(), ownerUser.getId());

            assertThat(found).isPresent();
            assertThat(Hibernate.isInitialized(found.get().getOrganization()))
                    .as("OrganizationController maps OrganizationDto outside the repository call")
                    .isTrue();
            assertThat(found.get().getOrganization().getName()).isEqualTo("Owner's Workspace");
        }

        @Test
        @DisplayName("Regression: default membership query fetches organization for /organizations/current")
        void defaultMembershipQueryFetchesOrganizationForCurrentOrganization() {
            entityManager.clear();

            Optional<OrganizationMember> found = memberRepository.findActiveDefaultByUserId(ownerUser.getId());

            assertThat(found).isPresent();
            assertThat(Hibernate.isInitialized(found.get().getOrganization()))
                    .as("/organizations/current builds an OrganizationDto after the query returns")
                    .isTrue();
            assertThat(found.get().getOrganization().getId()).isEqualTo(personalOrg.getId());
        }

        @Test
        @DisplayName("Should find default organization membership")
        void shouldFindDefaultMembership() {
            Optional<OrganizationMember> found = memberRepository.findByUser_IdAndIsDefaultTrue(ownerUser.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getOrganization().getId()).isEqualTo(personalOrg.getId());
        }

        @Test
        @DisplayName("Regression: a soft-deleted default org is filtered out of findActiveDefaultByUserId (raw finder still returns it)")
        void softDeletedDefaultIsExcludedFromActiveFinder() {
            // Prod bug 2026-06-06: if the user's is_default membership points at a
            // soft-deleted (pending-purge) org, the gateway must NOT stamp it as the
            // active org. OrganizationService.getDefaultMembership now reads
            // findActiveDefaultByUserId, which filters deletedAt IS NULL - proving the
            // raw finder would have leaked the deleted org makes the fix meaningful.
            personalOrg.setDeletedAt(java.time.LocalDateTime.now());
            entityManager.persistAndFlush(personalOrg);
            entityManager.clear();

            assertThat(memberRepository.findActiveDefaultByUserId(ownerUser.getId()))
                    .as("deleted-filtered finder must not return a soft-deleted default org")
                    .isEmpty();
            assertThat(memberRepository.findByUser_IdAndIsDefaultTrue(ownerUser.getId()))
                    .as("raw finder still returns it - this is exactly what the service must avoid")
                    .isPresent();
        }

        @Test
        @DisplayName("Should check membership existence")
        void shouldCheckMembershipExistence() {
            assertThat(memberRepository.existsByOrganization_IdAndUser_Id(
                    personalOrg.getId(), ownerUser.getId())).isTrue();
            assertThat(memberRepository.existsByOrganization_IdAndUser_Id(
                    personalOrg.getId(), memberUser.getId())).isFalse();
        }

        @Test
        @DisplayName("Should count members in organization")
        void shouldCountMembersInOrganization() {
            // Add another member
            OrganizationMember member = new OrganizationMember(personalOrg, memberUser, OrganizationRole.MEMBER, false);
            entityManager.persistAndFlush(member);

            long count = memberRepository.countByOrganization_Id(personalOrg.getId());

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("Should find members of an organization")
        void shouldFindOrganizationMembers() {
            OrganizationMember member = new OrganizationMember(personalOrg, memberUser, OrganizationRole.MEMBER, false);
            entityManager.persistAndFlush(member);

            List<OrganizationMember> members = memberRepository.findByOrganization_Id(personalOrg.getId());

            assertThat(members).hasSize(2);
        }

        @Test
        @DisplayName("Should find memberships with organization loaded")
        void shouldFindMembershipsWithOrganization() {
            List<OrganizationMember> memberships = memberRepository.findByUserIdWithOrganization(ownerUser.getId());

            assertThat(memberships).hasSize(1);
            assertThat(memberships.get(0).getOrganization()).isNotNull();
            assertThat(memberships.get(0).getOrganization().getName()).isEqualTo("Owner's Workspace");
        }

        @Test
        @DisplayName("E2E regression - soft-deleted orgs must be excluded from /organizations/me result set")
        void shouldExcludeSoftDeletedOrgsFromMembershipListing() {
            // Caught by scripts/test-org-e2e.sh: after PR-cascade soft-delete
            // (sets deletedAt on the org row) the ex-owner kept seeing the org
            // in /me - findByUserIdWithOrganization had no deletedAt predicate.
            // This pin reproduces the leak on the pre-fix query and passes on
            // the post-fix one (the @Query gained `AND o.deletedAt IS NULL`).
            personalOrg.setDeletedAt(java.time.LocalDateTime.now());
            entityManager.persistAndFlush(personalOrg);

            List<OrganizationMember> memberships =
                    memberRepository.findByUserIdWithOrganization(ownerUser.getId());

            assertThat(memberships)
                    .as("soft-deleted org must NOT appear in the ex-owner's membership listing")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Over-cap reconcile owner lookup")
    class ReconcileOwnerLookup {

        @Test
        @DisplayName("findDistinctOwnerIdsWithActiveNonPersonalWorkspaces returns only owners of an ACTIVE non-personal workspace")
        void returnsOwnersOfActiveNonPersonalWorkspaces() {
            // ownerUser gets one active non-personal workspace (qualifies) + one soft-deleted (excluded).
            Organization active = new Organization("Team A", "team-a", false, ownerUser);
            entityManager.persistAndFlush(active);
            Organization deleted = new Organization("Team Gone", "team-gone", false, ownerUser);
            deleted.setDeletedAt(java.time.LocalDateTime.now());
            entityManager.persistAndFlush(deleted);
            // memberUser only has a personal workspace → must never appear (nothing to pause).
            Organization memberPersonal = new Organization("Member WS", "member-ws", true, memberUser);
            entityManager.persistAndFlush(memberPersonal);
            entityManager.clear();

            List<Long> owners = organizationRepository.findDistinctOwnerIdsWithActiveNonPersonalWorkspaces();

            assertThat(owners).containsExactly(ownerUser.getId());
        }

        @Test
        @DisplayName("returns no owners when only personal workspaces exist (nothing can be over-cap)")
        void emptyWhenOnlyPersonalWorkspaces() {
            // Base setup persists only ownerUser's personal workspace.
            List<Long> owners = organizationRepository.findDistinctOwnerIdsWithActiveNonPersonalWorkspaces();

            assertThat(owners).isEmpty();
        }
    }
}
