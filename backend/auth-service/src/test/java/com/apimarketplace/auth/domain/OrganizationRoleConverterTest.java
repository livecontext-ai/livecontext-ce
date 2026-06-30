package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OrganizationRoleConverter Tests")
class OrganizationRoleConverterTest {

    private OrganizationRoleConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OrganizationRoleConverter();
    }

    @Nested
    @DisplayName("convertToDatabaseColumn()")
    class ConvertToDatabaseColumnTests {

        @ParameterizedTest
        @EnumSource(OrganizationRole.class)
        @DisplayName("should convert all roles to lowercase")
        void shouldConvertAllRolesToLowercase(OrganizationRole role) {
            String result = converter.convertToDatabaseColumn(role);

            assertThat(result).isEqualTo(role.name().toLowerCase());
        }

        @Test
        @DisplayName("should return null for null role")
        void shouldReturnNullForNullRole() {
            String result = converter.convertToDatabaseColumn(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should convert OWNER to 'owner'")
        void shouldConvertOwnerToLowercase() {
            String result = converter.convertToDatabaseColumn(OrganizationRole.OWNER);

            assertThat(result).isEqualTo("owner");
        }

        @Test
        @DisplayName("should convert ADMIN to 'admin'")
        void shouldConvertAdminToLowercase() {
            String result = converter.convertToDatabaseColumn(OrganizationRole.ADMIN);

            assertThat(result).isEqualTo("admin");
        }

        @Test
        @DisplayName("should convert MEMBER to 'member'")
        void shouldConvertMemberToLowercase() {
            String result = converter.convertToDatabaseColumn(OrganizationRole.MEMBER);

            assertThat(result).isEqualTo("member");
        }

        @Test
        @DisplayName("should convert VIEWER to 'viewer'")
        void shouldConvertViewerToLowercase() {
            String result = converter.convertToDatabaseColumn(OrganizationRole.VIEWER);

            assertThat(result).isEqualTo("viewer");
        }
    }

    @Nested
    @DisplayName("convertToEntityAttribute()")
    class ConvertToEntityAttributeTests {

        @Test
        @DisplayName("should convert 'owner' to OWNER")
        void shouldConvertOwnerString() {
            OrganizationRole result = converter.convertToEntityAttribute("owner");

            assertThat(result).isEqualTo(OrganizationRole.OWNER);
        }

        @Test
        @DisplayName("should convert 'admin' to ADMIN")
        void shouldConvertAdminString() {
            OrganizationRole result = converter.convertToEntityAttribute("admin");

            assertThat(result).isEqualTo(OrganizationRole.ADMIN);
        }

        @Test
        @DisplayName("should convert 'member' to MEMBER")
        void shouldConvertMemberString() {
            OrganizationRole result = converter.convertToEntityAttribute("member");

            assertThat(result).isEqualTo(OrganizationRole.MEMBER);
        }

        @Test
        @DisplayName("should convert 'viewer' to VIEWER")
        void shouldConvertViewerString() {
            OrganizationRole result = converter.convertToEntityAttribute("viewer");

            assertThat(result).isEqualTo(OrganizationRole.VIEWER);
        }

        @Test
        @DisplayName("should handle uppercase input")
        void shouldHandleUppercaseInput() {
            OrganizationRole result = converter.convertToEntityAttribute("OWNER");

            assertThat(result).isEqualTo(OrganizationRole.OWNER);
        }

        @Test
        @DisplayName("should handle mixed case input")
        void shouldHandleMixedCaseInput() {
            OrganizationRole result = converter.convertToEntityAttribute("Admin");

            assertThat(result).isEqualTo(OrganizationRole.ADMIN);
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            OrganizationRole result = converter.convertToEntityAttribute(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for empty string")
        void shouldReturnNullForEmptyString() {
            OrganizationRole result = converter.convertToEntityAttribute("");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should throw for invalid role string")
        void shouldThrowForInvalidRoleString() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("INVALID"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Round-trip conversion")
    class RoundTripTests {

        @ParameterizedTest
        @EnumSource(OrganizationRole.class)
        @DisplayName("should survive round-trip conversion for all roles")
        void shouldSurviveRoundTripConversion(OrganizationRole role) {
            String dbValue = converter.convertToDatabaseColumn(role);
            OrganizationRole restored = converter.convertToEntityAttribute(dbValue);

            assertThat(restored).isEqualTo(role);
        }
    }
}
