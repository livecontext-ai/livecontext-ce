package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Organization Domain Model Tests")
class OrganizationTest {

    @Nested
    @DisplayName("Constructors")
    class ConstructorTests {

        @Test
        @DisplayName("should set default timestamps on creation")
        void shouldSetDefaultTimestampsOnCreation() {
            Organization org = new Organization();

            assertThat(org.getCreatedAt()).isNotNull();
            assertThat(org.getUpdatedAt()).isNotNull();
            assertThat(org.isPersonal()).isFalse();
        }

        @Test
        @DisplayName("should create with all parameters")
        void shouldCreateWithAllParameters() {
            User owner = new User();
            owner.setId(1L);

            Organization org = new Organization("My Org", "my-org", true, owner);

            assertThat(org.getName()).isEqualTo("My Org");
            assertThat(org.getSlug()).isEqualTo("my-org");
            assertThat(org.isPersonal()).isTrue();
            assertThat(org.getOwner()).isEqualTo(owner);
            assertThat(org.getCreatedAt()).isNotNull();
            assertThat(org.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should get and set all fields")
        void shouldGetAndSetAllFields() {
            UUID id = UUID.randomUUID();
            User owner = new User();
            owner.setId(1L);
            LocalDateTime now = LocalDateTime.now();

            Organization org = new Organization();
            org.setId(id);
            org.setName("Test Org");
            org.setSlug("test-org");
            org.setPersonal(false);
            org.setOwner(owner);
            org.setAvatarUrl("https://example.com/avatar.png");
            org.setCreatedAt(now);
            org.setUpdatedAt(now);

            assertThat(org.getId()).isEqualTo(id);
            assertThat(org.getName()).isEqualTo("Test Org");
            assertThat(org.getSlug()).isEqualTo("test-org");
            assertThat(org.isPersonal()).isFalse();
            assertThat(org.getOwner()).isEqualTo(owner);
            assertThat(org.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
            assertThat(org.getCreatedAt()).isEqualTo(now);
            assertThat(org.getUpdatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("should initialize members as empty set")
        void shouldInitializeMembersAsEmptySet() {
            Organization org = new Organization();

            assertThat(org.getMembers()).isNotNull();
            assertThat(org.getMembers()).isEmpty();
        }
    }
}
