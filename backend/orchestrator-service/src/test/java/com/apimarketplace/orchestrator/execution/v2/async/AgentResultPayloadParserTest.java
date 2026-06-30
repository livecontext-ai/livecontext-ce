package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentResultPayloadParser}.
 *
 * <p>This parser is load-bearing: two call sites (the pub/sub subscriber and the
 * recovery service) decode worker payloads through it, and before this class existed
 * they disagreed on the boolean rule - one treated {@code {success: false}} as
 * success=true. These tests lock in the exact contract documented on the class.</p>
 */
@DisplayName("AgentResultPayloadParser")
class AgentResultPayloadParserTest {

    @Nested
    @DisplayName("isSuccess")
    class IsSuccess {

        @Test
        @DisplayName("null payload → failure")
        void nullPayloadIsFailure() {
            assertThat(AgentResultPayloadParser.isSuccess(null)).isFalse();
        }

        @Test
        @DisplayName("empty payload → success (no error, no explicit flag)")
        void emptyPayloadIsSuccess() {
            assertThat(AgentResultPayloadParser.isSuccess(new HashMap<>())).isTrue();
        }

        @Test
        @DisplayName("explicit success=true → success")
        void explicitTrueIsSuccess() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("success", true);
            assertThat(AgentResultPayloadParser.isSuccess(payload)).isTrue();
        }

        @Test
        @DisplayName("explicit success=false → failure (even when no error field)")
        void explicitFalseIsFailureEvenWithoutErrorField() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("success", false);
            // This is the exact bug the parser was extracted to fix: legacy subscriber code
            // would have reported this as success=true.
            assertThat(AgentResultPayloadParser.isSuccess(payload)).isFalse();
        }

        @Test
        @DisplayName("error field present → failure (even when success=true)")
        void errorFieldTrumpsExplicitSuccess() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("success", true);
            payload.put("error", "something broke");
            assertThat(AgentResultPayloadParser.isSuccess(payload)).isFalse();
        }

        @Test
        @DisplayName("error field with null value → still failure")
        void nullErrorValueIsStillFailure() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("error", null);
            assertThat(AgentResultPayloadParser.isSuccess(payload)).isFalse();
        }

        @Test
        @DisplayName("success as boxed Boolean.TRUE → success")
        void boxedBooleanTrueIsSuccess() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("success", Boolean.TRUE);
            assertThat(AgentResultPayloadParser.isSuccess(payload)).isTrue();
        }

        @Test
        @DisplayName("success as a non-Boolean (e.g. String 'true') → treated as failure")
        void nonBooleanSuccessIsFailure() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("success", "true");
            // Only Boolean.TRUE counts - we must not accept stringly-typed values by accident.
            assertThat(AgentResultPayloadParser.isSuccess(payload)).isFalse();
        }
    }

    @Nested
    @DisplayName("extractError")
    class ExtractError {

        @Test
        @DisplayName("no error field → null")
        void absentErrorIsNull() {
            assertThat(AgentResultPayloadParser.extractError(new HashMap<>())).isNull();
        }

        @Test
        @DisplayName("null payload → null")
        void nullPayloadIsNull() {
            assertThat(AgentResultPayloadParser.extractError(null)).isNull();
        }

        @Test
        @DisplayName("error field with null value → null")
        void nullErrorValueIsNull() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("error", null);
            assertThat(AgentResultPayloadParser.extractError(payload)).isNull();
        }

        @Test
        @DisplayName("error field with string value → that string")
        void stringErrorIsPreserved() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("error", "boom");
            assertThat(AgentResultPayloadParser.extractError(payload)).isEqualTo("boom");
        }

        @Test
        @DisplayName("error field with non-string value → toString representation")
        void nonStringErrorIsStringified() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("error", 42);
            assertThat(AgentResultPayloadParser.extractError(payload)).isEqualTo("42");
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        @DisplayName("success payload populates all fields including optional context")
        void decodeSuccess() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("answer", "42");
            payload.put("success", true);

            AgentResultMessage msg = AgentResultPayloadParser.decode(
                "corr-1", payload, "run-1", "agent:test", "classify");

            assertThat(msg.correlationId()).isEqualTo("corr-1");
            assertThat(msg.runId()).isEqualTo("run-1");
            assertThat(msg.nodeId()).isEqualTo("agent:test");
            assertThat(msg.agentType()).isEqualTo("classify");
            assertThat(msg.success()).isTrue();
            assertThat(msg.errorMessage()).isNull();
            assertThat(msg.result()).isSameAs(payload);
            assertThat(msg.completedAt()).isNotNull();
        }

        @Test
        @DisplayName("failure payload populates errorMessage and success=false")
        void decodeFailure() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("error", "model timed out");

            AgentResultMessage msg = AgentResultPayloadParser.decode(
                "corr-2", payload, "run-2", "agent:fail", "agent");

            assertThat(msg.success()).isFalse();
            assertThat(msg.errorMessage()).isEqualTo("model timed out");
        }

        @Test
        @DisplayName("explicit success=false with no error field still yields success=false")
        void decodeExplicitFalseNoError() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("success", false);

            AgentResultMessage msg = AgentResultPayloadParser.decode(
                "corr-3", payload, null, null, null);

            assertThat(msg.success()).isFalse();
            assertThat(msg.errorMessage()).isNull();
        }

        @Test
        @DisplayName("decode passes through null runId/nodeId/agentType (pub/sub path)")
        void decodeAllowsNullContext() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("success", true);

            AgentResultMessage msg = AgentResultPayloadParser.decode(
                "corr-4", payload, null, null, null);

            assertThat(msg.runId()).isNull();
            assertThat(msg.nodeId()).isNull();
            assertThat(msg.agentType()).isNull();
            assertThat(msg.success()).isTrue();
        }
    }
}
