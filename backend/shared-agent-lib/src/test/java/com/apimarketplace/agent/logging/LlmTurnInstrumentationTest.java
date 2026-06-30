package com.apimarketplace.agent.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.domain.UsageInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.CallPurpose;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 0.2 - verify {@code [LLM_TURN]} log line contains the contract fields
 * that the Grafana dashboard and alert rules consume. Breaking one of these
 * field names silently breaks the p95 panels - hence the explicit assertions
 * on key presence + value rather than a fuzzy substring check.
 */
@DisplayName("LlmTurnInstrumentation")
class LlmTurnInstrumentationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LlmTurnInstrumentation.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("emits [LLM_TURN] line containing every contract field - purpose, char counts, usage")
    void emitsContractFields() throws JsonProcessingException {
        ToolParameter qParam = ToolParameter.builder()
            .name("q")
            .type("string")
            .description("Query string")
            .build();
        ToolDefinition tool = ToolDefinition.builder()
            .name("search")
            .description("Search the web")
            .parameters(List.of(qParam))
            .build();
        AgentLoopContext context = AgentLoopContext.builder()
            .provider("google")
            .model("gemini-3-flash")
            .tenantId("tenant-abc")
            .agentId("agent-42")
            .runId("run-xyz")
            .purpose(CallPurpose.MAIN)
            .build();
        CompletionRequest request = CompletionRequest.builder()
            .tenantId("tenant-abc")
            .model("gemini-3-flash")
            .systemPrompt("You are helpful.")
            .userPrompt("hello")
            .conversationHistory(List.of(
                new Message(Message.Role.USER, "hi", null, null, null, null),
                new Message(Message.Role.ASSISTANT, "hello there", null, null, null, null)
            ))
            .tools(List.of(tool))
            .stream(false)
            .build();
        UsageInfo usage = UsageInfo.builder()
            .promptTokens(1234)
            .completionTokens(56)
            .totalTokens(1290)
            .thoughtsTokenCount(185)
            .cachedContentTokenCount(4096)
            .cacheReadInputTokens(null)
            .build();

        LlmTurnInstrumentation.logTurn(context, request, usage, 3);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).startsWith("[LLM_TURN] ");

        String json = event.getFormattedMessage().substring("[LLM_TURN] ".length());
        Map<String, Object> fields = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});

        assertThat(fields).containsEntry("turnIndex", 3);
        assertThat(fields).containsEntry("provider", "google");
        assertThat(fields).containsEntry("model", "gemini-3-flash");
        assertThat(fields).containsEntry("tenantId", "tenant-abc");
        assertThat(fields).containsEntry("agentId", "agent-42");
        assertThat(fields).containsEntry("runId", "run-xyz");
        assertThat(fields).containsEntry("purpose", "MAIN");
        assertThat(fields).containsEntry("systemPrompt_chars", "You are helpful.".length());
        assertThat(fields).containsEntry("userPrompt_chars", "hello".length());
        assertThat(fields).containsEntry("history_chars", "hi".length() + "hello there".length());
        assertThat(fields).containsEntry("history_messages", 2);
        assertThat(fields).containsEntry("tools_count", 1);
        assertThat(fields).containsEntry("stream", false);
        assertThat(fields).containsEntry("promptTokens", 1234);
        assertThat(fields).containsEntry("completionTokens", 56);
        assertThat(fields).containsEntry("thoughtsTokenCount", 185);
        assertThat(fields).containsEntry("cachedContentTokenCount", 4096);
        // tools_chars should be > 0 (tool has name + description + params)
        assertThat((Integer) fields.get("tools_chars")).isGreaterThan(0);
    }

    @Test
    @DisplayName("defaults purpose to MAIN when AgentLoopContext leaves it null")
    void nullPurposeDefaultsToMain() throws JsonProcessingException {
        AgentLoopContext context = AgentLoopContext.builder().provider("openai").build();
        CompletionRequest request = CompletionRequest.builder().model("gpt-4").build();

        LlmTurnInstrumentation.logTurn(context, request, UsageInfo.empty(), 1);

        Map<String, Object> fields = parseJson(appender.list.get(0).getFormattedMessage());
        assertThat(fields).containsEntry("purpose", "MAIN");
    }

    @Test
    @DisplayName("preserves CLASSIFY and GUARDRAIL purposes so telemetry can split the share")
    void classifyAndGuardrailPreserved() throws JsonProcessingException {
        for (CallPurpose p : new CallPurpose[]{CallPurpose.CLASSIFY, CallPurpose.GUARDRAIL}) {
            appender.list.clear();
            AgentLoopContext ctx = AgentLoopContext.builder().provider("openai").purpose(p).build();
            CompletionRequest req = CompletionRequest.builder().model("gpt-4").build();

            LlmTurnInstrumentation.logTurn(ctx, req, UsageInfo.empty(), 1);

            Map<String, Object> fields = parseJson(appender.list.get(0).getFormattedMessage());
            assertThat(fields).containsEntry("purpose", p.name());
        }
    }

    @Test
    @DisplayName("handles null usage (request-only log line) without throwing")
    void nullUsageIsSafe() throws JsonProcessingException {
        AgentLoopContext ctx = AgentLoopContext.builder().provider("openai").purpose(CallPurpose.MAIN).build();
        CompletionRequest req = CompletionRequest.builder().model("gpt-4").systemPrompt("x").build();

        LlmTurnInstrumentation.logTurn(ctx, req, null, 1);

        Map<String, Object> fields = parseJson(appender.list.get(0).getFormattedMessage());
        assertThat(fields).containsEntry("systemPrompt_chars", 1);
        assertThat(fields).doesNotContainKey("promptTokens");
    }

    private Map<String, Object> parseJson(String formatted) throws JsonProcessingException {
        String json = formatted.substring("[LLM_TURN] ".length());
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
