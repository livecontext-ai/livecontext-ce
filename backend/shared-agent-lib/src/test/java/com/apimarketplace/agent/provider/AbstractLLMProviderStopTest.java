package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for mid-stream stop signal in AbstractLLMProvider.processStreamingResponse().
 * Verifies that shouldStop() is checked during streaming and can break the loop early.
 */
@DisplayName("AbstractLLMProvider - Mid-Stream Stop")
class AbstractLLMProviderStopTest {

    /**
     * Minimal concrete implementation for testing processStreamingResponse().
     */
    static class TestProvider extends AbstractLLMProvider {
        TestProvider() {
            super();
        }

        @Override
        protected String getApiKey() { return "test-key"; }

        @Override
        protected String getApiUrl() { return "https://api.test.com/v1/completions"; }

        @Override
        protected Map<String, Object> buildRequestBody(CompletionRequest request) {
            return Map.of("model", "test");
        }

        @Override
        protected CompletionResponse parseResponse(Map<String, Object> response) {
            return CompletionResponse.builder().content("test").build();
        }

        @Override
        protected HttpHeaders buildHeaders() {
            return new HttpHeaders();
        }

        @Override
        protected String processStreamingLine(String line) {
            if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                return line.substring(6);
            }
            return null;
        }

        @Override
        public String getProviderName() { return "test"; }

        @Override
        public String getDefaultModel() { return "test-model"; }

        @Override
        public List<String> getSupportedModels() { return List.of("test-model"); }

