package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.SkillFolderEntity;
import com.apimarketplace.agent.repository.SkillFolderRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V275 (2026-05-21) - coverage for the admin-only folder is_global toggle.
 *
 * <p>Tests intentionally tight: one behavior each. The pre-V275 contract was
 * "folders cannot be marked global" - these tests pin the new gating shape
 * so future refactors don't accidentally let a USER role flip the flag.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillFolderService - V275 admin-only is_global toggle")
class SkillFolderServiceGlobalTest {

    @Mock private SkillFolderRepository folderRepository;
    @Mock private SkillRepository skillRepository;

    @InjectMocks private SkillFolderService skillFolderService;

    private static final UUID FOLDER_ID = UUID.randomUUID();
    private static final String OWNER_TENANT = "owner_user";
    private static final String CALLER_TENANT = "caller_user";
    private static final String ORG = "org_X";

    private SkillFolderEntity folder(UUID id, String tenantId, String orgId, boolean isGlobal) {
        SkillFolderEntity f = new SkillFolderEntity(tenantId, "Marketing", null);
        f.setId(id);
        if (orgId != null) f.setOrganizationId(orgId);
        f.setIsGlobal(isGlobal);
        return f;
    }

    @Test
    @DisplayName("setFolderGlobal flips the flag when the caller has the ADMIN role")
    void adminCanFlipFolderGlobal() {
        SkillFolderEntity existing = folder(FOLDER_ID, OWNER_TENANT, ORG, false);
        when(folderRepository.findVisibleByIdForOrganization(FOLDER_ID, ORG))
                .thenReturn(Optional.of(existing));
        when(folderRepository.save(any(SkillFolderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillFolderEntity saved = skillFolderService.setFolderGlobal(
                FOLDER_ID, CALLER_TENANT, ORG, true, /* callerIsAdmin */ true);

        assertThat(saved.getIsGlobal()).isTrue();
    }

    @Test
    @DisplayName("setFolderGlobal rejects when the caller is not an admin - no DB write")
    void nonAdminCannotFlipFolderGlobal() {
        assertThatThrownBy(() -> skillFolderService.setFolderGlobal(
                FOLDER_ID, CALLER_TENANT, ORG, true, /* callerIsAdmin */ false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only admins");

        verify(folderRepository, never()).save(any(SkillFolderEntity.class));
        verify(folderRepository, never()).findVisibleByIdForOrganization(any(), any());
    }

    @Test
    @DisplayName("setFolderGlobal can demote a global folder back to personal (true → false)")
    void adminCanUnmakeFolderGlobal() {
        SkillFolderEntity existing = folder(FOLDER_ID, OWNER_TENANT, ORG, true);
        when(folderRepository.findVisibleByIdForOrganization(FOLDER_ID, ORG))
                .thenReturn(Optional.of(existing));
        when(folderRepository.save(any(SkillFolderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillFolderEntity saved = skillFolderService.setFolderGlobal(
                FOLDER_ID, CALLER_TENANT, ORG, false, /* callerIsAdmin */ true);

        assertThat(saved.getIsGlobal()).isFalse();
    }

    @Test
    @DisplayName("setFolderGlobal routes through findVisibleByIdForOrganization so an admin can reach a folder created in another org")
    void adminReachesCrossOrgGlobalFolder() {
        // Admin's active org is "org_Y" but the folder was created in "org_X" and
        // marked global earlier. The visibility-aware finder must return it.
        SkillFolderEntity crossOrgGlobal = folder(FOLDER_ID, OWNER_TENANT, ORG, true);
        when(folderRepository.findVisibleByIdForOrganization(FOLDER_ID, "org_Y"))
                .thenReturn(Optional.of(crossOrgGlobal));
        when(folderRepository.save(any(SkillFolderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillFolderEntity saved = skillFolderService.setFolderGlobal(
                FOLDER_ID, CALLER_TENANT, "org_Y", false, true);

        assertThat(saved.getIsGlobal()).isFalse();
    }
}
