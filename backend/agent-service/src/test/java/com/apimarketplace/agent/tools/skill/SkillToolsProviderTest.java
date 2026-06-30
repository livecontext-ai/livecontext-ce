package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillToolsProvider Tests")
class SkillToolsProviderTest {

    @Mock private SkillCrudModule crudModule;
    @Mock private SkillHelpModule helpModule;
    @Mock private SkillFolderModule folderModule;
    @Mock private SkillPublishModule publishModule;

    private SkillToolsProvider provider;

    private static final String TENANT = "tenant-123";

    @BeforeEach
    void setUp() {
        provider = new SkillToolsProvider(crudModule, helpModule, folderModule, publishModule);
    }

    private ToolExecutionContext ctx(String tenantId) {
        return new ToolExecutionContext(tenantId, null, Map.of(), null, null, null, null, null);
    }

    @Nested
    @DisplayName("Tool Definitions")
    class ToolDefinitions {

        @Test
        @DisplayName("Returns single 'skill' tool")
        void returnsSingleSkillTool() {
            List<AgentToolDefinition> tools = provider.getTools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("skill");
        }

        @Test
        @DisplayName("Category is AGENT")
        void categoryIsAgent() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.AGENT);
        }

        @Test
        @DisplayName("'action' is only required parameter")
        void actionIsOnlyRequired() {
            assertThat(provider.getTools().get(0).requiredParameters()).containsExactly("action");
        }

        @Test
        @DisplayName("Has all expected parameters")
        void hasAllParams() {
            List<String> names = provider.getTools().get(0).parameters().stream().map(p -> p.name()).toList();
            assertThat(names).contains("action", "skill_id", "name", "description",
                "instructions", "folder_id", "parent_id", "agent_id", "skill_ids");
        }
    }

    @Nested
    @DisplayName("Execute Routing")
    class ExecuteRouting {

        @Test
        @DisplayName("CRUD actions route to crudModule")
        void crudActionsRoute() {
            for (String action : List.of("create", "get", "list", "update", "delete", "assign")) {
                when(crudModule.canHandle(action)).thenReturn(true);
                when(crudModule.execute(eq(action), any(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("ok", true))));

                ToolExecutionResult r = provider.execute("skill", Map.of("action", action), ctx(TENANT));
                assertThat(r.success()).as("Action '%s' should succeed", action).isTrue();
            }
        }

        @Test
        @DisplayName("Folder actions route to folderModule")
        void folderActionsRoute() {
            for (String action : List.of("create_folder", "list_folders", "rename_folder", "move_folder", "delete_folder")) {
                when(folderModule.canHandle(action)).thenReturn(true);
                when(folderModule.execute(eq(action), any(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("ok", true))));

                ToolExecutionResult r = provider.execute("skill", Map.of("action", action), ctx(TENANT));
                assertThat(r.success()).as("Folder action '%s' should succeed", action).isTrue();
            }
        }

        @Test
        @DisplayName("Help action routes to helpModule")
        void helpRoutes() {
            when(helpModule.canHandle("help")).thenReturn(true);
            when(helpModule.execute(eq("help"), any(), isNull(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("help", "docs"))));

            ToolExecutionResult r = provider.execute("skill", Map.of("action", "help"), ctx(null));
            assertThat(r.success()).isTrue();
        }

        @Test
        @DisplayName("Missing action fails")
        void missingActionFails() {
            ToolExecutionResult r = provider.execute("skill", Map.of(), ctx(TENANT));
            assertThat(r.success()).isFalse();
        }

        @Test
        @DisplayName("Invalid action fails")
        void invalidActionFails() {
            ToolExecutionResult r = provider.execute("skill", Map.of("action", "nope"), ctx(TENANT));
            assertThat(r.success()).isFalse();
        }

        @Test
        @DisplayName("Wrong tool name fails")
        void wrongToolFails() {
            ToolExecutionResult r = provider.execute("agent", Map.of("action", "list"), ctx(TENANT));
            assertThat(r.success()).isFalse();
        }
    }
}
