package com.apimarketplace.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link ToolAccessControl#checkRoleWriteAccess}: the workspace-role axis that makes a
 * VIEWER read-only on the tool path, next to the per-agent access mode. It must refuse every
 * non-READ action of a VIEWER inside a workspace, and be a strict no-op for everyone else.
 */
@DisplayName("ToolAccessControl.checkRoleWriteAccess - VIEWER is read-only on tools")
class ToolAccessControlRoleWriteGateTest {

    @Test
    @DisplayName("a VIEWER in a workspace is refused a WRITE action, with the reads listed")
    void viewerRefusedWrite() {
        Optional<String> denied = ToolAccessControl.checkRoleWriteAccess("org-1", "VIEWER", "workflow", "delete");

        assertThat(denied).isPresent();
        assertThat(denied.get()).contains("VIEWER").contains("'delete'").contains("get_run");
    }

    @Test
    @DisplayName("the role match is case- and whitespace-insensitive, like OrgAccessGuard.isRoleWriteBlocked")
    void viewerMatchIsLenient() {
        assertThat(ToolAccessControl.checkRoleWriteAccess("org-1", " viewer ", "file", "create_folder")).isPresent();
    }

    @Test
    @DisplayName("a VIEWER keeps every READ action of the category")
    void viewerKeepsReads() {
        for (String read : ToolAccessControl.readActions("workflow")) {
            assertThat(ToolAccessControl.checkRoleWriteAccess("org-1", "VIEWER", "workflow", read))
                    .as(read).isEmpty();
        }
    }

    @Test
    @DisplayName("OWNER / ADMIN / MEMBER / no role are never refused")
    void writersPass() {
        for (String role : new String[] {"OWNER", "ADMIN", "MEMBER", null, ""}) {
            assertThat(ToolAccessControl.checkRoleWriteAccess("org-1", role, "workflow", "delete"))
                    .as(String.valueOf(role)).isEmpty();
        }
    }

    @Test
    @DisplayName("a VIEWER role without a workspace (no org) is not a workspace role and is not refused")
    void viewerOutsideWorkspacePasses() {
        assertThat(ToolAccessControl.checkRoleWriteAccess(null, "VIEWER", "workflow", "delete")).isEmpty();
        assertThat(ToolAccessControl.checkRoleWriteAccess("  ", "VIEWER", "workflow", "delete")).isEmpty();
    }

    @Test
    @DisplayName("an unclassified category fails CLOSED for a VIEWER: nothing is a known read")
    void unclassifiedCategoryFailsClosed() {
        assertThat(ToolAccessControl.checkRoleWriteAccess("org-1", "VIEWER", "not-a-category", "get")).isPresent();
    }
}
