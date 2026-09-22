package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * What a transform reports, which nothing asserted before this class existed.
 *
 * <p>Transform is, after set, the most common node for {@code {{$vars.secret}}}, and its
 * mapping labels are the AUTHOR'S names, so both halves of the gate apply to it: the
 * workspace-variable rule on the value and the key-name rule on the label. Reverting either
 * turned nothing red.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("a transform reports what its mappings produced")
class TransformNodeReportedParamsTest {

    private static final String SECRET = "s3cr3t-should-never-be-persisted";

    @Mock private WorkflowPlan mockPlan;
    @Mock private TemplateEngine engine;

    private ExecutionContext context() {
        return ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    private TransformNode node(String label, String expression) {
        TransformNode node = new TransformNode("core:transform",
            List.of(new Core.TransformMapping(label, expression)));
        node.setTemplateAdapter(new V2TemplateAdapter(engine));
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramsOf(NodeExecutionResult result) {
        return (Map<String, Object>) result.output().get("resolved_params");
    }

    @Test
    @DisplayName("a value pulled from a WORKSPACE variable is withheld, not copied onto the row")
    void withholdsAWorkspaceVariable() {
        // The label is `greeting` deliberately: a name the word rules do not match, so this
        // asserts the $vars rule and not the key-name rule wearing its clothes.
        when(engine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
            .thenReturn(SECRET);

        Map<String, Object> params = paramsOf(node("greeting", "{{$vars.welcome}}").execute(context()));

        assertThat(params.get("greeting")).isEqualTo(ReportedParams.WITHHELD_WORKSPACE_VARIABLE);
        assertThat(params.toString()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a mapping label the AUTHOR called `api_key` is masked whatever it was mapped from")
    void masksACredentialNamedLabel() {
        when(engine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
            .thenReturn(SECRET);

        Map<String, Object> params = paramsOf(node("api_key", "{{trigger:hook.output.k}}").execute(context()));

        assertThat(params.get("api_key")).isEqualTo(ReportedParams.WITHHELD_CREDENTIAL);
        assertThat(params.toString()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a mapping that produced NOTHING reports null, the same answer the Output column gives")
    void reportsNullForAMappingThatResolvedToNothing() {
        // It used to report the expression here, which is what made a mapping that produced
        // nothing indistinguishable from one that produced the text `{{core:x.output.y}}` -
        // the defect DataInputNode fixes in the same commit, decided the other way.
        when(engine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
            .thenReturn(null);

        Map<String, Object> params = paramsOf(node("title", "{{core:missing.output.title}}").execute(context()));

        assertThat(params).containsKey("title");
        assertThat(params.get("title")).isNull();
    }

    @Test
    @DisplayName("an ordinary mapping reports the value it produced: that is what the panel is opened for")
    void reportsTheValueItProduced() {
        when(engine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
            .thenReturn("Quarterly report");

        Map<String, Object> params = paramsOf(node("title", "{{core:prepare.output.title}}").execute(context()));

        assertThat(params.get("title")).isEqualTo("Quarterly report");
    }
}
