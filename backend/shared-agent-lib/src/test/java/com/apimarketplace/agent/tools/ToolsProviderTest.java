package com.apimarketplace.agent.tools;

import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolsProvider inner records (ToolExecutionContext, ToolExecutionResult).
 */
@DisplayName("ToolsProvider inner types")
class ToolsProviderTest {

    @Nested
    @DisplayName("ToolExecutionContext")
    class ContextTests {

        @Test
        @DisplayName("of() should create context with tenant only")
        void ofShouldCreateWithTenant() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext.of("t-1");

            assertThat(ctx.tenantId()).isEqualTo("t-1");
            assertThat(ctx.credentials()).isEmpty();
            assertThat(ctx.variables()).isEmpty();
            assertThat(ctx.approvedServices()).isEmpty();
            assertThat(ctx.viewingWorkflowId()).isNull();
        }

        @Test
        @DisplayName("empty() should create empty context")
        void emptyShouldCreateEmpty() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext.empty();

            assertThat(ctx.tenantId()).isNull();
            assertThat(ctx.credentials()).isEmpty();
        }

        @Test
        @DisplayName("withApprovedServices() should create context with services")
        void withServicesShouldCreate() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext
                    .withApprovedServices("t-1", Set.of("gmail", "slack"));

            assertThat(ctx.tenantId()).isEqualTo("t-1");
            assertThat(ctx.approvedServices()).containsExactlyInAnyOrder("gmail", "slack");
        }

        @Test
        @DisplayName("withApprovedServices() should handle null services")
        void withServicesNullShouldUseEmptySet() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext
                    .withApprovedServices("t-1", null);

            assertThat(ctx.approvedServices()).isEmpty();
        }

        @Test
        @DisplayName("withWorkflowContext() should create context with workflow info")
        void withWorkflowContextShouldCreate() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext
                    .withWorkflowContext("t-1", Set.of("gmail"), "wf-123", "My Workflow");

            assertThat(ctx.viewingWorkflowId()).isEqualTo("wf-123");
            assertThat(ctx.viewingWorkflowName()).isEqualTo("My Workflow");
        }

        @Test
        @DisplayName("isServiceApproved() should check approved services")
        void isServiceApproved() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext
                    .withApprovedServices("t-1", Set.of("gmail"));

            assertThat(ctx.isServiceApproved("gmail")).isTrue();
            assertThat(ctx.isServiceApproved("slack")).isFalse();
        }

        @Test
        @DisplayName("isViewingWorkflow() should check for workflow context")
        void isViewingWorkflow() {
            ToolsProvider.ToolExecutionContext withWf = ToolsProvider.ToolExecutionContext
                    .withWorkflowContext("t-1", null, "wf-1", "WF");
            ToolsProvider.ToolExecutionContext withoutWf = ToolsProvider.ToolExecutionContext.of("t-1");

            assertThat(withWf.isViewingWorkflow()).isTrue();
            assertThat(withoutWf.isViewingWorkflow()).isFalse();
        }

        @Test
        @DisplayName("convenience factories carry no implicit org scope (orgId/orgRole stay null)")
        void factoriesProduceNoImplicitOrgScope() {
            // The org-scoping invariant: a context built from the tenant-only factories
            // must NOT silently inherit any organization. orgId/orgRole are only ever
            // populated through the full constructor, never implied by a tenant id.
            ToolsProvider.ToolExecutionContext of = ToolsProvider.ToolExecutionContext.of("t-1");
            ToolsProvider.ToolExecutionContext empty = ToolsProvider.ToolExecutionContext.empty();
            ToolsProvider.ToolExecutionContext withSvc = ToolsProvider.ToolExecutionContext
                    .withApprovedServices("t-1", Set.of("gmail"));
            ToolsProvider.ToolExecutionContext withWf = ToolsProvider.ToolExecutionContext
                    .withWorkflowContext("t-1", Set.of("gmail"), "wf-1", "WF");

            assertThat(of.orgId()).isNull();
            assertThat(of.orgRole()).isNull();
            assertThat(empty.orgId()).isNull();
            assertThat(empty.orgRole()).isNull();
            assertThat(withSvc.orgId()).isNull();
            assertThat(withSvc.orgRole()).isNull();
            assertThat(withWf.orgId()).isNull();
            assertThat(withWf.orgRole()).isNull();
        }

        @Test
        @DisplayName("full constructor keeps tenantId, orgId and orgRole coherent together")
        void fullConstructorKeepsOrgFieldsCoherent() {
            // When an org is present the three scoping fields must travel together and
            // remain independently addressable, so a tool module can authorize against
            // (tenantId, orgId, orgRole) without one shadowing another.
            ToolsProvider.ToolExecutionContext ctx = new ToolsProvider.ToolExecutionContext(
                    "tenant-7", Map.of(), Map.of(), Set.of(), null, null, "org-42", "admin");

            assertThat(ctx.tenantId()).isEqualTo("tenant-7");
            assertThat(ctx.orgId()).isEqualTo("org-42");
            assertThat(ctx.orgRole()).isEqualTo("admin");
        }
    }

    @Nested
    @DisplayName("ToolExecutionResult")
    class ResultTests {

        @Test
        @DisplayName("success() should create successful result")
        void successShouldCreate() {
            ToolsProvider.ToolExecutionResult result = ToolsProvider.ToolExecutionResult
                    .success(Map.of("key", "value"));

            assertThat(result.success()).isTrue();
            assertThat(result.data()).isEqualTo(Map.of("key", "value"));
            assertThat(result.error()).isNull();
            assertThat(result.errorCode()).isNull();
        }

        @Test
        @DisplayName("success() with metadata should include metadata")
        void successWithMetadata() {
            ToolsProvider.ToolExecutionResult result = ToolsProvider.ToolExecutionResult
                    .success("data", Map.of("marker", "[viz:wf:123]"));

            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).containsEntry("marker", "[viz:wf:123]");
        }

        @Test
        @DisplayName("failure(String) should create failure with default error code")
        void failureWithStringShouldCreate() {
            @SuppressWarnings("deprecation")
            ToolsProvider.ToolExecutionResult result = ToolsProvider.ToolExecutionResult
                    .failure("Something went wrong");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Something went wrong");
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }

        @Test
        @DisplayName("failure(ToolErrorCode, String) should create failure with specific code")
        void failureWithCodeShouldCreate() {
            ToolsProvider.ToolExecutionResult result = ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.TOOL_NOT_FOUND, "Tool xyz not found");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Tool xyz not found");
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("failure(ToolErrorCode) should use default message")
        void failureWithCodeOnlyShouldUseDefault() {
            ToolsProvider.ToolExecutionResult result = ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.TIMEOUT);

            assertThat(result.error()).isEqualTo("Execution timeout");
        }

        @Test
        @DisplayName("toMap() should convert success result")
        void toMapSuccess() {
            ToolsProvider.ToolExecutionResult result = ToolsProvider.ToolExecutionResult
                    .success(Map.of("count", 5));

            Map<String, Object> map = result.toMap();
            assertThat(map.get("success")).isEqualTo(true);
            assertThat(map.get("data")).isEqualTo(Map.of("count", 5));
        }

        @Test
        @DisplayName("toMap() should convert failure result with error code")
        void toMapFailure() {
            ToolsProvider.ToolExecutionResult result = ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.VALIDATION_ERROR, "Bad input");

            Map<String, Object> map = result.toMap();
            assertThat(map.get("success")).isEqualTo(false);
            assertThat(map.get("error")).isEqualTo("Bad input");
            assertThat(map.get("errorCode")).isEqualTo("TOOL_010");
            assertThat(map.get("errorType")).isEqualTo("VALIDATION_ERROR");
        }

        @Test
        @DisplayName("isValidationError() should detect validation errors")
        void isValidationError() {
            assertThat(ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.VALIDATION_ERROR, "err").isValidationError()).isTrue();
            assertThat(ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.MISSING_PARAMETER, "err").isValidationError()).isTrue();
            assertThat(ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.INVALID_PARAMETER_TYPE, "err").isValidationError()).isTrue();
            assertThat(ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.EXECUTION_FAILED, "err").isValidationError()).isFalse();
        }

        @Test
        @DisplayName("isAuthError() should detect auth errors")
        void isAuthError() {
            assertThat(ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.AUTHENTICATION_REQUIRED, "err").isAuthError()).isTrue();
            assertThat(ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.PERMISSION_DENIED, "err").isAuthError()).isTrue();
            assertThat(ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.TENANT_NOT_FOUND, "err").isAuthError()).isTrue();
            assertThat(ToolsProvider.ToolExecutionResult
                    .failure(ToolErrorCode.EXECUTION_FAILED, "err").isAuthError()).isFalse();
        }
    }

    @Nested
    @DisplayName("executeAsync()")
    class ExecuteAsyncTests {

        @Test
        @DisplayName("Binds context orgId on the async worker thread")
        void bindsContextOrgIdOnAsyncWorkerThread() throws Exception {
            ToolsProvider provider = new ToolsProvider() {
                @Override
                public com.apimarketplace.agent.registry.ToolCategory getCategory() {
                    return com.apimarketplace.agent.registry.ToolCategory.UTILITY;
                }

                @Override
                public List<com.apimarketplace.agent.registry.AgentToolDefinition> getTools() {
                    return List.of();
                }

                @Override
                public ToolExecutionResult execute(String toolName, Map<String, Object> parameters,
                                                   ToolExecutionContext context) {
                    return ToolExecutionResult.success(TenantResolver.currentRequestOrganizationId());
                }
            };
            ToolsProvider.ToolExecutionContext context = new ToolsProvider.ToolExecutionContext(
                    "tenant-1", Map.of(), Map.of(), Set.of(), null, null, "org-async-provider", "member");

            ToolsProvider.ToolExecutionResult result = provider.executeAsync("any", Map.of(), context)
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.success()).isTrue();
            assertThat(result.data()).isEqualTo("org-async-provider");
            assertThat(TenantResolver.currentRequestOrganizationId())
                    .as("Async org binding must not leak back to the caller thread.")
                    .isNull();
        }
    }
}
