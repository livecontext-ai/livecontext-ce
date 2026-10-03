package com.apimarketplace.orchestrator.tools.workflow.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * What an AGENT gets when it loads a workflow saved before generate moved.
 *
 * <p>The node used to travel in the plan's {@code cores}. It now travels in
 * {@code agents} and the engine refuses to run one found among the cores, on
 * purpose and loudly. The builder cannot answer the same way, because the
 * builder is where the plan gets repaired, and here the failure to repair was
 * silent in a particular way worth spelling out:
 *
 * <ul>
 *   <li>{@code validate} walks the AI nodes, so a generate node parked in the
 *       cores was checked by nothing and the answer came back clean;</li>
 *   <li>{@code finish} then saved the plan with the node still in the cores;</li>
 *   <li>and the run died at the engine, on a workflow the tools had just
 *       pronounced valid.</li>
 * </ul>
 *
 * <p>The builder frontend already adopted it on import. This is the same
 * adoption for the tool path, so the two front doors give one answer.
 */
@DisplayName("a generate node stored as a core is adopted when a workflow is loaded")
class WorkflowBuilderLoaderGenerateCoreAdoptionTest {

    /** A stored workflow whose plan holds the node where every old one holds it. */
    private WorkflowEntity workflowWith(Map<String, Object>... extraCores) {
        Map<String, Object> generate = new LinkedHashMap<>();
        generate.put("id", "core:make_clip");
        generate.put("type", "generate");
        generate.put("label", "Make Clip");
        generate.put("params", Map.of(
                "model", "seedance-2.0-fast",
                "prompt", "a paper boat in a rain gutter",
                "credential_source", "user",
                "credential_id", 42));

        List<Map<String, Object>> cores = new ArrayList<>();
        cores.add(generate);
        cores.addAll(List.of(extraCores));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", cores);
        // The edges a real plan carries, naming the node by the key it had when
        // it was saved. Without them the fixture cannot see whether adoption
        // leaves the node wired to anything.
        plan.put("edges", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("from", "trigger:start", "to", "core:make_clip")),
                new LinkedHashMap<>(Map.of("from", "core:make_clip", "to", "core:use_clip")))));

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setName("Legacy clip");
        workflow.setPlan(plan);
        return workflow;
    }

    /**
     * The real load path, driven as {@code load} drives it.
     *
     * <p>Built without running the constructor: converting a stored plan into a
     * session touches none of the loader's dozen collaborators, and passing a
     * dozen nulls positionally would say the same thing and be unreadable.
     */
    private WorkflowBuilderSession load(WorkflowEntity workflow) throws Exception {
        WorkflowBuilderLoader loader =
                Mockito.mock(WorkflowBuilderLoader.class, Mockito.CALLS_REAL_METHODS);
        Method method = WorkflowBuilderLoader.class.getDeclaredMethod(
                "convertWorkflowToSession", WorkflowEntity.class, String.class, String.class);
        method.setAccessible(true);
        return (WorkflowBuilderSession) method.invoke(loader, workflow, "test-tenant", "conv-1");
    }

    private Map<String, Object> nodeLabelled(List<Map<String, Object>> nodes, String label) {
        return nodes.stream()
                .filter(n -> label.equals(n.get("label")))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("it is loaded as an AI node, so the checks that know about it can see it")
    void itIsLoadedAsAnAiNode() throws Exception {
        WorkflowBuilderSession session = load(workflowWith());

        assertThat(nodeLabelled(session.getMcps(), "Make Clip"))
                .as("left among the cores it is checked by nothing: validate answers clean and "
                        + "the run is then refused by the engine")
                .isNotNull();
        assertThat(nodeLabelled(session.getCores(), "Make Clip"))
                .as("and it must not be in both places, or the next save writes it twice")
                .isNull();
    }

    @Test
    @DisplayName("it carries the flags that make agent: resolve for it")
    void itCarriesTheAiFlags() throws Exception {
        WorkflowBuilderSession session = load(workflowWith());

        Map<String, Object> node = nodeLabelled(session.getMcps(), "Make Clip");
        assertThat(node).containsEntry("isAgent", true).containsEntry("isGenerate", true);
        assertThat(node.get("type")).isEqualTo("generate");
    }

    @Test
    @DisplayName("its configuration survives, since the next save is what rewrites the plan")
    void itsConfigurationSurvives() throws Exception {
        // Whatever the load drops here is gone from the file the next save
        // writes: the model, the prompt and the key that decides who is billed.
        WorkflowBuilderSession session = load(workflowWith());

        @SuppressWarnings("unchecked")
        Map<String, Object> params =
                (Map<String, Object>) nodeLabelled(session.getMcps(), "Make Clip").get("params");
        assertThat(params)
                .containsEntry("model", "seedance-2.0-fast")
                .containsEntry("prompt", "a paper boat in a rain gutter")
                .containsEntry("credential_source", "user")
                .containsEntry("credential_id", 42);
    }

    @Test
    @DisplayName("the agent is told to read its file as agent:, never as core:")
    void theReferenceUsesTheAgentPrefix() throws Exception {
        // The whole point of adopting it rather than leaving it: a reference
        // written from the old key resolves to an empty string, not an error.
        WorkflowBuilderSession session = load(workflowWith());

        WorkflowBuilderSession.NodeSchema schema = session.getNodeSchemas().get("agent:make_clip");
        assertThat(schema).as("a schema must be registered under the AI key").isNotNull();
        assertThat(schema.getReferenceSyntax())
                .containsEntry("file", "{{agent:make_clip.output.file}}");
        assertThat(session.getNodeSchemas()).doesNotContainKey("core:make_clip");
    }

    @Test
    @DisplayName("an ordinary core in the same plan is untouched, so the adoption is not a blanket one")
    void anOrdinaryCoreIsUntouched() throws Exception {
        Map<String, Object> wait = new LinkedHashMap<>();
        wait.put("id", "core:pause");
        wait.put("type", "wait");
        wait.put("label", "Pause");
        wait.put("params", Map.of("seconds", 3));

        WorkflowBuilderSession session = load(workflowWith(wait));

        assertThat(nodeLabelled(session.getCores(), "Pause"))
                .as("a guard that runs before every core is one line from moving nodes it has no "
                        + "business moving")
                .isNotNull();
        assertThat(nodeLabelled(session.getMcps(), "Pause")).isNull();
    }

    private String end(WorkflowBuilderSession session, int index, String which) {
        return String.valueOf(session.getEdges().get(index).get(which));
    }

    /**
     * The node moves and its wiring moves with it.
     *
     * <p>This is the half that makes the repair worth doing. Adopted without
     * its edges, the next save writes the node under {@code agents} and every
     * edge still naming {@code core:<label>}: the engine drops those edges, the
     * node is unreachable, and every node after it is skipped. The author gets
     * a workflow that looks repaired and does less than it did.
     */
    @Test
    @DisplayName("the edges that named the old key now name the new one, so the node is still reachable")
    void theEdgesFollowTheNode() throws Exception {
        WorkflowBuilderSession session = load(workflowWith());

        assertThat(end(session, 0, "to"))
                .as("the edge INTO the node must find it, or nothing ever reaches it")
                .isEqualTo("agent:make_clip");
        assertThat(end(session, 1, "from"))
                .as("and the edge OUT of it, or everything downstream is skipped")
                .isEqualTo("agent:make_clip");
    }

    @Test
    @DisplayName("a note attached to the node follows it, so it keeps its rename and remove cascade")
    @SuppressWarnings("unchecked")
    void theNotesFollowTheNode() throws Exception {
        WorkflowEntity workflow = workflowWith();
        workflow.getPlan().put("notes", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("id", "n1", "label", "Why", "text", "x", "attachedTo", "core:make_clip")),
                new LinkedHashMap<>(Map.of("id", "n2", "label", "Other", "text", "y", "attachedTo", "core:use_clip")))));

        WorkflowBuilderSession session = load(workflow);

        assertThat(nodeLabelled(session.getNotes(), "Why")).containsEntry("attachedTo", "agent:make_clip");
        assertThat(nodeLabelled(session.getNotes(), "Other"))
                .as("a note on a node that was not adopted keeps its anchor")
                .containsEntry("attachedTo", "core:use_clip");
    }

    @Test
    @DisplayName("the node itself carries the new key, since the exporter looks it up by id")
    void theNodeCarriesTheNewId() throws Exception {
        WorkflowBuilderSession session = load(workflowWith());

        assertThat(nodeLabelled(session.getMcps(), "Make Clip"))
                .containsEntry("id", "agent:make_clip");
    }

    @Test
    @DisplayName("an edge between two ordinary cores is left exactly as written")
    void unrelatedEdgesAreUntouched() throws Exception {
        // The rewrite walks every edge of the plan, so the risk it carries is
        // renaming an end that had nothing to do with the adopted node.
        Map<String, Object> wait = new LinkedHashMap<>();
        wait.put("id", "core:pause");
        wait.put("type", "wait");
        wait.put("label", "Pause");

        WorkflowEntity workflow = workflowWith(wait);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) workflow.getPlan().get("edges");
        edges.add(new LinkedHashMap<>(Map.of("from", "core:pause", "to", "core:use_clip")));

        WorkflowBuilderSession session = load(workflow);

        assertThat(end(session, 2, "from")).isEqualTo("core:pause");
        assertThat(end(session, 2, "to")).isEqualTo("core:use_clip");
    }

    /**
     * A port on the end that MOVES is carried across.
     *
     * <p>Written the other way round first, with the port on the decision that
     * stayed put, this asserted nothing: that end is not in the rename map, so
     * the branch under test was never taken and deleting the port handling left
     * it green. The port has to be on the adopted end for the assertion to mean
     * anything.
     */
    @Test
    @DisplayName("a port on the ADOPTED end is kept, since dropping it changes where the edge lands")
    void aPortSuffixSurvivesTheRewrite() throws Exception {
        WorkflowEntity workflow = workflowWith();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) workflow.getPlan().get("edges");
        edges.clear();
        edges.add(new LinkedHashMap<>(Map.of("from", "core:make_clip:done", "to", "core:use_clip")));
        edges.add(new LinkedHashMap<>(Map.of("from", "core:decide:if", "to", "core:make_clip")));

        WorkflowBuilderSession session = load(workflow);

        assertThat(end(session, 0, "from"))
                .as("the port belongs to the node, so it moves with it")
                .isEqualTo("agent:make_clip:done");
        assertThat(end(session, 1, "from"))
                .as("and an end that did not move keeps its own port untouched")
                .isEqualTo("core:decide:if");
        assertThat(end(session, 1, "to")).isEqualTo("agent:make_clip");
    }

    /**
     * An edge that names the node by a BARE stored id follows it too.
     *
     * <p>The edge parser cannot type a ref with no {@code type:} prefix and
     * returns nothing for it, so the rewrite must read such a ref as a whole
     * node key. Treated as unparseable-and-skipped instead, the edge would keep
     * naming a key that no longer exists and the node would be unreachable.
     */
    @Test
    @DisplayName("an edge naming the node by its bare, unprefixed stored id is repointed too")
    void aBareStoredIdEdgeFollowsTheNode() throws Exception {
        WorkflowEntity workflow = workflowWith();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cores = (List<Map<String, Object>>) workflow.getPlan().get("cores");
        cores.get(0).put("id", "make_clip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) workflow.getPlan().get("edges");
        edges.clear();
        edges.add(new LinkedHashMap<>(Map.of("from", "trigger:start", "to", "make_clip")));

        WorkflowBuilderSession session = load(workflow);

        assertThat(end(session, 0, "to")).isEqualTo("agent:make_clip");
        assertThat(end(session, 0, "from"))
                .as("the other end is not in the rename map and stays as written")
                .isEqualTo("trigger:start");
    }

    /**
     * A ref with more colons than {@code type:label:port} is split where the ENGINE splits it.
     *
     * <p>The engine reads every edge end with {@link EdgeRefParser} (ExecutionGraph,
     * EdgeWiringOrchestrator, WorkflowPlan): for a core ref the port is the LAST segment, since no
     * port contains a colon (if, elseif_N, case_N, body, iterate, branch_N ...), and everything
     * before it is the node key. So {@code core:make:clip:done} names the node
     * {@code core:make:clip} on port {@code done}. The rewrite this replaced split at the SECOND
     * colon and looked up {@code core:make}, a key nothing answers to: the edge was left naming a
     * node that had just moved, and the engine drops such an edge, so the node's successors were
     * silently cut off. Only a rewrite that splits where the engine splits changes exactly the
     * edges the engine attributes to the adopted node.
     */
    @Test
    @DisplayName("a ref with extra colons is split where the engine splits it: the last segment is the port")
    void aRefWithExtraColonsIsSplitWhereTheEngineSplitsIt() throws Exception {
        WorkflowEntity workflow = workflowWith();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cores = (List<Map<String, Object>>) workflow.getPlan().get("cores");
        cores.get(0).put("id", "core:make:clip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) workflow.getPlan().get("edges");
        edges.clear();
        edges.add(new LinkedHashMap<>(Map.of("from", "core:make:clip:done", "to", "core:use_clip")));

        WorkflowBuilderSession session = load(workflow);

        assertThat(EdgeRefParser.getNodeKey("core:make:clip:done"))
                .as("how the engine reads the stored edge: the node is core:make:clip")
                .isEqualTo("core:make:clip");
        String from = end(session, 0, "from");
        assertThat(from).isEqualTo("agent:make_clip:done");
        assertThat(EdgeRefParser.getNodeKey(from))
                .as("so the engine finds the adopted node at the rewritten end")
                .isEqualTo("agent:make_clip");
        assertThat(EdgeRefParser.getPort(from)).isEqualTo("done");
        assertThat(end(session, 0, "to")).isEqualTo("core:use_clip");
    }

    /**
     * What the downstream nodes READ from it moves too.
     *
     * <p>The edges are only half the wiring. A downstream node names this one in
     * a template in its own params, and the documentation taught
     * {@code {{core:<label>.output.file}}} for as long as the node was a core, so
     * real stored plans are full of them. Left naming the old key they resolve to
     * an EMPTY STRING rather than failing, and the downstream node runs with no
     * file while the run reports success. Moving the node without its references
     * would have reintroduced exactly the failure this change exists to remove.
     */
    @Test
    @DisplayName("a downstream {{core:...}} reference to it is repointed at the new key")
    void downstreamReferencesFollowTheNode() throws Exception {
        WorkflowEntity workflow = workflowWith();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cores =
                (List<Map<String, Object>>) workflow.getPlan().get("cores");
        Map<String, Object> consumer = new LinkedHashMap<>();
        consumer.put("id", "core:mux");
        consumer.put("type", "media");
        consumer.put("label", "Mux");
        consumer.put("params", new LinkedHashMap<>(Map.of(
                "operation", "mux_audio",
                "audio", "{{core:make_clip.output.file}}",
                "tracks", List.of(new LinkedHashMap<>(Map.of(
                        "source", "{{core:make_clip.output.file}}"))))));
        cores.add(consumer);

        WorkflowBuilderSession session = load(workflow);

        String saved = String.valueOf(nodeLabelled(session.getCores(), "Mux"));
        assertThat(saved)
                .as("a reference to the old key resolves to an empty string, not an error")
                .doesNotContain("core:make_clip");
        assertThat(saved)
                .as("including one nested inside a list of tracks")
                .contains("{{agent:make_clip.output.file}}");
    }

    @Test
    @DisplayName("a reference to a node whose key merely STARTS with the moved one is untouched")
    void aLongerNeighbourKeyIsNotRewritten() throws Exception {
        // The rewrite is a string replace, so its risk is matching inside a
        // longer key: {{core:make_clip_two.output}} must survive intact.
        WorkflowEntity workflow = workflowWith();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cores =
                (List<Map<String, Object>>) workflow.getPlan().get("cores");
        Map<String, Object> consumer = new LinkedHashMap<>();
        consumer.put("id", "core:mux");
        consumer.put("type", "media");
        consumer.put("label", "Mux");
        consumer.put("params", new LinkedHashMap<>(Map.of(
                "audio", "{{core:make_clip_two.output.file}}")));
        cores.add(consumer);

        WorkflowBuilderSession session = load(workflow);

        assertThat(String.valueOf(nodeLabelled(session.getCores(), "Mux")))
                .contains("{{core:make_clip_two.output.file}}");
    }

    @Test
    @DisplayName("the label spelling is NOT claimed when another core already owns that key")
    void aNeighbourKeyedByTheLabelIsNotCaptured() throws Exception {
        // The rename map is applied to every edge in the plan, so registering the
        // label form unconditionally would repoint a neighbour's edges at this
        // node, silently and depending on the order the two were read in.
        WorkflowEntity workflow = workflowWith();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cores =
                (List<Map<String, Object>>) workflow.getPlan().get("cores");
        // The generate node's id is deliberately NOT the label form here, and a
        // real, unrelated core owns that spelling.
        cores.get(0).put("id", "core:g1");
        Map<String, Object> impostor = new LinkedHashMap<>();
        impostor.put("id", "core:make_clip");
        impostor.put("type", "wait");
        impostor.put("label", "Make Clip");
        cores.add(impostor);

        WorkflowBuilderSession session = load(workflow);

        assertThat(end(session, 0, "to"))
                .as("the edge belongs to the wait node that owns this key, not to the one that moved")
                .isEqualTo("core:make_clip");
    }

    @Test
    @DisplayName("a node with a blank label is NOT adopted, since adopting it would delete it")
    void aBlankLabelIsNotAdopted() throws Exception {
        // computeNodeId falls back to the id when the label is blank, and a
        // stored id is routinely already prefixed, so the node would land under
        // agent:core_make_clip; the plan parser then drops any agent whose label
        // is blank, with a warning nobody reads. Opening the workflow would have
        // deleted the node. Left among the cores the engine refuses it, which is
        // loud, and the error checker now names it too.
        WorkflowEntity workflow = workflowWith();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cores =
                (List<Map<String, Object>>) workflow.getPlan().get("cores");
        // BLANK, not absent. A missing label and a whitespace one take different
        // branches of the guard, and only the blank case can tell whether the
        // isBlank() half is doing anything.
        cores.get(0).put("label", "   ");

        WorkflowBuilderSession session = load(workflow);

        assertThat(session.getCores())
                .as("it stays where the engine will refuse it, rather than being silently lost")
                .anySatisfy(core -> assertThat(core.get("type")).isEqualTo("generate"));
        assertThat(session.getMcps())
                .noneSatisfy(node -> assertThat(node.get("type")).isEqualTo("generate"));
    }

    @Test
    @DisplayName("a reference inside an INTERFACE follows the node too, not just one inside a core")
    void interfaceReferencesFollowTheNode() throws Exception {
        // The bucket most likely to hold one in real data: an interface binds its
        // variable_mapping to a producing node, and a reference left naming the
        // old key renders the empty state on every run with no error anywhere.
        WorkflowEntity workflow = workflowWith();
        Map<String, Object> iface = new LinkedHashMap<>();
        iface.put("id", "interface:preview");
        iface.put("label", "Preview");
        iface.put("variable_mapping",
                new LinkedHashMap<>(Map.of("clip", "{{core:make_clip.output.file}}")));
        workflow.getPlan().put("interfaces", new ArrayList<>(List.of(iface)));

        WorkflowBuilderSession session = load(workflow);

        assertThat(String.valueOf(session.getInterfaces()))
                .doesNotContain("core:make_clip")
                .contains("{{agent:make_clip.output.file}}");
    }
}
