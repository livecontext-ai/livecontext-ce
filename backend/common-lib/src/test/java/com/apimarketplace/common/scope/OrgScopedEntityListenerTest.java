package com.apimarketplace.common.scope;

import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Locks down the contract of the post-V263 @PrePersist guard:
 *
 * <ol>
 *   <li>If the entity already has an organizationId → listener does nothing.</li>
 *   <li>If the entity has null orgId AND the request thread carries
 *       {@code X-Organization-ID} → listener fills it.</li>
 *   <li>If the entity has null orgId AND an async scope is bound via
 *       {@link TenantResolver#runWithOrgScope} → listener fills it.</li>
 *   <li>HOTFIX-2 (2026-05-20): if null orgId AND no binding at all → listener
 *       throws {@link IllegalStateException} to surface the dispatch-site stack
 *       at the actual offending caller (post-V263 NOT NULL would otherwise
 *       cascade-fail a different insert in the same TX).</li>
 *   <li>Non-{@link OrgScopedEntity} objects are ignored - no reflection, no
 *       NoSuchMethodException risk.</li>
 * </ol>
 */
@DisplayName("OrgScopedEntityListener - post-V263 @PrePersist guard")
class OrgScopedEntityListenerTest {

    private final OrgScopedEntityListener listener = new OrgScopedEntityListener();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bindOrgIdToCurrentThread(String orgId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (orgId != null) {
            request.addHeader("X-Organization-ID", orgId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("Skip when entity already has organizationId set")
    void skipsWhenAlreadySet() {
        TestEntity entity = new TestEntity();
        entity.setOrganizationId("explicit-org");
        bindOrgIdToCurrentThread("request-org");

        listener.ensureOrgId(entity);

        assertThat(entity.getOrganizationId()).isEqualTo("explicit-org");
    }

    @Test
    @DisplayName("Fill from request context when null AND header present")
    void fillsFromRequestContext() {
        TestEntity entity = new TestEntity();
        bindOrgIdToCurrentThread("request-org-from-header");

        listener.ensureOrgId(entity);

        assertThat(entity.getOrganizationId()).isEqualTo("request-org-from-header");
    }

    @Test
    @DisplayName("Throws IllegalStateException when no request context AND no async scope (daemon path)")
    void throwsOnDaemonThreadWithoutBinding() {
        TestEntity entity = new TestEntity();
        // no bindOrgIdToCurrentThread → no request context, no async scope

        assertThatThrownBy(() -> listener.ensureOrgId(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TestEntity")
                .hasMessageContaining("runWithOrgScope")
                .hasMessageContaining("null organizationId");

        // entity stays untouched - the throw aborts the persist
        assertThat(entity.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("Throws IllegalStateException when request bound but X-Organization-ID header missing AND no async scope")
    void throwsWhenHeaderMissingAndNoAsyncScope() {
        TestEntity entity = new TestEntity();
        bindOrgIdToCurrentThread(null); // request bound but no org header

        assertThatThrownBy(() -> listener.ensureOrgId(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TestEntity");
    }

    @Test
    @DisplayName("Fills from async scope binding when no request context (covers ForkJoinPool worker thread)")
    void fillsFromAsyncScopeBinding() {
        TestEntity entity = new TestEntity();
        // no request context - simulating a ForkJoinPool worker thread

        TenantResolver.runWithOrgScope("async-org-from-runWithOrgScope", () ->
                listener.ensureOrgId(entity));

        assertThat(entity.getOrganizationId()).isEqualTo("async-org-from-runWithOrgScope");
    }

    @Test
    @DisplayName("Ignore non-OrgScopedEntity inputs - defensive against misconfiguration")
    void ignoresNonOrgScopedEntities() {
        Object unrelated = new Object();
        bindOrgIdToCurrentThread("some-org");

        assertThatCode(() -> listener.ensureOrgId(unrelated)).doesNotThrowAnyException();

        // Nothing else to assert beyond "didn't throw".
    }

    /** Minimal OrgScopedEntity implementation for unit tests. */
    static class TestEntity implements OrgScopedEntity {
        private String organizationId;

        @Override
        public String getOrganizationId() {
            return organizationId;
        }

        @Override
        public void setOrganizationId(String organizationId) {
            this.organizationId = organizationId;
        }
    }
}
