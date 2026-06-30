package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.Organization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrganizationDto avatarUrl mapping")
class OrganizationDtoTest {

    private static Organization orgWithAvatar(UUID id, String avatarUrl) {
        Organization org = new Organization("Acme's Workspace", "acme", false, null);
        org.setId(id);
        org.setAvatarUrl(avatarUrl);
        return org;
    }

    @Test
    @DisplayName("storage-UUID avatar_url maps to the public avatar endpoint with a ?v cache-buster")
    void uuidMapsToServableUrl() {
        UUID orgId = UUID.randomUUID();
        UUID storageId = UUID.randomUUID();

        OrganizationDto dto = OrganizationDto.fromEntity(orgWithAvatar(orgId, storageId.toString()), null, 1);

        assertThat(dto.getAvatarUrl())
                .isEqualTo("/api/organizations/" + orgId + "/avatar?v=" + storageId);
    }

    @Test
    @DisplayName("null avatar_url maps to null (UI renders the initials fallback)")
    void nullStaysNull() {
        OrganizationDto dto = OrganizationDto.fromEntity(orgWithAvatar(UUID.randomUUID(), null), null, 1);
        assertThat(dto.getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("a non-UUID avatar_url (legacy external URL) is passed through untouched")
    void nonUuidPassesThrough() {
        OrganizationDto dto = OrganizationDto.fromEntity(
                orgWithAvatar(UUID.randomUUID(), "https://cdn.example.com/logo.png"), null, 1);
        assertThat(dto.getAvatarUrl()).isEqualTo("https://cdn.example.com/logo.png");
    }
}
