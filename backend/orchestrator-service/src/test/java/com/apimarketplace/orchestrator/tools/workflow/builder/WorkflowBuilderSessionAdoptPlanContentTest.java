package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowBuilderSession#adoptPlanContentFrom} copies the workflow content field by
 * field when a session is rebuilt from a plan saved on the canvas. A content field added to
 * the session later and forgotten there would keep the session's OLD value through the
 * rebuild, and the next auto-save would write it back over the canvas save, the very bug
 * the rebuild exists to prevent. Every field must therefore be classified here: identity
 * (kept) or content (copied); an unclassified field fails the build.
 */
@DisplayName("WorkflowBuilderSession.adoptPlanContentFrom - every field is either kept or copied")
class WorkflowBuilderSessionAdoptPlanContentTest {

    /** Kept through a rebuild: who and what the session is, and state reset on purpose. */
    private static final Set<String> NOT_COPIED = Set.of(
            "sessionId", "tenantId", "orgId", "conversationId", "createdAt", "updatedAt",
            "loadedWorkflowId", "loadedPlanSnapshot", "baselineTracksWrites",
            // Reset by the rebuild (asserted separately), never taken from the source.
            "actionHistory", "redoStack", "lastAddedNodeId",
            // Lazy caches over the content lists, rebuilt on next use.
            "nodeFinder", "edgeManager");

    private static List<Field> instanceFields() {
        return Arrays.stream(WorkflowBuilderSession.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .peek(f -> f.setAccessible(true))
                .toList();
    }

    /** A value of the field's type that no freshly created session holds. */
    private static Object distinctValue(Field field) {
        Class<?> type = field.getType();
        if (type == String.class) return "copied-" + field.getName();
        if (type == boolean.class) return true;
        if (List.class.isAssignableFrom(type)) return new ArrayList<>(List.of(Map.of("marker", field.getName())));
        if (Map.class.isAssignableFrom(type)) return new LinkedHashMap<>(Map.of("marker", field.getName()));
        throw new AssertionError("No test value for field type " + type + " (" + field.getName()
                + "): extend distinctValue, then classify the field");
    }

    @Test
    @DisplayName("every content field of the source ends up on the rebuilt session")
    void copiesEveryContentField() throws Exception {
        WorkflowBuilderSession source = WorkflowBuilderSession.create("t", "conv", "n", "d");
        WorkflowBuilderSession target = WorkflowBuilderSession.create("t", "conv", "old", "old");
        Map<String, Object> expected = new HashMap<>();
        for (Field field : instanceFields()) {
            if (NOT_COPIED.contains(field.getName())) continue;
            Object value = distinctValue(field);
            field.set(source, value);
            expected.put(field.getName(), value);
        }

        target.adoptPlanContentFrom(source);

        for (Field field : instanceFields()) {
            if (NOT_COPIED.contains(field.getName())) continue;
            assertThat(field.get(target))
                    .as("content field '%s' must be copied by adoptPlanContentFrom", field.getName())
                    .isEqualTo(expected.get(field.getName()));
        }
    }

    @Test
    @DisplayName("every field that is not copied is classified on purpose, so a new field cannot slip through")
    void everyNotCopiedFieldExists() {
        Set<String> fieldNames = instanceFields().stream().map(Field::getName).collect(Collectors.toSet());
        assertThat(fieldNames).containsAll(NOT_COPIED);
    }

    @Test
    @DisplayName("the rebuild keeps identity and resets undo/redo and the last-added marker")
    void keepsIdentityAndResetsHistory() {
        WorkflowBuilderSession source = WorkflowBuilderSession.create("t", "conv-2", "n", "d");
        WorkflowBuilderSession target = WorkflowBuilderSession.create("t", "conv", "old", "old");
        target.setLoadedWorkflowId("wf-1");
        target.setLastAddedNodeId("core:x");
        target.getActionHistory().add(new WorkflowBuilderSession.SessionAction());
        target.getRedoStack().add(new WorkflowBuilderSession.SessionAction());
        String sessionId = target.getSessionId();

        target.adoptPlanContentFrom(source);

        assertThat(target.getSessionId()).isEqualTo(sessionId);
        assertThat(target.getConversationId()).isEqualTo("conv");
        assertThat(target.getLoadedWorkflowId()).isEqualTo("wf-1");
        assertThat(target.getActionHistory()).isEmpty();
        assertThat(target.getRedoStack()).isEmpty();
        assertThat(target.getLastAddedNodeId()).isNull();
    }
}
