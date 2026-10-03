package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-037: an APPLICATION share token resolves to the OWNER's real identity, so a plain
 * strict-scope (tenant/org) check on a resource that carries its own {@code workflowId} (e.g. a
 * stored file) would let the visitor read every file the owner has, not just the ones produced by
 * the SHARED workflow. {@link ShareContextResourceBinding#permitsWorkflowResource} closes that gap.
 */
@DisplayName("ShareContextResourceBinding (LC-037)")
class ShareContextResourceBindingTest {

    private static final String SHARED_WORKFLOW_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_WORKFLOW_ID = "22222222-2222-2222-2222-222222222222";

    @Test
    @DisplayName("non-share request (no X-Share-Context) is unaffected regardless of workflow id")
    void nonShareRequestUnaffected() {
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                null, null, null, OTHER_WORKFLOW_ID)).isTrue();
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "false", null, null, null)).isTrue();
    }

    @Test
    @DisplayName("APPLICATION share: a file stamped with the SAME shared workflow id is allowed")
    void sameWorkflowAllowed() {
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "true", "APPLICATION", SHARED_WORKFLOW_ID, SHARED_WORKFLOW_ID)).isTrue();
    }

    @Test
    @DisplayName("pre-fix regression: APPLICATION share holder cannot pivot to a DIFFERENT workflow's file")
    void differentWorkflowDenied() {
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "true", "APPLICATION", SHARED_WORKFLOW_ID, OTHER_WORKFLOW_ID)).isFalse();
    }

    @Test
    @DisplayName("a file with no workflow association at all is denied in a share context (fail closed)")
    void noWorkflowAssociationDenied() {
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "true", "APPLICATION", SHARED_WORKFLOW_ID, null)).isFalse();
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "true", "APPLICATION", SHARED_WORKFLOW_ID, "")).isFalse();
    }

    @Test
    @DisplayName("a non-APPLICATION share type is denied outright (owner-impersonation is illegitimate there)")
    void nonApplicationShareTypeDenied() {
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "true", "CHAT", SHARED_WORKFLOW_ID, SHARED_WORKFLOW_ID)).isFalse();
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "true", null, SHARED_WORKFLOW_ID, SHARED_WORKFLOW_ID)).isFalse();
    }

    @Test
    @DisplayName("a share request with no X-Share-Resource-Id at all is denied, never matched loosely")
    void missingResourceIdDenied() {
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "true", "APPLICATION", null, SHARED_WORKFLOW_ID)).isFalse();
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "true", "APPLICATION", "", SHARED_WORKFLOW_ID)).isFalse();
    }

    @Test
    @DisplayName("X-Share-Context is case-insensitive, like every other boolean header in this codebase")
    void shareContextCaseInsensitive() {
        assertThat(ShareContextResourceBinding.permitsWorkflowResource(
                "TRUE", "application", SHARED_WORKFLOW_ID, SHARED_WORKFLOW_ID)).isTrue();
    }
}
