package com.apimarketplace.common.storage.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkflowFileScope.isWorkflowScoped")
class WorkflowFileScopeTest {

    private static final String WF = "1dd25096-3ec8-4f37-be08-a38f4759de27";

    @Test
    @DisplayName("a UUID workflow with a real (or no) run id is a workflow scope")
    void realWorkflowRun() {
        assertThat(WorkflowFileScope.isWorkflowScoped(WF, "b4f0c1d2-0000-4000-8000-000000000001")).isTrue();
        assertThat(WorkflowFileScope.isWorkflowScoped(WF, null)).isTrue();
    }

    @Test
    @DisplayName("an ad-hoc run is not, even though its synthetic plan id is a well-formed UUID")
    void adHocRun() {
        assertThat(WorkflowFileScope.isWorkflowScoped(WF, "adhoc-" + WF)).isFalse();
    }

    @Test
    @DisplayName("a missing or non-UUID workflow id (the file tools' 'unknown') is not")
    void notAWorkflowId() {
        assertThat(WorkflowFileScope.isWorkflowScoped(null, null)).isFalse();
        assertThat(WorkflowFileScope.isWorkflowScoped("  ", null)).isFalse();
        assertThat(WorkflowFileScope.isWorkflowScoped("unknown", "unknown")).isFalse();
    }

    @Test
    @DisplayName("a non-canonical id that UUID.fromString would accept is not (same rule as the V555 repair)")
    void nonCanonicalUuid() {
        assertThat(WorkflowFileScope.isWorkflowScoped("1-2-3-4-5", null)).isFalse();
        assertThat(WorkflowFileScope.isWorkflowScoped(WF.toUpperCase(), null)).isTrue();
    }
}
