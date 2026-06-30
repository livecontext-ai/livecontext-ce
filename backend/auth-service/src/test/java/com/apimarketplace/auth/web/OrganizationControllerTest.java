package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationInvitation;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.InvitationDto;
import com.apimarketplace.auth.dto.OrganizationDto;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.GatewayCacheClient;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.OrganizationMemberService;
import com.apimarketplace.auth.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationController - workspace avatar authorization")
class OrganizationControllerTest {

    @Mock private OrganizationService organizationService;
    @Mock private OrganizationMemberService memberService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private UserRepository userRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private com.apimarketplace.auth.repository.OrganizationAuditEventRepository auditEventRepository;
    @Mock private GatewayCacheClient gatewayCacheClient;

    private OrganizationController controller;

    private final UUID orgId = UUID.randomUUID();
    private final Long userId = 7L;

    @BeforeEach
    void setUp() {
        controller = new OrganizationController(
                organizationService, memberService, memberRepository,
                userRepository, onboardingService, auditEventRepository, gatewayCacheClient);
    }

    private void membershipWithRole(OrganizationRole role) {
        OrganizationMember m = mock(OrganizationMember.class);
        when(m.getRole()).thenReturn(role);
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, userId)).thenReturn(Optional.of(m));
    }

    @Nested
    @DisplayName("getInvitationInfo (public, no-auth lookup for the accept page)")
    class GetInvitationInfo {

        @Test
        @DisplayName("valid token → {valid:true, email, organizationName, role, hasAccount}")
        @SuppressWarnings("unchecked")
        void validToken() {
            when(memberService.getInvitationInfo("good-token")).thenReturn(
                    new OrganizationMemberService.InvitationInfo(
                            true, "invitee@example.com", "Acme", OrganizationRole.MEMBER, true));

            ResponseEntity<?> resp = controller.getInvitationInfo("good-token");

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("valid")).isEqualTo(true);
            assertThat(body.get("email")).isEqualTo("invitee@example.com");
            assertThat(body.get("organizationName")).isEqualTo("Acme");
            assertThat(body.get("role")).isEqualTo(OrganizationRole.MEMBER);
            assertThat(body.get("hasAccount")).isEqualTo(true);
        }

        @Test
        @DisplayName("invalid token → ONLY {valid:false}; email/org/role/hasAccount are absent (no leak)")
        @SuppressWarnings("unchecked")
        void invalidToken() {
            when(memberService.getInvitationInfo("bad-token"))
                    .thenReturn(OrganizationMemberService.InvitationInfo.invalid());

            ResponseEntity<?> resp = controller.getInvitationInfo("bad-token");

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("valid")).isEqualTo(false);
            // A bogus/expired/used token must leak nothing beyond valid:false.
            assertThat(body).containsOnlyKeys("valid");
            assertThat(body).doesNotContainKey("email");
            assertThat(body).doesNotContainKey("organizationName");
            assertThat(body).doesNotContainKey("role");
            assertThat(body).doesNotContainKey("hasAccount");
        }
    }

    @Nested
    @DisplayName("inviter name resolution (CE: no onboarding row → real name, not \"Unknown\")")
    class InviterNameResolution {

        private OrganizationInvitation invitationFrom(Long inviterId, String email) {
            Organization org = new Organization();
            org.setName("Acme");
            User inviter = new User();
            inviter.setId(inviterId);
            return new OrganizationInvitation(org, email, OrganizationRole.MEMBER, inviter);
        }

        @Test
        @DisplayName("getPendingInvitations resolves invitedByName via the shared fallback chain, never \"Unknown\"")
        @SuppressWarnings("unchecked")
        void pendingInvitationsResolveInviterName() {
            OrganizationInvitation inv = invitationFrom(5L, "invitee@example.com");
            when(memberService.getPendingInvitations(orgId, userId)).thenReturn(List.of(inv));
            when(onboardingService.resolveDisplayNames(anyList())).thenReturn(Map.of(5L, "Ada Lovelace"));

            ResponseEntity<?> resp = controller.getPendingInvitations(orgId, userId);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            List<InvitationDto> dtos = (List<InvitationDto>) resp.getBody();
            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).getInvitedByName()).isEqualTo("Ada Lovelace");
            assertThat(dtos.get(0).getInvitedByName()).isNotEqualTo("Unknown");
        }

        @Test
        @DisplayName("inviteMember echoes the inviter's resolved display name (email is normalized lowercase)")
        void inviteMemberResolvesInviterName() {
            OrganizationInvitation inv = invitationFrom(userId, "invitee@example.com");
            when(memberService.inviteMember(orgId, "invitee@example.com", OrganizationRole.MEMBER, userId))
                    .thenReturn(inv);
            when(onboardingService.resolveDisplayName(userId)).thenReturn("Ada Lovelace");

            ResponseEntity<?> resp = controller.inviteMember(
                    orgId, userId, Map.of("email", "Invitee@Example.com", "role", "MEMBER"));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            InvitationDto dto = (InvitationDto) resp.getBody();
            assertThat(dto).isNotNull();
            assertThat(dto.getInvitedByName()).isEqualTo("Ada Lovelace");
        }

        @Test
        @DisplayName("getMyPendingInvitations (the invitee inbox feed) resolves invitedByName, never \"Unknown\"")
        @SuppressWarnings("unchecked")
        void myPendingInvitationsResolveInviterName() {
            User caller = new User();
            caller.setId(userId);
            caller.setEmail("me@example.com");
            OrganizationInvitation inv = invitationFrom(5L, "me@example.com");
            when(userRepository.findById(userId)).thenReturn(Optional.of(caller));
            when(memberService.getPendingInvitationsForEmail("me@example.com")).thenReturn(List.of(inv));
            when(onboardingService.resolveDisplayNames(anyList())).thenReturn(Map.of(5L, "Ada Lovelace"));

            ResponseEntity<?> resp = controller.getMyPendingInvitations(userId);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            List<InvitationDto> dtos = (List<InvitationDto>) resp.getBody();
            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).getInvitedByName()).isEqualTo("Ada Lovelace");
            assertThat(dtos.get(0).getInvitedByName()).isNotEqualTo("Unknown");
        }

        @Test
        @DisplayName("declineInvitationById resolves invitedByName via the chain instead of the old hardcoded \"Unknown\"")
        void declineResolvesInviterName() {
            OrganizationInvitation inv = invitationFrom(5L, "invitee@example.com");
            when(memberService.declineInvitationById(any(UUID.class), eq(userId))).thenReturn(inv);
            when(onboardingService.resolveDisplayName(5L)).thenReturn("Ada Lovelace");

            ResponseEntity<?> resp = controller.declineInvitationById(UUID.randomUUID(), userId);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            InvitationDto dto = (InvitationDto) resp.getBody();
            assertThat(dto).isNotNull();
            assertThat(dto.getInvitedByName()).isEqualTo("Ada Lovelace");
            assertThat(dto.getInvitedByName()).isNotEqualTo("Unknown");
        }
    }

    @Nested
    @DisplayName("member display-name resolution (CE: shared chain, not raw email)")
    class MemberNameResolution {

        private OrganizationMember memberOf(Organization org, Long userId, String email) {
            User u = new User();
            u.setId(userId);
            u.setEmail(email);
            return new OrganizationMember(org, u, OrganizationRole.MEMBER, false);
        }

        @Test
        @DisplayName("getOrganization resolves member displayName via the shared chain (CE: real name, not the raw email)")
        void memberListResolvesViaSharedChain() {
            Organization org = new Organization();
            org.setId(orgId);
            org.setName("Acme");
            OrganizationMember caller = memberOf(org, userId, "owner@example.com");
            OrganizationMember member = memberOf(org, 5L, "member@example.com");

            when(memberRepository.findActiveByOrganizationIdAndUserId(orgId, userId))
                    .thenReturn(Optional.of(caller));
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(2L);
            when(memberRepository.findByOrganization_Id(orgId)).thenReturn(List.of(caller, member));
            when(onboardingService.resolveDisplayNames(anyList()))
                    .thenReturn(Map.of(userId, "Owner Name", 5L, "Ada Lovelace"));

            ResponseEntity<OrganizationDto> resp = controller.getOrganization(orgId, userId);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            OrganizationDto dto = resp.getBody();
            assertThat(dto).isNotNull();
            assertThat(dto.getMembers()).extracting(OrganizationDto.MemberDto::getDisplayName)
                    .containsExactly("Owner Name", "Ada Lovelace");
        }

        @Test
        @DisplayName("getOrganization falls back to the member email when the chain returns nothing for that id")
        void memberListFallsBackToEmail() {
            Organization org = new Organization();
            org.setId(orgId);
            org.setName("Acme");
            OrganizationMember caller = memberOf(org, userId, "owner@example.com");

            when(memberRepository.findActiveByOrganizationIdAndUserId(orgId, userId))
                    .thenReturn(Optional.of(caller));
            when(memberRepository.countByOrganization_Id(orgId)).thenReturn(1L);
            when(memberRepository.findByOrganization_Id(orgId)).thenReturn(List.of(caller));
            // No id resolves (e.g. nothing came back) → controller uses the email fallback.
            when(onboardingService.resolveDisplayNames(anyList())).thenReturn(Map.of());

            ResponseEntity<OrganizationDto> resp = controller.getOrganization(orgId, userId);

            OrganizationDto dto = resp.getBody();
            assertThat(dto).isNotNull();
            assertThat(dto.getMembers().get(0).getDisplayName()).isEqualTo("owner@example.com");
        }

        @Test
        @DisplayName("changeMemberRole falls back to the member email when the chain resolves null")
        void changeRoleFallsBackToEmail() {
            Organization org = new Organization();
            org.setId(orgId);
            OrganizationMember updated = memberOf(org, 5L, "member@example.com");
            when(memberService.changeRole(eq(orgId), eq(5L), eq(OrganizationRole.ADMIN), eq(userId)))
                    .thenReturn(updated);
            when(onboardingService.resolveDisplayName(5L)).thenReturn(null);

            ResponseEntity<?> resp = controller.changeMemberRole(
                    orgId, 5L, userId, Map.of("role", "ADMIN"));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            OrganizationDto.MemberDto dto = (OrganizationDto.MemberDto) resp.getBody();
            assertThat(dto).isNotNull();
            assertThat(dto.getDisplayName()).isEqualTo("member@example.com");
        }
    }

    @Nested
    @DisplayName("uploadOrgAvatar")
    class Upload {

        @Test
        @DisplayName("400 when the X-User-ID header is missing")
        void missingHeader() {
            ResponseEntity<?> resp = controller.uploadOrgAvatar(orgId, mock(MultipartFile.class), null);
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(organizationService);
        }

        @Test
        @DisplayName("404 when the caller is not a member of the org")
        void notAMember() {
            when(memberRepository.findByOrganization_IdAndUser_Id(orgId, userId)).thenReturn(Optional.empty());
            ResponseEntity<?> resp = controller.uploadOrgAvatar(orgId, mock(MultipartFile.class), userId);
            assertThat(resp.getStatusCode().value()).isEqualTo(404);
            verifyNoInteractions(organizationService);
        }

        @Test
        @DisplayName("403 for a VIEWER (not OWNER/ADMIN)")
        void viewerForbidden() {
            membershipWithRole(OrganizationRole.VIEWER);
            ResponseEntity<?> resp = controller.uploadOrgAvatar(orgId, mock(MultipartFile.class), userId);
            assertThat(resp.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(organizationService);
        }

        @Test
        @DisplayName("403 for a plain MEMBER")
        void memberForbidden() {
            membershipWithRole(OrganizationRole.MEMBER);
            ResponseEntity<?> resp = controller.uploadOrgAvatar(orgId, mock(MultipartFile.class), userId);
            assertThat(resp.getStatusCode().value()).isEqualTo(403);
            verifyNoInteractions(organizationService);
        }

        @Test
        @DisplayName("200 for an OWNER - delegates to the service")
        void ownerUploads() throws Exception {
            membershipWithRole(OrganizationRole.OWNER);
            MultipartFile file = mock(MultipartFile.class);
            when(file.getBytes()).thenReturn(new byte[]{1, 2});
            when(file.getContentType()).thenReturn("image/png");
            when(file.getOriginalFilename()).thenReturn("logo.png");
            when(organizationService.uploadAvatar(eq(orgId), any(), eq("image/png"), eq("logo.png")))
                    .thenReturn("storage-uuid");

            ResponseEntity<?> resp = controller.uploadOrgAvatar(orgId, file, userId);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            verify(organizationService).uploadAvatar(eq(orgId), any(), eq("image/png"), eq("logo.png"));
        }

        @Test
        @DisplayName("400 when the service rejects the image (bad type / oversized)")
        void ownerBadImage() throws Exception {
            membershipWithRole(OrganizationRole.OWNER);
            MultipartFile file = mock(MultipartFile.class);
            when(file.getBytes()).thenReturn(new byte[]{1});
            when(file.getContentType()).thenReturn("application/pdf");
            when(file.getOriginalFilename()).thenReturn("x.pdf");
            when(organizationService.uploadAvatar(eq(orgId), any(), anyString(), anyString()))
                    .thenThrow(new IllegalArgumentException("Unsupported image type: application/pdf"));

            ResponseEntity<?> resp = controller.uploadOrgAvatar(orgId, file, userId);

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("500 when storage fails unexpectedly")
        void ownerStorageFailure() throws Exception {
            membershipWithRole(OrganizationRole.OWNER);
            MultipartFile file = mock(MultipartFile.class);
            when(file.getBytes()).thenReturn(new byte[]{1});
            when(file.getContentType()).thenReturn("image/png");
            when(file.getOriginalFilename()).thenReturn("logo.png");
            when(organizationService.uploadAvatar(eq(orgId), any(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("storage down"));

            ResponseEntity<?> resp = controller.uploadOrgAvatar(orgId, file, userId);

            assertThat(resp.getStatusCode().value()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("deleteOrgAvatar")
    class Delete {

        @Test
        @DisplayName("403 for a VIEWER")
        void viewerForbidden() {
            membershipWithRole(OrganizationRole.VIEWER);
            ResponseEntity<?> resp = controller.deleteOrgAvatar(orgId, userId);
            assertThat(resp.getStatusCode().value()).isEqualTo(403);
            verify(organizationService, never()).deleteAvatar(any());
        }

        @Test
        @DisplayName("204 for an ADMIN - delegates to the service")
        void adminDeletes() {
            membershipWithRole(OrganizationRole.ADMIN);
            ResponseEntity<?> resp = controller.deleteOrgAvatar(orgId, userId);
            assertThat(resp.getStatusCode().value()).isEqualTo(204);
            verify(organizationService).deleteAvatar(orgId);
        }
    }

    @Nested
    @DisplayName("getAuditLog - resolves user ids to display names")
    class AuditLogNames {

        @Test
        @DisplayName("userNames map uses the sidebar display names (OnboardingService) for actor + event_data target id")
        @SuppressWarnings("unchecked")
        void resolvesActorAndTargetNames() {
            membershipWithRole(OrganizationRole.OWNER);
            OrganizationAuditEvent event = new OrganizationAuditEvent(
                    orgId, 1L, "ORG_ROLE_CHANGED", Map.of("targetUserId", 5, "oldRole", "MEMBER", "newRole", "ADMIN"));
            when(auditEventRepository.findByOrgIdOrderByCreatedAtDesc(eq(orgId), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(event)));
            // The display names come from OnboardingService (user_onboarding.display_name),
            // the same source as the sidebar / members table - not raw first/last.
            when(onboardingService.resolveDisplayNames(any()))
                    .thenReturn(Map.of(1L, "ada lovelace", 5L, "livecontextai"));

            ResponseEntity<?> resp = controller.getAuditLog(orgId, userId, null, 0, 50);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            Map<String, String> names = (Map<String, String>) body.get("userNames");
            assertThat(names).containsEntry("1", "ada lovelace").containsEntry("5", "livecontextai");
        }
    }

    /**
     * Regression (whole-class): the workspace avatar is the same kind of mutable-URL
     * resource as the user avatar. A bare {@code max-age=1 DAY} pinned a stale workspace
     * avatar (notably after a rename, which the {@code ?v=} upload buster does not cover)
     * for up to a day. Both responses must now revalidate ({@code no-cache}).
     */
    @Nested
    @DisplayName("getOrgAvatar - Cache-Control forces revalidation (no day-long pin)")
    class GetOrgAvatarCache {

        @Test
        @DisplayName("Uploaded workspace photo → no-cache, NOT max-age=86400")
        void uploadedPhotoRevalidates() {
            StorageEntity entity = new StorageEntity();
            entity.setDataBinary(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2 });
            entity.setMimeType("image/jpeg");
            when(organizationService.getAvatarEntity(orgId)).thenReturn(Optional.of(entity));

            ResponseEntity<byte[]> resp = controller.getOrgAvatar(orgId);

            assertThat(resp.getHeaders().getCacheControl())
                    .isEqualTo("no-cache")
                    .doesNotContain("max-age=86400");
        }

        @Test
        @DisplayName("Initials SVG (rename case) → no-cache, NOT max-age=86400")
        void initialsSvgRevalidates() {
            when(organizationService.getAvatarEntity(orgId)).thenReturn(Optional.empty());
            Organization org = mock(Organization.class);
            when(org.getName()).thenReturn("Acme Workspace");
            when(organizationService.findById(orgId)).thenReturn(Optional.of(org));

            ResponseEntity<byte[]> resp = controller.getOrgAvatar(orgId);

            assertThat(resp.getHeaders().getContentType().toString()).contains("image/svg");
            assertThat(resp.getHeaders().getCacheControl())
                    .isEqualTo("no-cache")
                    .doesNotContain("max-age=86400");
        }
    }
}
