package com.apimarketplace.orchestrator.tools.workflow.help;

import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The help an agent reads about the execution policy has to describe the tool that exists.
 *
 * <p>An agent only ever sees the MCP interface, so a capability the help does not mention is a
 * capability nobody uses, and a rule the help states wrongly costs a real call to find out. Two
 * specific things are worth pinning here rather than trusting to review:
 *
 * <ul>
 *   <li><b>The topic is reachable.</b> The refusal messages the tools return point the agent at
 *       {@code topics=['node_policy']}. A topic name that resolves to nothing sends the agent to a
 *       dead end at the exact moment it needs the answer.</li>
 *   <li><b>The multiplication is stated.</b> It is the one thing an author cannot deduce: their
 *       retry and the platform's compose by multiplying, so a careful author hammers a provider
 *       harder than a careless one unless the help says so.</li>
 * </ul>
 */
@DisplayName("workflow(action='help', topics=['node_policy']) describes the policy the tool accepts")
class NodePolicyHelpMatchesTheToolTest {

    private static WorkflowHelpProvider provider() {
        // The topic is static text; the collaborators exist for other topics.
        return new WorkflowHelpProvider(
                mock(com.apimarketplace.orchestrator.service.NodeLibraryService.class),
                mock(com.apimarketplace.orchestrator.service.NodeHelpFormatter.class),
                mock(com.apimarketplace.orchestrator.services.generation.GenerationExecutionService.class));
    }

