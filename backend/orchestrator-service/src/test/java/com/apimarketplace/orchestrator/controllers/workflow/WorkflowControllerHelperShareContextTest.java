package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the share-token cross-run read leak fix.
 *
 * <p>The gateway resolves a share token to the OWNER's identity, so the strict
 * scope check alone would let a holder of ONE application share link read ANY
 * run of the same owner. {@link WorkflowControllerHelper#shareContextPermitsRun}
 * binds a share-context run read to the shared application's publication.
 */
@DisplayName("WorkflowControllerHelper - share-context run binding")
class WorkflowControllerHelperShareContextTest {

    private static final String CALLER = "tenant-1";
    private static final String ORG = "org-1";

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void shareRequest(String shareContext, String resourceType, String resourceToken) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (shareContext != null) req.addHeader("X-Share-Context", shareContext);
        if (resourceType != null) req.addHeader("X-Share-Resource-Type", resourceType);
        if (resourceToken != null) req.addHeader("X-Share-Resource-Token", resourceToken);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    /** A run owned by CALLER/ORG (so the strict-scope check passes), with the given publication id. */
    private WorkflowRunEntity ownerRun(String publicationId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(CALLER);
        run.setOrganizationId(ORG);
        run.setPublicationId(publicationId);
        return run;
    }

    @Test
    @DisplayName("no share context (normal authenticated request) → strict scope unchanged")
    void noShareContext_strictScopeUnchanged() {
        // no request attributes at all → internal/non-share path
        assertThat(WorkflowControllerHelper.isRunInScope(ownerRun("pub-A"), CALLER, ORG)).isTrue();
        assertThat(WorkflowControllerHelper.isRunInScope(ownerRun("pub-A"), "other-tenant", "other-org")).isFalse();
    }

    @Test
    @DisplayName("APPLICATION share + run of the SHARED publication → permitted")
    void applicationShare_matchingPublication_permitted() {
        shareRequest("true", "APPLICATION", "pub-A");
        assertThat(WorkflowControllerHelper.isRunInScope(ownerRun("pub-A"), CALLER, ORG)).isTrue();
    }

    @Test
    @DisplayName("APPLICATION share + run of a DIFFERENT publication (same owner) → DENIED (the leak)")
    void applicationShare_foreignPublication_denied() {
        shareRequest("true", "APPLICATION", "pub-A");
        // strict scope passes (same owner) but the run belongs to another publication → must be denied
        assertThat(WorkflowControllerHelper.isRunInScope(ownerRun("pub-B"), CALLER, ORG)).isFalse();
    }

    @Test
    @DisplayName("APPLICATION share + run with no publication id → DENIED")
    void applicationShare_nullPublication_denied() {
        shareRequest("true", "APPLICATION", "pub-A");
        assertThat(WorkflowControllerHelper.isRunInScope(ownerRun(null), CALLER, ORG)).isFalse();
    }

    @Test
    @DisplayName("non-APPLICATION share type → run binding not applied (behavior unchanged)")
    void nonApplicationShare_unchanged() {
        shareRequest("true", "INTERFACE", "iface-token");
        assertThat(WorkflowControllerHelper.isRunInScope(ownerRun("pub-B"), CALLER, ORG)).isTrue();
    }

    @Test
    @DisplayName("X-Share-Context not 'true' → treated as a normal request")
    void shareContextFalse_unchanged() {
        shareRequest("false", "APPLICATION", "pub-A");
        assertThat(WorkflowControllerHelper.isRunInScope(ownerRun("pub-B"), CALLER, ORG)).isTrue();
    }
}
