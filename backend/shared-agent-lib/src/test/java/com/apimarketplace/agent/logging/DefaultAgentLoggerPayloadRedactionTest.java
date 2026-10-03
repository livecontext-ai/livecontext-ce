package com.apimarketplace.agent.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-009 / LC-034: the agent run log wrote the user prompt, every tool argument (500 chars each)
 * and 5000 characters of every tool result at INFO. With payload logging off (the production
 * default) only names and sizes reach the log.
 */
@DisplayName("DefaultAgentLogger payload redaction")
class DefaultAgentLoggerPayloadRedactionTest {

    private static final String SECRET_PROMPT = "Summarise the email from ceo@acme.example about the wire";
    private static final String SECRET_QUERY = "from:ceo@acme.example subject:acquisition";
    private static final String SECRET_BODY = "the wire transfer of 45000 EUR is approved";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level previous;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(DefaultAgentLogger.class);
        previous = logger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previous);
    }

    private String captured() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("prompt, tool arguments and tool result content never reach the log")
    void payloadsRedacted() {
        DefaultAgentLogger agentLogger = new DefaultAgentLogger();
        ToolCall call = ToolCall.builder().id("c1").toolName("catalog").arguments(Map.of("q", SECRET_QUERY)).build();

        agentLogger.logExecutionStart("run-1", SECRET_PROMPT, "anthropic", "claude");
        agentLogger.logToolCallStart("run-1", call);
        agentLogger.logToolCallEnd("run-1", call, ToolResult.success(call, "{\"body\":\"" + SECRET_BODY + "\"}"), 12);

        String logged = captured();
        assertThat(logged).doesNotContain("ceo@acme.example").doesNotContain(SECRET_BODY).doesNotContain("45000");
        assertThat(logged).contains("catalog").contains("{q}(1 keys)").containsPattern("<\\d+ chars>");
    }
}
