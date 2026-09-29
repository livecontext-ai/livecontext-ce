package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The current role of one member, read by the gateway WebSocket on every run-driving action so
 * a demotion applies to a socket that is already open (it used to keep the handshake role).
 */
@DisplayName("InternalAuthController.getMemberRole")
class InternalAuthControllerMemberRoleTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private OrganizationMemberRepository memberRepository;
    private InternalAuthController controller;

    @BeforeEach
    void setUp() throws Exception {
        memberRepository = mock(OrganizationMemberRepository.class);
        var ctor = java.util.Arrays.stream(InternalAuthController.class.getConstructors())
                .max(java.util.Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount))
                .orElseThrow();
        Object[] args = java.util.Arrays.stream(ctor.getParameterTypes())
                .map(t -> t == OrganizationMemberRepository.class ? memberRepository : mock(t))
                .toArray();
        controller = (InternalAuthController) ctor.newInstance(args);
    }

    @Test
    @DisplayName("an active member: their current role name")
    void activeMember() {
        OrganizationMember member = mock(OrganizationMember.class);
        when(member.getRole()).thenReturn(OrganizationRole.VIEWER);
        when(memberRepository.findActiveByOrganizationIdAndUserId(UUID.fromString(ORG), 42L))
                .thenReturn(Optional.of(member));

        ResponseEntity<Map<String, Object>> response = controller.getMemberRole(ORG, "42");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("role", "VIEWER");
    }

    @Test
    @DisplayName("not a member (removed, or org deleted): role null")
    void notMember() {
        when(memberRepository.findActiveByOrganizationIdAndUserId(UUID.fromString(ORG), 42L))
                .thenReturn(Optional.empty());

        assertThat(controller.getMemberRole(ORG, "42").getBody()).containsEntry("role", null);
    }

    @Test
    @DisplayName("malformed org or user id: role null, no query")
    void malformedIds() {
        assertThat(controller.getMemberRole("not-a-uuid", "42").getBody()).containsEntry("role", null);
        assertThat(controller.getMemberRole(ORG, "abc").getBody()).containsEntry("role", null);
        verifyNoInteractions(memberRepository);
    }
}