    private static void collectStrings(Object node, StringBuilder sink) {
        if (node instanceof String s) {
            sink.append(s).append('\n');
        } else if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                sink.append(k).append('\n');
                collectStrings(v, sink);
            });
        } else if (node instanceof Iterable<?> iterable) {
            iterable.forEach(v -> collectStrings(v, sink));
        }
    }

    private static String rendered(String topic) {
        StringBuilder sink = new StringBuilder();
        collectStrings(provider().getHelp(topic), sink);
        return sink.toString();
    }

    @Test
    @DisplayName("the topic the refusal messages name actually resolves")
    void theTopicResolves() {
        // Both add_node and modify point the agent here when they refuse a policy.
        Map<String, Object> help = provider().getHelp("node_policy");

        assertThat(help).isNotNull().isNotEmpty();
        assertThat(String.valueOf(help.get("title"))).containsIgnoringCase("policy");
    }

    @Test
    @DisplayName("the spellings a model reaches for resolve to the same topic")
    void theAliasesResolve() {
        for (String alias : List.of("nodePolicy", "policy", "retry", "timeout",
                "continue_on_failure", "execute_once", "execution_policy", "provider_retry")) {
            assertThat(provider().getHelp(alias))
                    .as("alias '%s'", alias)
                    .isNotNull()
                    .containsKey("title");
        }
    }

    @Test
    @DisplayName("every field the record actually has is documented, so none is discoverable only "
            + "by trial")
    void everyFieldIsDocumented() {
        // Derived from NodePolicy's record components rather than listed by hand: a literal list
        // would certify the author's idea of the field set and stay green through the one change
        // that matters, a new field nobody documented.
        String text = rendered("node_policy");

        for (var component : com.apimarketplace.orchestrator.domain.workflow.NodePolicy.class
                .getRecordComponents()) {
            assertThat(text)
                    .as("nodePolicy.%s", component.getName())
                    .contains(component.getName());
        }
    }

    @Test
    @DisplayName("it states the one thing an author cannot deduce: the two retry layers MULTIPLY")
    void itStatesTheMultiplication() {
        String text = rendered("node_policy");

        assertThat(text).containsIgnoringCase("multiply");
        assertThat(text)
                .as("and what to do about it")
                .contains("providerRetryMaxWaitSec=0");
    }

    @Test
    @DisplayName("it says where the policy goes, since sending it inside params is the natural mistake")
    void itSaysThePolicyIsOutsideParams() {
        String text = rendered("node_policy");

        assertThat(text).contains("OUTSIDE params");
    }

    @Test
    @DisplayName("it names the node types the engine refuses each flag on")
    void itNamesTheRefusedNodeTypes() {
        String text = rendered("node_policy");

        assertThat(text).containsIgnoringCase("decision, switch, option");
        assertThat(text).containsIgnoringCase("split, aggregate, merge, loop");
        assertThat(text).containsIgnoringCase("trigger");
    }

    @Test
    @DisplayName("it tells the agent how to clear a policy, which the whole-block replacement "
            + "makes non-obvious")
    void itSaysHowToClearAPolicy() {
        String text = rendered("node_policy");

        assertThat(text).contains("nodePolicy={}");
    }

    @Test
    @DisplayName("it says what the agent can READ BACK, since a provider wait emits no event")
    void itSaysWhatCanBeReadBack() {
        String text = rendered("node_policy");

        assertThat(text)
                .as("the wait happens inside one tool call, so the node stays RUNNING and the only "
                        + "trace is this field")
                .contains("_provider_retries");
        assertThat(text).contains("get_node_output");
    }

    @Test
    @DisplayName("it is honest about billing, so an agent does not avoid retries to save credits")
    void itIsHonestAboutBilling() {
        String text = rendered("node_policy");

        assertThat(text).containsIgnoringCase("ONE credit");
    }

    @Test
    @DisplayName("it references only actions the agent can actually call")
    void itReferencesOnlyCallableActions() {
        String text = rendered("node_policy");

        // An agent sees the MCP interface and nothing else: a REST path, a UI menu or a file name
        // in the help is an instruction it cannot follow.
        assertThat(text)
                .doesNotContain("/api/")
                .doesNotContain("SELECT ")
                .doesNotContain(".java")
                .doesNotContain("mvn ");
    }

    @Test
    @DisplayName("every workflow(action='...') it names is a real action of the tool")
    void everyActionItNamesExists() {
        // Checking the forbidden shapes is only half of it: an action that does not exist reads as
        // perfectly callable and sends the agent into an error on the one call the help was meant
        // to save it. The names are taken from the text and checked against the tool's own list.
        String text = rendered("node_policy");
        Set<String> declared = declaredActions();

        Matcher matcher = Pattern.compile("action='([a-z_]+)'").matcher(text);
        Set<String> named = new LinkedHashSet<>();
        while (matcher.find()) {
            named.add(matcher.group(1));
        }

        assertThat(named).as("the topic should show the agent how to use the setting").isNotEmpty();
        assertThat(declared).as("sanity: the action list was found").contains("add_node", "modify");
        assertThat(named)
                .as("actions named in the node_policy topic that the workflow tool does not have")
                .allSatisfy(action -> assertThat(declared).contains(action));
    }

    /**
     * The action names the tool's own schema advertises, taken from the leading
     * {@code "Action: a, b, c ..."} ENUMERATION only.
     *
     * <p>Harvesting every word of the whole description instead would put most of the English in
     * that paragraph into the "declared" set, and any action name that happens to be an ordinary
     * word would pass. The enumeration is the list a fresh agent actually reads to learn what it can
     * call, so it is the right authority here.
     */
    private static Set<String> declaredActions() {
        var factory = new com.apimarketplace.orchestrator.tools.workflow.builder
                .WorkflowBuilderToolDefinitionFactory(
                mock(com.apimarketplace.orchestrator.service.NodeLibraryService.class));
        String actionDescription = factory.buildToolDefinition().parameters().stream()
                .filter(parameter -> "action".equals(parameter.name()))
                .findFirst().orElseThrow().description();
        Matcher enumeration = Pattern.compile("Action:\\s*([a-z_, ]+)").matcher(actionDescription);
        assertThat(enumeration.find())
                .as("the action parameter must open with the list of actions, which is what an agent "
                        + "reads to learn they exist")
                .isTrue();
        Set<String> actions = new LinkedHashSet<>();
        for (String name : enumeration.group(1).split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                actions.add(trimmed);
            }
        }
        return actions;
    }
}
