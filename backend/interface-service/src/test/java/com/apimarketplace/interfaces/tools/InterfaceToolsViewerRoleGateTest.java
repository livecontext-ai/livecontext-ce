package com.apimarketplace.interfaces.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Regression for the interface tool VIEWER gap: create / update / patch / delete and
 * publish / unpublish gated the per-agent access mode only, so a workspace VIEWER driving
 * an agent could write interfaces the REST surface refuses them.
 */
@DisplayName("interface tool modules refuse writes from the workspace VIEWER role")
class InterfaceToolsViewerRoleGateTest {

    private static final ToolExecutionContext VIEWER =
            new ToolExecutionContext("7", Map.of(), Map.of(), Set.of(), null, null, "org-1", "VIEWER");

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "com.apimarketplace.interfaces.tools.InterfaceCrudModule, create",
            "com.apimarketplace.interfaces.tools.InterfaceCrudModule, update",
            "com.apimarketplace.interfaces.tools.InterfaceCrudModule, patch",
            "com.apimarketplace.interfaces.tools.InterfaceCrudModule, delete",
            "com.apimarketplace.interfaces.tools.InterfacePublishModule, publish",
            "com.apimarketplace.interfaces.tools.InterfacePublishModule, unpublish"})
    @DisplayName("a VIEWER write is PERMISSION_DENIED and reaches no collaborator")
    void viewerWriteDenied(String className, String action) throws Exception {
        List<Object> deps = new ArrayList<>();
        ToolModule module = build(Class.forName(className).asSubclass(ToolModule.class), deps);

        Optional<ToolExecutionResult> result = module.execute(action, Map.of(), "7", VIEWER);

        assertThat(result).isPresent();
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
