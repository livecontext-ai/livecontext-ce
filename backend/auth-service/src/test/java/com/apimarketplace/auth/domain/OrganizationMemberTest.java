package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrganizationMember Tests")
class OrganizationMemberTest {

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should initialize joinedAt to now")
        void shouldInitializeJoinedAt() {
            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            OrganizationMember member = new OrganizationMember();
            LocalDateTime after = LocalDateTime.now().plusSeconds(1);

            assertThat(member.getJoinedAt()).isAfter(before).isBefore(after);
        }

        @Test
        @DisplayName("should have MEMBER as default role")
        void shouldHaveMemberAsDefaultRole() {
            OrganizationMember member = new OrganizationMember();

            assertThat(member.getRole()).isEqualTo(OrganizationRole.MEMBER);
        }

        @Test
        @DisplayName("should have isDefault set to false")
        void shouldHaveIsDefaultFalse() {
            OrganizationMember member = new OrganizationMember();

            assertThat(member.isDefault()).isFalse();
        }
    }

    @Nested
    @DisplayName("Parameterized Constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should set all fields correctly")
        void shouldSetAllFields() {
            Organization org = new Organization("TestOrg", "test-org", false, null);
            User user = new User("testuser", "test@example.com", AuthProvider.KEYCLOAK, "f47ac10b-58cc-4372-a567-0e02b2c3d479");

            OrganizationMember member = new OrganizationMember(org, user, OrganizationRole.OWNER, true);

            assertThat(member.getOrganization()).isEqualTo(org);
            assertThat(member.getUser()).isEqualTo(user);
            assertThat(member.getRole()).isEqualTo(OrganizationRole.OWNER);
            assertThat(member.isDefault()).isTrue();
        }

        @Test
        @DisplayName("should initialize joinedAt from default constructor")
        void shouldInitializeJoinedAt() {
            Organization org = new Organization("TestOrg", "test-org", false, null);
            User user = new User();

            OrganizationMember member = new OrganizationMember(org, user, OrganizationRole.ADMIN, false);

            assertThat(member.getJoinedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set id")
        void shouldGetAndSetId() {
            OrganizationMember member = new OrganizationMember();
            member.setId(99L);

            assertThat(member.getId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("should get and set organization")
        void shouldGetAndSetOrganization() {
            OrganizationMember member = new OrganizationMember();
            Organization org = new Organization("TestOrg", "test-org", false, null);
            member.setOrganization(org);

            assertThat(member.getOrganization()).isEqualTo(org);
        }

        @Test
        @DisplayName("should get and set user")
        void shouldGetAndSetUser() {
            OrganizationMember member = new OrganizationMember();
            User user = new User();
            user.setId(5L);
            member.setUser(user);

            assertThat(member.getUser()).isEqualTo(user);
        }

        @Test
        @DisplayName("should get and set role")
        void shouldGetAndSetRole() {
            OrganizationMember member = new OrganizationMember();
            member.setRole(OrganizationRole.ADMIN);

            assertThat(member.getRole()).isEqualTo(OrganizationRole.ADMIN);
        }

        @Test
        @DisplayName("should get and set isDefault")
        void shouldGetAndSetIsDefault() {
            OrganizationMember member = new OrganizationMember();
            member.setDefault(true);

            assertThat(member.isDefault()).isTrue();
        }

        @Test
        @DisplayName("should get and set joinedAt")
        void shouldGetAndSetJoinedAt() {
            OrganizationMember member = new OrganizationMember();
            LocalDateTime date = LocalDateTime.of(2025, 6, 15, 10, 0);
            member.setJoinedAt(date);

            assertThat(member.getJoinedAt()).isEqualTo(date);
        }

        @Test
        @DisplayName("should get and set invitedBy")
        void shouldGetAndSetInvitedBy() {
            OrganizationMember member = new OrganizationMember();
            User inviter = new User();
            inviter.setId(10L);
            member.setInvitedBy(inviter);

            assertThat(member.getInvitedBy()).isEqualTo(inviter);
        }
    }

    @Nested
    @DisplayName("getOrganizationId()")
    class GetOrganizationIdTests {

        @Test
        @DisplayName("should return organization ID when organization is set")
        void shouldReturnOrganizationId() {
            Organization org = new Organization("TestOrg", "test-org", false, null);
            OrganizationMember member = new OrganizationMember();
            member.setOrganization(org);

            UUID orgId = member.getOrganizationId();

            assertThat(orgId).isEqualTo(org.getId());
        }

        @Test
        @DisplayName("should return null when organization is null")
        void shouldReturnNullWhenOrganizationIsNull() {
            OrganizationMember member = new OrganizationMember();

            assertThat(member.getOrganizationId()).isNull();
        }
    }
}