        /**
         * Expose protected method for testing.
         */
        public CompletionResponse testProcessStreamingResponse(BufferedReader reader, StreamingCallback callback) throws Exception {
            return processStreamingResponse(reader, callback);
        }
    }

    /**
     * Test callback that records chunks and supports stop-after-N-chunks.
     */
    static class StopAfterNCallback implements StreamingCallback {
        private final int stopAfterChunks;
        private final AtomicInteger chunkCount = new AtomicInteger(0);
        private final List<String> receivedChunks = new ArrayList<>();
        private CompletionResponse completionResponse;

        StopAfterNCallback(int stopAfterChunks) {
            this.stopAfterChunks = stopAfterChunks;
        }

        @Override
        public void onChunk(String content) {
            receivedChunks.add(content);
            chunkCount.incrementAndGet();
        }

        @Override
        public void onToolCall(ToolCall toolCall) {}

        @Override
        public void onComplete(CompletionResponse response) {
            this.completionResponse = response;
        }

        @Override
        public void onError(String error) {}

        @Override
        public boolean shouldStop() {
            return chunkCount.get() >= stopAfterChunks;
        }

        public List<String> getReceivedChunks() { return receivedChunks; }
        public CompletionResponse getCompletionResponse() { return completionResponse; }
    }

    @Nested
    @DisplayName("processStreamingResponse with stop signal")
    class StopSignalTests {

        @Test
        @DisplayName("should break early when shouldStop() returns true mid-stream")
        void shouldBreakEarlyOnStopSignal() throws Exception {
            // Simulate 5 SSE data lines
            String sseStream = """
                    data: chunk1
                    data: chunk2
                    data: chunk3
                    data: chunk4
                    data: chunk5
                    data: [DONE]
                    """;

            BufferedReader reader = new BufferedReader(new StringReader(sseStream));
            StopAfterNCallback callback = new StopAfterNCallback(2); // stop after 2 chunks

            TestProvider provider = new TestProvider();
            CompletionResponse response = provider.testProcessStreamingResponse(reader, callback);

            // Should have received only 2 chunks before stop signal kicked in
            assertThat(callback.getReceivedChunks()).hasSize(2);
            assertThat(callback.getReceivedChunks()).containsExactly("chunk1", "chunk2");

            // onComplete should still be called with partial content
            assertThat(response).isNotNull();
            assertThat(response.content()).isEqualTo("chunk1chunk2");
        }

        @Test
        @DisplayName("should process all chunks when shouldStop() never returns true")
        void shouldProcessAllChunksWhenNotStopped() throws Exception {
            String sseStream = """
                    data: chunk1
                    data: chunk2
                    data: chunk3
                    data: [DONE]
                    """;

            BufferedReader reader = new BufferedReader(new StringReader(sseStream));
            StopAfterNCallback callback = new StopAfterNCallback(999); // never stop

            TestProvider provider = new TestProvider();
            CompletionResponse response = provider.testProcessStreamingResponse(reader, callback);

            assertThat(callback.getReceivedChunks()).hasSize(3);
            assertThat(response.content()).isEqualTo("chunk1chunk2chunk3");
        }

        @Test
        @DisplayName("should return partial content in response when stopped mid-stream")
        void shouldReturnPartialContentOnStop() throws Exception {
            String sseStream = """
                    data: Hello
                    data:  world
                    data:  how
                    data:  are
                    data:  you
                    data: [DONE]
                    """;

            BufferedReader reader = new BufferedReader(new StringReader(sseStream));
            StopAfterNCallback callback = new StopAfterNCallback(3);

            TestProvider provider = new TestProvider();
            CompletionResponse response = provider.testProcessStreamingResponse(reader, callback);

            // Only 3 chunks processed
            assertThat(callback.getReceivedChunks()).hasSize(3);
            // Response contains only partial content
            assertThat(response.content()).isEqualTo("Hello world how");
        }

        @Test
        @DisplayName("should stop immediately if shouldStop() is true before first chunk")
        void shouldStopBeforeFirstChunk() throws Exception {
            String sseStream = """
                    data: chunk1
                    data: chunk2
                    data: [DONE]
                    """;

            BufferedReader reader = new BufferedReader(new StringReader(sseStream));
            StopAfterNCallback callback = new StopAfterNCallback(0); // stop immediately

            TestProvider provider = new TestProvider();
            CompletionResponse response = provider.testProcessStreamingResponse(reader, callback);

            assertThat(callback.getReceivedChunks()).isEmpty();
            assertThat(response.content()).isEmpty();
        }
    }

    /**
     * Delta 3 - {@link StreamingCallback#getCompletionTokenBudget()} enforces a local
     * hard cap on completion tokens as a last line of defense against a single-turn
     * overdraft when the pre-flight estimate was too low. The cap is purely local
     * (no HTTP call in the streaming hot path), and the finish reason is rewritten
     * to {@code budget_exhausted} so upstream code can surface BUDGET_EXHAUSTED.
     */
    @Nested
    @DisplayName("processStreamingResponse with completion-token budget (Delta 3)")
    class CompletionTokenBudgetTests {

        /**
         * Callback declaring a token budget but never requesting a user-driven stop.
         * Uses ~4 chars per token approximation, matching the provider-side check.
         */
        static class BudgetCallback implements StreamingCallback {
            private final long budget;
            private final List<String> received = new ArrayList<>();
            private CompletionResponse completion;

            BudgetCallback(long budget) { this.budget = budget; }

            @Override public void onChunk(String content) { received.add(content); }
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onComplete(CompletionResponse r) { this.completion = r; }
            @Override public void onError(String error) {}
            @Override public long getCompletionTokenBudget() { return budget; }

            List<String> getReceived() { return received; }
            CompletionResponse getCompletion() { return completion; }
        }

        @Test
        @DisplayName("Aborts streaming when accumulated content ≈ budget tokens, marks finish_reason=budget_exhausted")
        void abortsWhenBudgetExceeded() throws Exception {
            // Each chunk is 20 chars ≈ 5 tokens. Budget = 6 tokens → 24 chars.
            // After 2 chunks (40 chars ≈ 10 tokens), the guard trips.
            String sseStream = """
                    data: aaaaaaaaaaaaaaaaaaaa
                    data: bbbbbbbbbbbbbbbbbbbb
                    data: cccccccccccccccccccc
                    data: dddddddddddddddddddd
                    data: [DONE]
                    """;
            BufferedReader reader = new BufferedReader(new StringReader(sseStream));
            BudgetCallback callback = new BudgetCallback(6L);
            TestProvider provider = new TestProvider();

            CompletionResponse response = provider.testProcessStreamingResponse(reader, callback);

            // The partial content must be preserved - user still gets what was streamed.
            assertThat(response.content()).isNotNull();
            assertThat(response.content().length()).isLessThanOrEqualTo(40);
            // Finish reason signals budget exhaustion, not a normal stop.
            assertThat(response.finishReason()).isEqualTo("budget_exhausted");
            // The remaining chunks were never delivered.
            assertThat(callback.getReceived().size()).isLessThan(4);
            assertThat(callback.getCompletion()).isNotNull();
        }

        @Test
        @DisplayName("Passes through normally when budget is -1 (default, inert)")
        void inertWhenBudgetDisabled() throws Exception {
            String sseStream = """
                    data: hello
                    data:  world
                    data: [DONE]
                    """;
            BufferedReader reader = new BufferedReader(new StringReader(sseStream));
            BudgetCallback callback = new BudgetCallback(-1L);
            TestProvider provider = new TestProvider();

            CompletionResponse response = provider.testProcessStreamingResponse(reader, callback);

            assertThat(response.content()).isEqualTo("hello world");
            // Normal end-of-stream, not budget-triggered.
            assertThat(response.finishReason()).isEqualTo("stop");
        }

        @Test
        @DisplayName("Does not trip when accumulated tokens stay below the budget")
        void passesWhenUnderBudget() throws Exception {
            String sseStream = """
                    data: hi
                    data: [DONE]
                    """;
            BufferedReader reader = new BufferedReader(new StringReader(sseStream));
            BudgetCallback callback = new BudgetCallback(1000L);
            TestProvider provider = new TestProvider();

            CompletionResponse response = provider.testProcessStreamingResponse(reader, callback);

            assertThat(response.content()).isEqualTo("hi");
            assertThat(response.finishReason()).isEqualTo("stop");
        }
    }
}
