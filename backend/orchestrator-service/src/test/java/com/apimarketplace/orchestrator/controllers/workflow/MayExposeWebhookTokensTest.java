package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The last door on the execution surface (LC-012, security audit 2026-08-13).
 *
 * <p>Every gate added in this pass refuses a read-only member who asks the platform to run a
 * workflow. None of them can help if the platform first hands that member the workflow's webhook
 * token, because {@code POST /webhook/{token}} is deliberately unauthenticated: its callers are
 * external systems, and it cannot ask who is knocking. Holding the token IS the capability.
 *
 * <p>So the token travels under the same rule as the capability it grants: the SAME
 * {@code canWrite} call {@code canExecuteRun} makes, not a role-only shortcut. The difference
 * bites on a member holding a READ-level per-resource restriction, who is not a VIEWER and would
 * sail through a role-only check while being refused every endpoint that fires the workflow.
 */
@DisplayName("WorkflowControllerHelper.mayExposeWebhookTokens")
class MayExposeWebhookTokensTest {

    private static final String CALLER = "user-1";
    private static final String ORG = "org-1";
    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-4000-8000-00000000000f");

    private final OrgAccessGuard guard = mock(OrgAccessGuard.class);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * Bind a request the way the gateway leaves one: the caller's identity in headers, and the
     * share flag only for an anonymous share visitor.
     */
    private void bindRequest(boolean shareContext) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", CALLER);
        if (shareContext) {
            request.addHeader("X-Share-Context", "true");
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private WorkflowEntity workflow(String orgId) {
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(WORKFLOW_ID);
        entity.setTenantId("owner-1");
        entity.setOrganizationId(orgId);
        return entity;
    }

    private boolean askAs(String orgId, String orgRole, WorkflowEntity workflow) {
        AtomicBoolean out = new AtomicBoolean();
        TenantResolver.runWithOrgScope(orgId, orgRole,
                () -> out.set(WorkflowControllerHelper.mayExposeWebhookTokens(workflow, guard)));
        return out.get();
    }

    @Test
    @DisplayName("a read-only VIEWER is not given the token that would let them fire the workflow")
    void viewerIsRefused() {
        bindRequest(false);
        when(guard.canWrite(ORG, CALLER, "workflow", WORKFLOW_ID.toString(), "VIEWER")).thenReturn(false);

        assertThat(askAs(ORG, "VIEWER", workflow(ORG))).isFalse();
    }

    @Test
    @DisplayName("a member denied this workflow is refused too, not only a VIEWER")
    void deniedMemberIsRefused() {
        // The reason this asks canWrite instead of the role-only shortcut: a MEMBER carrying a
        // READ-level restriction on this workflow passes every role test and must still not get
        // the credential that runs it.
        bindRequest(false);
        when(guard.canWrite(ORG, CALLER, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(false);

        assertThat(askAs(ORG, "MEMBER", workflow(ORG))).isFalse();
    }

    @Test
    @DisplayName("a member who may run the workflow still gets its token")
    void allowedMemberStillGetsTheToken() {
        // Anti-vacuity: refusing everybody would satisfy the two tests above. This pins the
        // blast radius to callers who may not run the workflow, and nobody else.
        bindRequest(false);
        when(guard.canWrite(ORG, CALLER, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(true);

        assertThat(askAs(ORG, "MEMBER", workflow(ORG))).isTrue();
        verify(guard).canWrite(ORG, CALLER, "workflow", WORKFLOW_ID.toString(), "MEMBER");
    }

    @Test
    @DisplayName("an anonymous share visitor is still refused, as before")
    void shareVisitorStaysRefused() {
        // The pre-existing rule this one was built next to. It must keep holding, including for
        // a caller whose role would otherwise allow it, and without consulting the guard at all.
        bindRequest(true);

        assertThat(askAs(ORG, "OWNER", workflow(ORG))).isFalse();
        verify(guard, never()).canWrite(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("outside an organization the token is exposed, there being no role to enforce")
    void personalWorkflowIsUntouched() {
        bindRequest(false);

        assertThat(askAs(null, null, workflow(null))).isTrue();
        verify(guard, never()).canWrite(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an undecidable question refuses: no workflow, or no guard")
    void failsClosed() {
        // Same contract as canExecuteRun. A credential is the last thing to hand out on a
        // maybe: a missing guard bean would otherwise make the check a silent no-op.
        bindRequest(false);

        assertThat(askAs(ORG, "OWNER", null)).isFalse();

        AtomicBoolean noGuard = new AtomicBoolean(true);
        TenantResolver.runWithOrgScope(ORG, "OWNER",
                () -> noGuard.set(WorkflowControllerHelper.mayExposeWebhookTokens(workflow(ORG), null)));
        assertThat(noGuard).isFalse();
    }

    @Test
    @DisplayName("an internal call with no request bound is not treated as a share, nor refused")
    void internalCallIsUntouched() {
        // No RequestContextHolder attributes: service-to-service work must not silently lose the
        // tokens it needs to build a response. Outside an org there is no role to enforce.
        assertThat(WorkflowControllerHelper.mayExposeWebhookTokens(workflow(null), guard)).isTrue();
    }
}
