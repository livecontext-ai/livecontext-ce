package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.NoteCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sticky notes written by an agent: {@code add_node type='note'}, the {@code attachedTo} anchor
 * (a node id, written as a label), and the anchor following its node through rename, remove,
 * undo/redo, set_plan and get_plan.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow notes attached to a node")
class WorkflowNoteAnchorTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;
    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    private NoteCreator noteCreator;
    private WorkflowBuilderModifier modifier;
    private WorkflowBuilderPlanExporter exporter;

    @BeforeEach
    void setUp() {
        noteCreator = new NoteCreator(sessionStore);
        modifier = new WorkflowBuilderModifier(sessionStore);
        exporter = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher);
    }

    /** Manual trigger "Start" -> exit core "Check Seen". */
    private WorkflowBuilderSession session() {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("s").tenantId("t").workflowName("Notes")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        session.getTriggers().add(new LinkedHashMap<>(Map.of("id", "trigger:start", "label", "Start", "type", "manual")));
        session.getCores().add(new LinkedHashMap<>(Map.of("id", "core:check_seen", "label", "Check Seen", "type", "exit")));
        session.getEdges().add(new LinkedHashMap<>(Map.of("from", "trigger:start", "to", "core:check_seen")));
        return session;
    }

    private ToolExecutionResult addNote(WorkflowBuilderSession session, Map<String, Object> params) {
        return noteCreator.executeAddNote(session, new LinkedHashMap<>(Map.of("params", new LinkedHashMap<>(params))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    private static Map<String, Object> note(WorkflowBuilderSession session, String label) {
        return session.getNotes().stream().filter(n -> label.equals(n.get("label"))).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("add_node type='note'")
    class AddNote {

        @Test
        @DisplayName("an attached note stores its anchor as the node id and gets a label naming that node")
        void attachedNoteStoresNodeIdAndGeneratedLabel() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = addNote(session, Map.of("text", "Why we dedup", "attached_to", "Check Seen"));

            assertThat(result.success()).isTrue();
            assertThat(data(result)).containsEntry("node_id", "note:about_check_seen");
            Map<String, Object> stored = note(session, "About Check Seen");
            assertThat(stored).containsEntry("attachedTo", "core:check_seen")
                    .containsEntry("text", "Why we dedup")
                    .containsEntry("id", "note:about_check_seen");
            assertThat(session.findOrphanNodes()).as("a note is never an orphan").isEmpty();
        }

        @Test
        @DisplayName("free notes are numbered, and a second note on the same node gets a distinct label")
        void generatedLabelsNeverCollide() {
            WorkflowBuilderSession session = session();

            addNote(session, Map.of("text", "a"));
            addNote(session, Map.of("text", "b"));
            addNote(session, Map.of("text", "c", "attached_to", "Check Seen"));
            addNote(session, Map.of("text", "d", "attachedTo", "core:check_seen"));

            assertThat(session.getNotes()).extracting(n -> n.get("label"))
                    .containsExactly("Note 1", "Note 2", "About Check Seen", "About Check Seen 2");
            assertThat(session.getNotes().get(0)).doesNotContainKey("attachedTo");
        }

        @Test
        @DisplayName("'content' is accepted for the text, so a note is never imported empty")
        void contentAliasFillsText() {
            WorkflowBuilderSession session = session();

            addNote(session, Map.of("content", "Explained"));

            assertThat(session.getNotes().get(0)).containsEntry("text", "Explained");
        }

        @Test
        @DisplayName("a note without text is refused and nothing is added")
        void missingTextRefused() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = addNote(session, Map.of("attached_to", "Check Seen"));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(session.getNotes()).isEmpty();
        }

        @Test
        @DisplayName("an anchor naming no node is refused and lists the nodes it can name")
        void unknownAnchorRefused() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = addNote(session, Map.of("text", "x", "attached_to", "Nope"));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            assertThat(result.error()).contains("core:check_seen").contains("trigger:start");
            assertThat(session.getNotes()).isEmpty();
        }

        @Test
        @DisplayName("a note cannot be attached to another note")
        void anchorOnNoteRefused() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "first"));

            ToolExecutionResult result = addNote(session, Map.of("text", "x", "attached_to", "Note 1"));

            assertThat(result.success()).isFalse();
            assertThat(session.getNotes()).hasSize(1);
        }

        @Test
        @DisplayName("undo of add_note removes the note")
        void undoRemovesNote() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x", "attached_to", "Check Seen"));

            assertThat(modifier.executeUndo(session).success()).isTrue();

            assertThat(session.getNotes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the anchor follows its node")
    class FollowsNode {

        @Test
        @DisplayName("renaming the node rewrites the anchor to the new id")
        void renameRewritesAnchor() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x", "attached_to", "Check Seen"));

            ToolExecutionResult result = modifier.executeModifyNode(session,
                    new LinkedHashMap<>(Map.of("node", "Check Seen", "params", new LinkedHashMap<>(Map.of("label", "Already Seen")))));

            assertThat(result.success()).isTrue();
            assertThat(note(session, "About Check Seen")).containsEntry("attachedTo", "core:already_seen");
        }

        @Test
        @DisplayName("removing the node removes its notes only; undo restores them; redo removes them again")
        void removeCascadesAndUndoRestores() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "free"));
            addNote(session, Map.of("text", "attached", "attached_to", "Check Seen"));

            ToolExecutionResult removed = modifier.executeRemove(session, new LinkedHashMap<>(Map.of("node", "Check Seen")));

            assertThat(removed.success()).isTrue();
            assertThat(data(removed).get("notes_removed")).isEqualTo(List.of("About Check Seen"));
            assertThat(session.getNotes()).extracting(n -> n.get("label")).containsExactly("Note 1");

            modifier.executeUndo(session);
            assertThat(session.getNotes()).extracting(n -> n.get("label")).containsExactlyInAnyOrder("Note 1", "About Check Seen");
            assertThat(note(session, "About Check Seen")).containsEntry("attachedTo", "core:check_seen");

            modifier.executeRedo(session);
            assertThat(session.getNotes()).extracting(n -> n.get("label")).containsExactly("Note 1");
        }

        @Test
        @DisplayName("removing a note never cascades to other notes")
        void removingANoteKeepsOthers() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "a", "attached_to", "Check Seen"));
            addNote(session, Map.of("text", "b"));

            modifier.executeRemove(session, new LinkedHashMap<>(Map.of("node", "Note 1")));

            assertThat(session.getNotes()).extracting(n -> n.get("label")).containsExactly("About Check Seen");
        }
    }

    @Nested
    @DisplayName("modify a note's anchor")
    class ModifyAnchor {

        @Test
        @DisplayName("attachedTo written as a label is stored as the node id")
        void labelStoredAsId() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x"));

            ToolExecutionResult result = modifier.executeModifyNode(session,
                    new LinkedHashMap<>(Map.of("node", "Note 1", "params", new LinkedHashMap<>(Map.of("attached_to", "Start")))));

            assertThat(result.success()).isTrue();
            assertThat(note(session, "Note 1")).containsEntry("attachedTo", "trigger:start");
        }

        @Test
        @DisplayName("attachedTo null detaches the note")
        void nullDetaches() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x", "attached_to", "Check Seen"));
            Map<String, Object> params = new HashMap<>();
            params.put("attachedTo", null);

            modifier.executeModifyNode(session, new LinkedHashMap<>(Map.of("node", "About Check Seen", "params", params)));

            assertThat(note(session, "About Check Seen")).doesNotContainKey("attachedTo");
        }

        @Test
        @DisplayName("an unknown anchor is refused and the note keeps its anchor")
        void unknownRefused() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x", "attached_to", "Check Seen"));

            ToolExecutionResult result = modifier.executeModifyNode(session,
                    new LinkedHashMap<>(Map.of("node", "About Check Seen", "params", new LinkedHashMap<>(Map.of("attachedTo", "Ghost")))));

            assertThat(result.success()).isFalse();
            assertThat(note(session, "About Check Seen")).containsEntry("attachedTo", "core:check_seen");
        }
    }

    @Nested
    @DisplayName("set_plan / get_plan")
    class PlanRoundTrip {

        private Map<String, Object> plan(List<Map<String, Object>> notes) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Start", "type", "manual")))));
            plan.put("cores", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Check Seen", "type", "exit")))));
            plan.put("edges", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("from", "Start", "to", "Check Seen")))));
            plan.put("notes", new ArrayList<>(notes));
            return plan;
        }

        private ToolExecutionResult setPlan(WorkflowBuilderSession session, Map<String, Object> plan) {
            return exporter.executeSetPlan(session, new LinkedHashMap<>(Map.of("plan", plan)));
        }

        @Test
        @DisplayName("an anchor label imports as the node id, content becomes text, and a missing id is generated")
        void importCanonicalizesNote() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = setPlan(session, plan(List.of(
                    new LinkedHashMap<>(Map.of("label", "Why", "content", "Because", "attachedTo", "Check Seen")))));

            assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
            Map<String, Object> stored = note(session, "Why");
            assertThat(stored).containsEntry("attachedTo", "core:check_seen")
                    .containsEntry("text", "Because")
                    .containsEntry("id", "note:why")
                    .doesNotContainKey("content");
        }

        @Test
        @DisplayName("get_plan exports the anchor as a label, which set_plan accepts back unchanged")
        void exportIsLabelAndRoundTrips() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x", "attached_to", "Check Seen"));

            @SuppressWarnings("unchecked")
            Map<String, Object> exported = (Map<String, Object>) data(exporter.executeGetPlan(session)).get("plan");
            @SuppressWarnings("unchecked")
            Map<String, Object> exportedNote = ((List<Map<String, Object>>) exported.get("notes")).get(0);
            assertThat(exportedNote).containsEntry("attachedTo", "Check Seen");
            assertThat(session.getNotes().get(0)).as("export never mutates the session")
                    .containsEntry("attachedTo", "core:check_seen");

            assertThat(setPlan(session, exported).success()).isTrue();
            assertThat(session.getNotes().get(0)).containsEntry("attachedTo", "core:check_seen");
        }

        @Test
        @DisplayName("an anchor naming no node fails set_plan with the note named")
        void unknownAnchorFails() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = setPlan(session, plan(List.of(
                    new LinkedHashMap<>(Map.of("label", "Why", "text", "x", "attachedTo", "Ghost")))));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Note 'Why'").contains("unknown node 'Ghost'");
        }

        @Test
        @DisplayName("an anchor naming another note fails set_plan")
        void anchorOnNoteFails() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = setPlan(session, plan(List.of(
                    new LinkedHashMap<>(Map.of("label", "Overview", "text", "x")),
                    new LinkedHashMap<>(Map.of("label", "Why", "text", "y", "attachedTo", "Overview")))));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("is a note");
        }
    }

    @Nested
    @DisplayName("audit round 1 regressions")
    class AuditRegressions {

        private Map<String, Object> plan(List<Map<String, Object>> notes) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Start", "type", "manual")))));
            plan.put("cores", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Check Seen", "type", "exit")))));
            plan.put("edges", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("from", "Start", "to", "Check Seen")))));
            plan.put("notes", new ArrayList<>(notes));
            return plan;
        }

        private ToolExecutionResult setPlan(WorkflowBuilderSession session, Map<String, Object> plan) {
            return exporter.executeSetPlan(session, new LinkedHashMap<>(Map.of("plan", plan)));
        }

        @Test
        @DisplayName("set_plan accepts a note labelled like the node it explains, and anchors it to that node")
        void noteSharingItsNodeLabelIsAccepted() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = setPlan(session, plan(List.of(
                    new LinkedHashMap<>(Map.of("label", "Check Seen", "text", "x", "attachedTo", "Check Seen")))));

            assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
            assertThat(session.getNotes().get(0)).containsEntry("attachedTo", "core:check_seen");
            assertThat(String.valueOf(data(result).get("warnings"))).contains("has the label of a node");
        }

        @Test
        @DisplayName("set_plan refuses an anchor that names a port instead of a node")
        void portAnchorRefused() {
            ToolExecutionResult result = setPlan(session(), plan(List.of(
                    new LinkedHashMap<>(Map.of("label", "Why", "text", "x", "attachedTo", "core:check_seen:if")))));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("names a port");
        }

        @Test
        @DisplayName("set_plan gives two id-less notes sharing a label two distinct ids")
        void generatedNoteIdsAreUnique() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = setPlan(session, plan(List.of(
                    new LinkedHashMap<>(Map.of("label", "Note", "text", "a")),
                    new LinkedHashMap<>(Map.of("label", "Note", "text", "b")),
                    new LinkedHashMap<>(Map.of("id", "note:why", "label", "Late", "text", "c")),
                    new LinkedHashMap<>(Map.of("label", "Why", "text", "d")))));

            assertThat(session.getNotes()).extracting(n -> n.get("id"))
                    .as("a generated id never takes one a later note states explicitly")
                    .containsExactly("note:note", "note:note_2", "note:why", "note:why_2");
            assertThat(String.valueOf(data(result).get("warnings"))).contains("Several notes are labelled 'Note'");
        }

        @Test
        @DisplayName("set_plan takes attached_to too, and a wrong-prefix anchor is recovered to the real node")
        void wrongPrefixAnchorNeverDroppedSilently() {
            WorkflowBuilderSession session = session();

            ToolExecutionResult result = setPlan(session, plan(List.of(
                    new LinkedHashMap<>(Map.of("label", "Why", "text", "x", "attached_to", "mcp:check_seen")))));

            assertThat(result.success()).isTrue();
            assertThat(session.getNotes().get(0)).containsEntry("attachedTo", "core:check_seen");
            assertThat(session.getNotes().get(0)).doesNotContainKey("attached_to");
        }

        @Test
        @DisplayName("connect refuses a note at either end")
        void connectRefusesNotes() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x"));
            WorkflowBuilderConnectionManager connections = new WorkflowBuilderConnectionManager(sessionStore);

            ToolExecutionResult from = connections.executeConnect(session, new LinkedHashMap<>(Map.of("from", "Note 1", "to", "Check Seen")));
            ToolExecutionResult to = connections.executeConnect(session, new LinkedHashMap<>(Map.of("from", "Start", "to", "Note 1")));

            assertThat(from.success()).isFalse();
            assertThat(to.success()).isFalse();
            assertThat(from.error()).contains("A note cannot be connected");
            assertThat(session.getEdges()).hasSize(1);
        }

        @Test
        @DisplayName("connect_after a note is refused: nothing can follow a note")
        void connectAfterNoteRefused() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x"));
            var merges = new com.apimarketplace.orchestrator.tools.workflow.builder.creators.ForkMergeNodeCreator(sessionStore);

            ToolExecutionResult result = merges.executeAddMerge(session,
                    new LinkedHashMap<>(Map.of("label", "Join", "connect_after", "Note 1")));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("is a note");
        }

        @Test
        @DisplayName("a note never becomes the last added node the builder guidance chains from")
        void noteIsNotLastAdded() {
            WorkflowBuilderSession session = session();
            session.setLastAddedNodeId("core:check_seen");

            addNote(session, Map.of("text", "x", "attached_to", "Check Seen"));

            assertThat(session.getLastAddedNodeId()).isEqualTo("core:check_seen");
        }

        @Test
        @DisplayName("a palette color name sets the three note colors; an unknown one is refused")
        void paletteColors() {
            WorkflowBuilderSession session = session();

            addNote(session, Map.of("text", "x", "color", "Blue"));
            ToolExecutionResult bad = addNote(session, Map.of("text", "y", "color", "#123456"));

            assertThat(session.getNotes().get(0)).containsEntry("color", "#dbeafe")
                    .containsEntry("borderColor", "#3b82f6").containsEntry("textColor", "#1e40af");
            assertThat(bad.success()).isFalse();
            assertThat(bad.errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
            assertThat(session.getNotes()).hasSize(1);
        }

        @Test
        @DisplayName("undo then redo of a detach leaves no attachedTo key, not a null one")
        void redoOfDetachRemovesKey() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x", "attached_to", "Check Seen"));
            Map<String, Object> params = new HashMap<>();
            params.put("attachedTo", null);
            modifier.executeModifyNode(session, new LinkedHashMap<>(Map.of("node", "About Check Seen", "params", params)));

            modifier.executeUndo(session);
            assertThat(note(session, "About Check Seen")).containsEntry("attachedTo", "core:check_seen");
            modifier.executeRedo(session);

            assertThat(note(session, "About Check Seen")).doesNotContainKey("attachedTo");
        }

        @Test
        @DisplayName("undo of a re-anchor restores the previous anchor")
        void undoOfReanchor() {
            WorkflowBuilderSession session = session();
            addNote(session, Map.of("text", "x", "attached_to", "Check Seen"));
            modifier.executeModifyNode(session, new LinkedHashMap<>(Map.of("node", "About Check Seen",
                    "params", new LinkedHashMap<>(Map.of("attachedTo", "Start")))));

            modifier.executeUndo(session);

            assertThat(note(session, "About Check Seen")).containsEntry("attachedTo", "core:check_seen");
        }

        @Test
        @DisplayName("an anchor that passes validation but names a note is reported, and the note imported free")
        void unresolvedAnchorIsReported() {
            WorkflowBuilderSession session = session();

            // 'note:check_seen' strips to the node label check_seen, so validation lets it through;
            // the session resolves it to the note itself, which is no anchor.
            ToolExecutionResult result = setPlan(session, plan(List.of(
                    new LinkedHashMap<>(Map.of("label", "Check Seen", "text", "x", "attachedTo", "note:check_seen")))));

            assertThat(result.success()).isTrue();
            assertThat(session.getNotes().get(0)).doesNotContainKey("attachedTo");
            assertThat(String.valueOf(data(result).get("warnings"))).contains("did not resolve to a node");
        }
    }

    @Nested
    @DisplayName("what the agent is told about notes")
    class AgentGuidance {

        @Test
        @DisplayName("a workflow ready to finish reminds the agent to explain what the nodes do not say, with the exact call")
        void readyPhaseSuggestsNotes() {
            WorkflowBuilderSession session = session();
            assertThat(WorkflowBuilderPrompts.detectPhase(session)).isEqualTo(WorkflowBuilderPrompts.Phase.READY);

            Object hint = WorkflowBuilderPrompts.getContextualHelp(session).get("BEFORE_FINISH");

            assertThat(String.valueOf(hint)).contains("type='note'").contains("attached_to").contains("topics=['notes']");
        }

        @Test
        @DisplayName("the node types the builder documents include the note")
        @SuppressWarnings("unchecked")
        void stepTypesListTheNote() throws Exception {
            WorkflowBuilderHelpModule module = new WorkflowBuilderHelpModule(
                    org.mockito.Mockito.mock(com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider.class));
            java.lang.reflect.Method docs = WorkflowBuilderHelpModule.class.getDeclaredMethod("buildNodeCreationDocs");
            docs.setAccessible(true);

            Map<String, Object> stepTypes = (Map<String, Object>) ((Map<String, Object>) docs.invoke(module)).get("step_types");

            assertThat(String.valueOf(stepTypes.get("note"))).contains("attached_to").contains("topics=['notes']");
        }

        @Test
        @DisplayName("the notes help topic gives the call, when to annotate and when not to")
        void notesHelpTopic() {
            Map<String, Object> help = com.apimarketplace.orchestrator.tools.workflow.help.ConceptsHelpProvider.getNotesHelp();

            assertThat(String.valueOf(help.get("add"))).contains("type='note'").contains("attached_to");
            assertThat(help).containsKeys("when_to_add", "when_not_to", "attached_vs_free", "in_set_plan");
            assertThat(String.valueOf(help.get("placement"))).as("the agent must leave placement to the canvas")
                    .contains("Do not invent a position").contains("top to bottom").contains("switches direction")
                    .contains("keep the position each note came with");
        }
    }

    @Nested
    @DisplayName("notes an agent writes alternate through the palette")
    class ColorRotation {

        @Test
        @DisplayName("add_node without a colour takes the palette colour after the previous note's")
        void addNodeAlternates() {
            WorkflowBuilderSession session = session();

            addNote(session, Map.of("text", "a"));
            addNote(session, Map.of("text", "b"));
            addNote(session, Map.of("text", "c", "attached_to", "Check Seen"));

            assertThat(session.getNotes()).extracting(n -> n.get("color"))
                    .containsExactly("#fef3c7", "#dbeafe", "#d1fae5");
            assertThat(session.getNotes().get(1)).containsEntry("borderColor", "#3b82f6").containsEntry("textColor", "#1e40af");
        }

        @Test
        @DisplayName("an asked-for colour is kept, and the next note continues after it")
        void askedColourIsKeptAndContinued() {
            WorkflowBuilderSession session = session();

            addNote(session, Map.of("text", "a", "color", "pink"));
            addNote(session, Map.of("text", "b"));

            assertThat(session.getNotes()).extracting(n -> n.get("color")).containsExactly("#fce7f3", "#e9d5ff");
        }

        @Test
        @DisplayName("the rotation wraps after the last palette colour")
        void rotationWraps() {
            WorkflowBuilderSession session = session();

            addNote(session, Map.of("text", "a", "color", "orange"));
            addNote(session, Map.of("text", "b"));

            assertThat(session.getNotes().get(1)).containsEntry("color", "#fef3c7");
        }

        @Test
        @DisplayName("set_plan colours the notes that carry none, in turn, and keeps the ones that carry one")
        void setPlanAlternates() {
            WorkflowBuilderSession session = session();
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Start", "type", "manual")))));
            plan.put("cores", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Check Seen", "type", "exit")))));
            plan.put("edges", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("from", "Start", "to", "Check Seen")))));
            plan.put("notes", new ArrayList<>(List.of(
                    new LinkedHashMap<>(Map.of("label", "A", "text", "a")),
                    new LinkedHashMap<>(Map.of("label", "B", "text", "b")),
                    new LinkedHashMap<>(Map.of("label", "C", "text", "c", "color", "#fef3c7", "borderColor", "#fbbf24")),
                    new LinkedHashMap<>(Map.of("label", "D", "text", "d")))));

            ToolExecutionResult result = exporter.executeSetPlan(session, new LinkedHashMap<>(Map.of("plan", plan)));

            assertThat(result.success()).isTrue();
            assertThat(session.getNotes()).extracting(n -> n.get("color"))
                    .containsExactly("#fef3c7", "#dbeafe", "#fef3c7", "#dbeafe");
            assertThat(session.getNotes().get(2)).containsEntry("borderColor", "#fbbf24");
        }

        private ToolExecutionResult setPlanWithNotes(WorkflowBuilderSession session, List<Map<String, Object>> notes) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("triggers", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Start", "type", "manual")))));
            plan.put("cores", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("label", "Check Seen", "type", "exit")))));
            plan.put("edges", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("from", "Start", "to", "Check Seen")))));
            plan.put("notes", new ArrayList<>(notes));
            return exporter.executeSetPlan(session, new LinkedHashMap<>(Map.of("plan", plan)));
        }

        @Test
        @DisplayName("set_plan turns a palette name into its full swatch, and the next note continues after it")
        void setPlanResolvesPaletteNames() {
            WorkflowBuilderSession session = session();

            setPlanWithNotes(session, List.of(
                    new LinkedHashMap<>(Map.of("label", "A", "text", "a", "color", "Pink")),
                    new LinkedHashMap<>(Map.of("label", "B", "text", "b"))));

            assertThat(session.getNotes().get(0)).containsEntry("color", "#fce7f3")
                    .containsEntry("borderColor", "#ec4899").containsEntry("textColor", "#831843");
            assertThat(session.getNotes().get(1)).containsEntry("color", "#e9d5ff");
        }

        @Test
        @DisplayName("a palette hex in another case or with spaces is recognised and completed")
        void paletteHexMatchedLoosely() {
            WorkflowBuilderSession session = session();

            setPlanWithNotes(session, List.of(
                    new LinkedHashMap<>(Map.of("label", "A", "text", "a", "color", " #DBEAFE ")),
                    new LinkedHashMap<>(Map.of("label", "B", "text", "b"))));

            assertThat(session.getNotes().get(0)).containsEntry("color", "#dbeafe").containsEntry("borderColor", "#3b82f6");
            assertThat(session.getNotes().get(1)).containsEntry("color", "#d1fae5");
        }

        @Test
        @DisplayName("a previous note with a colour outside the palette is skipped to find where the rotation stands")
        void rotationSkipsNonPaletteColours() {
            WorkflowBuilderSession session = session();

            setPlanWithNotes(session, List.of(
                    new LinkedHashMap<>(Map.of("label", "A", "text", "a", "color", "#dbeafe")),
                    new LinkedHashMap<>(Map.of("label", "B", "text", "b", "color", "#123456", "borderColor", "#000000")),
                    new LinkedHashMap<>(Map.of("label", "C", "text", "c"))));

            assertThat(session.getNotes().get(1)).containsEntry("color", "#123456").containsEntry("borderColor", "#000000");
            assertThat(session.getNotes().get(2)).as("after blue, past the custom colour").containsEntry("color", "#d1fae5");
        }

        @Test
        @DisplayName("a note with only a border or text colour is left as written")
        void partialColouringUntouched() {
            WorkflowBuilderSession session = session();

            setPlanWithNotes(session, List.of(new LinkedHashMap<>(Map.of("label", "A", "text", "a", "borderColor", "#ec4899"))));

            assertThat(session.getNotes().get(0)).doesNotContainKey("color").containsEntry("borderColor", "#ec4899");
        }

        @Test
        @DisplayName("canvas-saved notes come back from set_plan unchanged, and get_plan then set_plan is stable")
        void canvasNotesAndRoundTripUnchanged() {
            WorkflowBuilderSession session = session();
            Map<String, Object> canvasNote = new LinkedHashMap<>(Map.of(
                    "id", "note-1", "label", "A", "text", "a", "color", "#fef3c7", "borderColor", "#fbbf24",
                    "textColor", "#92400e", "width", 250, "height", 100, "position", Map.of("x", 1, "y", 2)));
            Map<String, Object> customNote = new LinkedHashMap<>(Map.of(
                    "id", "note-2", "label", "B", "text", "b", "color", "#fef3c7", "borderColor", "#fbbf24",
                    "textColor", "#92400e"));
            setPlanWithNotes(session, List.of(new LinkedHashMap<>(canvasNote), new LinkedHashMap<>(customNote)));

            assertThat(session.getNotes().get(0)).isEqualTo(canvasNote);
            assertThat(session.getNotes().get(1)).as("two yellow notes in a row stay yellow").isEqualTo(customNote);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> exported = (List<Map<String, Object>>) ((Map<String, Object>)
                    data(exporter.executeGetPlan(session)).get("plan")).get("notes");
            List<Map<String, Object>> before = session.getNotes().stream().map(n -> (Map<String, Object>) new LinkedHashMap<>(n)).toList();
            setPlanWithNotes(session, exported.stream().map(n -> (Map<String, Object>) new LinkedHashMap<>(n)).toList());
            assertThat(session.getNotes()).isEqualTo(before);
        }

        @Test
        @DisplayName("add_node tells the agent which colour the note got, and the help says notes alternate")
        void colourIsReportedAndDocumented() {
            ToolExecutionResult result = addNote(session(), Map.of("text", "a"));

            assertThat(data(result)).containsEntry("color", "#fef3c7");
            Map<String, Object> help = com.apimarketplace.orchestrator.tools.workflow.help.ConceptsHelpProvider.getNotesHelp();
            assertThat(String.valueOf(help.get("add"))).contains("alternate");
            assertThat(String.valueOf(help.get("in_set_plan"))).contains("alternate");
        }
    }
}
