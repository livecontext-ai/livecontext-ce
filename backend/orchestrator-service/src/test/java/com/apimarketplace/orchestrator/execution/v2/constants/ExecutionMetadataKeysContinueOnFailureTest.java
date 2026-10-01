package com.apimarketplace.orchestrator.execution.v2.constants;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE;
import static com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.isContinueOnFailureStored;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExecutionMetadataKeys#isContinueOnFailureStored} reads the continueOnFailure flag off a
 * STORED step output, which is what the step-by-step path (every production run) has in hand after
 * a context rebuild. It must recognise the three shapes a stored result takes, and nothing else:
 * a false negative silently skips every node below a continued failure, a false positive runs them
 * after a failure the user never asked to continue past.
 */
@DisplayName("ExecutionMetadataKeys - continueOnFailure flag on a stored step output")
class ExecutionMetadataKeysContinueOnFailureTest {

    private static NodeExecutionResult failed(Map<String, Object> output, Map<String, Object> metadata) {
        return new NodeExecutionResult("mcp:call", NodeStatus.FAILED, output, Optional.of("down"), metadata, 5L);
    }

    @Test
    @DisplayName("REGRESSION: a flat persisted output with the flag is continued (what a context rebuild reads)")
    void flatPersistedOutput() {
        assertThat(isContinueOnFailureStored(Map.of(POLICY_CONTINUE_ON_FAILURE, true, "error", "down"))).isTrue();
    }

    @Test
    @DisplayName("REGRESSION: an output nested under 'output' (the in-memory envelope) is continued")
    void nestedUnderOutput() {
        assertThat(isContinueOnFailureStored(Map.of("output", Map.of(POLICY_CONTINUE_ON_FAILURE, true)))).isTrue();
    }

    @Test
    @DisplayName("a NodeExecutionResult flagged in its metadata is continued")
    void nodeExecutionResultMetadata() {
        assertThat(isContinueOnFailureStored(failed(Map.of(), Map.of(POLICY_CONTINUE_ON_FAILURE, true)))).isTrue();
    }

    @Test
    @DisplayName("a NodeExecutionResult flagged in its output only is continued")
    void nodeExecutionResultOutput() {
        assertThat(isContinueOnFailureStored(failed(Map.of(POLICY_CONTINUE_ON_FAILURE, true), Map.of()))).isTrue();
    }

    @Test
    @DisplayName("null, an unflagged output and an unflagged result are not continued")
    void absentFlag() {
        assertThat(isContinueOnFailureStored(null)).isFalse();
        assertThat(isContinueOnFailureStored(Map.of("error", "down"))).isFalse();
        assertThat(isContinueOnFailureStored(Map.of("output", Map.of("error", "down")))).isFalse();
        assertThat(isContinueOnFailureStored(failed(Map.of(), Map.of()))).isFalse();
    }

    @Test
    @DisplayName("only a boolean true counts: false, the string 'true' and null values are not the flag")
    void onlyBooleanTrue() {
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put(POLICY_CONTINUE_ON_FAILURE, null);

        assertThat(isContinueOnFailureStored(Map.of(POLICY_CONTINUE_ON_FAILURE, false))).isFalse();
        assertThat(isContinueOnFailureStored(Map.of(POLICY_CONTINUE_ON_FAILURE, "true"))).isFalse();
        assertThat(isContinueOnFailureStored(nullValue)).isFalse();
        assertThat(isContinueOnFailureStored(Map.of("output", Map.of(POLICY_CONTINUE_ON_FAILURE, false)))).isFalse();
    }

    @Test
    @DisplayName("the flag is read one level deep only: a flag buried inside a provider payload is ignored")
    void notReadDeeperThanTheEnvelope() {
        Map<String, Object> buried = Map.of("output", Map.of("response", Map.of(POLICY_CONTINUE_ON_FAILURE, true)));

        assertThat(isContinueOnFailureStored(buried)).isFalse();
    }

    @Test
    @DisplayName("anything that is neither a Map nor a NodeExecutionResult is not continued")
    void unrelatedObject() {
        assertThat(isContinueOnFailureStored("policy_continue_on_failure")).isFalse();
        assertThat(isContinueOnFailureStored(List.of(Map.of(POLICY_CONTINUE_ON_FAILURE, true)))).isFalse();
        assertThat(isContinueOnFailureStored(42)).isFalse();
    }

    @Test
    @DisplayName("POLICY_OUTPUT_KEYS lists every policy annotation persistence must keep, the flag included")
    void policyOutputKeys() {
        assertThat(ExecutionMetadataKeys.POLICY_OUTPUT_KEYS).containsExactlyInAnyOrder(
            ExecutionMetadataKeys.POLICY_ATTEMPT,
            ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS,
            ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT,
            ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE,
            ExecutionMetadataKeys.POLICY_RETRY_STOPPED,
            ExecutionMetadataKeys.POLICY_TIMEOUT);
    }
}
