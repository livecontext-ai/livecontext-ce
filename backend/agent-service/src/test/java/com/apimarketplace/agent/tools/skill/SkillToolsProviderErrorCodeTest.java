package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Error-code + routing-completeness coverage for {@link SkillToolsProvider}, complementing
 * the existing {@code SkillToolsProviderTest} (which asserts only success/failure, never the
 * structured error CODE, and omits the publish module, the tenant gate, the per-module
 * empty-Optional fallbacks, and the exception path).
 *
 * <p>The provider routes (in order) crud → folder → help → publish; help is exempt from the
 * tenant gate; there is no access-mode gate. Note the quirk pinned here: a missing action maps
 * to EXECUTION_FAILED (not MISSING_PARAMETER), while a missing tenant maps to MISSING_PARAMETER.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillToolsProvider - error codes, publish routing, fallbacks")
class SkillToolsProviderErrorCodeTest {

    private static final String TENANT = "tenant-123";

    @Mock private SkillCrudModule crudModule;
    @Mock private SkillHelpModule helpModule;
    @Mock private SkillFolderModule folderModule;
    @Mock private SkillPublishModule publishModule;

    private SkillToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SkillToolsProvider(crudModule, helpModule, folderModule, publishModule);
    }

    private ToolExecutionContext ctx(String tenantId) {
        return new ToolExecutionContext(tenantId, null, Map.of(), null, null, null, null, null);
    }

    private ToolExecutionResult exec(String action) {
        return provider.execute("skill", Map.of("action", action), ctx(TENANT));
    }

    private static ToolExecutionResult ok() {
        return ToolExecutionResult.success(Map.of("ok", true));
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("framing error codes")
    class Framing {

        @Test
        @DisplayName("a wrong tool name → TOOL_NOT_FOUND")
        void wrongTool() {
            ToolExecutionResult r = provider.execute("agent", Map.of("action", "list"), ctx(TENANT));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("a missing action → EXECUTION_FAILED (provider quirk: not MISSING_PARAMETER)")
        void missingAction() {
            ToolExecutionResult r = provider.execute("skill", Map.of(), ctx(TENANT));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("action is required");
        }

        @Test
        @DisplayName("a blank action → EXECUTION_FAILED")
        void blankAction() {
            ToolExecutionResult r = provider.execute("skill", Map.of("action", "  "), ctx(TENANT));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("action is required");
        }

        @Test
        @DisplayName("an action no module handles → EXECUTION_FAILED 'Invalid action'")
        void invalidAction() {
            when(crudModule.canHandle("nope")).thenReturn(false);
            when(folderModule.canHandle("nope")).thenReturn(false);
            when(helpModule.canHandle("nope")).thenReturn(false);
            when(publishModule.canHandle("nope")).thenReturn(false);
            ToolExecutionResult r = exec("nope");
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Invalid action");
        }

        @Test
        @DisplayName("a non-help action with no tenant → MISSING_PARAMETER before any module call")
        void missingTenant() {
            ToolExecutionResult r = provider.execute("skill", Map.of("action", "create"), ctx(null));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(crudModule, never()).execute(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("a module exception is caught → EXECUTION_FAILED")
        void moduleExceptionCaught() {
            when(crudModule.canHandle("create")).thenReturn(true);
            when(crudModule.execute(eq("create"), any(), eq(TENANT), any()))
                    .thenThrow(new RuntimeException("boom"));
            ToolExecutionResult r = exec("create");
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            // pin the "Error: " wrapper prefix, not just the cause text
            assertThat(r.error()).isEqualTo("Error: boom");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("publish routing (uncovered by the existing test)")
    class PublishRouting {

        @Test
        @DisplayName("publish falls through crud/folder/help to the publish module")
        void publishRoutes() {
            when(crudModule.canHandle("publish")).thenReturn(false);
            when(folderModule.canHandle("publish")).thenReturn(false);
            when(helpModule.canHandle("publish")).thenReturn(false);
            when(publishModule.canHandle("publish")).thenReturn(true);
            when(publishModule.execute(eq("publish"), any(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec("publish").success()).isTrue();
            verify(publishModule).execute(eq("publish"), any(), eq(TENANT), any());
        }

        @Test
        @DisplayName("unpublish routes to the publish module")
        void unpublishRoutes() {
            when(crudModule.canHandle("unpublish")).thenReturn(false);
            when(folderModule.canHandle("unpublish")).thenReturn(false);
            when(helpModule.canHandle("unpublish")).thenReturn(false);
            when(publishModule.canHandle("unpublish")).thenReturn(true);
            when(publishModule.execute(eq("unpublish"), any(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec("unpublish").success()).isTrue();
            verify(publishModule).execute(eq("unpublish"), any(), eq(TENANT), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("empty-Optional fallbacks (each module's distinct message + EXECUTION_FAILED)")
    class EmptyFallbacks {

        @Test
        @DisplayName("crud module empty → 'CRUD module failed for action: create'")
        void crudEmpty() {
            when(crudModule.canHandle("create")).thenReturn(true);
            when(crudModule.execute(eq("create"), any(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec("create");
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("CRUD module failed for action: create");
        }

        @Test
        @DisplayName("folder module empty → 'Folder module failed for action: create_folder'")
        void folderEmpty() {
            when(crudModule.canHandle("create_folder")).thenReturn(false);
            when(folderModule.canHandle("create_folder")).thenReturn(true);
            when(folderModule.execute(eq("create_folder"), any(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec("create_folder");
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Folder module failed for action: create_folder");
        }

        @Test
        @DisplayName("help module empty → 'Help module failed'")
        void helpEmpty() {
            when(crudModule.canHandle("help")).thenReturn(false);
            when(folderModule.canHandle("help")).thenReturn(false);
            when(helpModule.canHandle("help")).thenReturn(true);
            when(helpModule.execute(eq("help"), any(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec("help");
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Help module failed");
        }

        @Test
        @DisplayName("publish module empty → 'Publish module failed for action: publish'")
        void publishEmpty() {
            when(crudModule.canHandle("publish")).thenReturn(false);
            when(folderModule.canHandle("publish")).thenReturn(false);
            when(helpModule.canHandle("publish")).thenReturn(false);
            when(publishModule.canHandle("publish")).thenReturn(true);
            when(publishModule.execute(eq("publish"), any(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec("publish");
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Publish module failed for action: publish");
        }
    }
}
