package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-1 regression: Jackson's default PropertyNamingStrategy strips the {@code is} prefix
 * from boolean getters, so without @JsonProperty the wire format becomes {@code default} /
 * {@code personal} / {@code owner} - silently breaking the frontend contract which declares
 * {@code isDefault} / {@code isPersonal} / {@code isOwner}. These tests pin the JSON field
 * names so the mismatch cannot be reintroduced without breaking the tests.
 */
@DisplayName("OrganizationDto JSON serialization - BUG-1 regression")
class OrganizationDtoJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("OrganizationDto serializes isDefault/isPersonal (not default/personal)")
    void preservesIsPrefixOnOrganizationBooleans() throws Exception {
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("o@test.com");
        owner.setAuthProvider(AuthProvider.KEYCLOAK);

        Organization org = new Organization("T", "t", true, owner);
        org.setId(UUID.randomUUID());
        OrganizationMember membership = new OrganizationMember(org, owner, OrganizationRole.OWNER, true);

        OrganizationDto dto = OrganizationDto.fromEntity(org, membership, 1);
        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"isDefault\":true");
        assertThat(json).contains("\"isPersonal\":true");
        assertThat(json).doesNotContain("\"default\":");
        assertThat(json).doesNotContain("\"personal\":");
    }

    @Test
    @DisplayName("MemberDto serializes isOwner (not owner)")
    void preservesIsPrefixOnMemberBoolean() throws Exception {
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("o@test.com");
        owner.setAuthProvider(AuthProvider.KEYCLOAK);

        Organization org = new Organization("T", "t", false, owner);
        OrganizationMember membership = new OrganizationMember(org, owner, OrganizationRole.OWNER, true);

        OrganizationDto.MemberDto dto = new OrganizationDto.MemberDto(membership, "Owner");
        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"isOwner\":true");
        assertThat(json).doesNotContain("\"owner\":");
    }
}
