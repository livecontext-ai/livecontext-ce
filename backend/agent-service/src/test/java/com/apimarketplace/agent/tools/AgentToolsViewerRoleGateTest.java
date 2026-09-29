package com.apimarketplace.agent.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.agent.AgentConversationModule;
import com.apimarketplace.agent.tools.agent.AgentDelegationModule;
import com.apimarketplace.agent.tools.agent.AgentPublishModule;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.agent.tools.skill.SkillCrudModule;
import com.apimarketplace.agent.tools.skill.SkillFolderModule;
import com.apimarketplace.agent.tools.skill.SkillPublishModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Regression for the agent-service tool path VIEWER gap: the skill, skill-folder,
 * skill-publish, delegation (tasks), conversation (share goes through the internal
 * shared-links endpoint, which bypasses SharedLinkController) and agent-publish modules
 * gated the per-AGENT access mode only, so a workspace VIEWER driving an agent could write
 * through them. Each module now refuses every non-READ action of a VIEWER before touching
 * any collaborator.
 */
@DisplayName("agent-service tool modules refuse writes from the workspace VIEWER role")
class AgentToolsViewerRoleGateTest {

    private static final ToolExecutionContext VIEWER =
            new ToolExecutionContext("7", Map.of(), Map.of(), Set.of(), null, null, "org-1", "VIEWER");

    static Stream<Arguments> writes() {
        return Stream.of(
                Arguments.of(SkillCrudModule.class, "create"),
                Arguments.of(SkillCrudModule.class, "delete"),
                Arguments.of(SkillFolderModule.class, "create_folder"),
                Arguments.of(SkillPublishModule.class, "publish"),
                Arguments.of(AgentDelegationModule.class, "assign"),
                Arguments.of(AgentDelegationModule.class, "task_delete"),
                Arguments.of(AgentConversationModule.class, "share"),
                Arguments.of(AgentPublishModule.class, "publish"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("writes")
    @DisplayName("a VIEWER write is PERMISSION_DENIED and reaches no collaborator")
    void viewerWriteDenied(Class<? extends ToolModule> type, String action) throws Exception {
        List<Object> deps = new ArrayList<>();
        ToolModule module = build(type, deps);

        Optional<ToolExecutionResult> result = module.execute(action, Map.of(), "7", VIEWER);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.get().error()).contains("VIEWER").contains("'" + action + "'");
        if (!deps.isEmpty()) {
            verifyNoInteractions(deps.toArray());
        }
    }

    /** Builds the module through its widest public constructor, every collaborator a mock. */
    static <T> T build(Class<T> type, List<Object> depsOut) throws Exception {
        Constructor<?> ctor = Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == boolean.class) {
                args[i] = false;
            } else if (types[i].isPrimitive()) {
                args[i] = 0;
            } else if (types[i] == String.class) {
                args[i] = "";
            } else {
                args[i] = mock(types[i]);
                depsOut.add(args[i]);
            }
        }
        return type.cast(ctor.newInstance(args));
    }
}
