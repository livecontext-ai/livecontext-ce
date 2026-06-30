package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Subscribes to agent execution result notifications via Redis pub/sub and
 * delegates to {@link AgentAsyncCompletionService} for delivery into the sync
 * persistence pipeline.
 *
 * <p>Agent-service workers publish results to {@code agent:result:channel:{correlationId}}
 * after completing a queued task. This subscriber listens on the pattern
 * {@code agent:result:channel:*} and reconstructs an {@link AgentResultMessage}
 * from the raw result JSON + channel name (which contains the correlationId).</p>
 *
 * <p>Activated only when {@code scaling.agent.queue.enabled=true}.</p>
 */
@Component
@ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")
public class AgentResultSubscriber implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(AgentResultSubscriber.class);

    /** Must match {@code AgentQueueWorkerService.RESULT_CHANNEL_PREFIX} in agent-service */
    private static final String RESULT_CHANNEL_PREFIX = "agent:result:channel:";

    private final RedisMessageListenerContainer listenerContainer;
    private final AgentAsyncCompletionService agentAsyncCompletionService;
    private final ObjectMapper objectMapper;

    public AgentResultSubscriber(
            RedisMessageListenerContainer listenerContainer,
            AgentAsyncCompletionService agentAsyncCompletionService,
            ObjectMapper objectMapper) {
        this.listenerContainer = listenerContainer;
        this.agentAsyncCompletionService = agentAsyncCompletionService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void subscribe() {
        listenerContainer.addMessageListener(this, new PatternTopic(RESULT_CHANNEL_PREFIX + "*"));
        logger.info("[AgentResultSubscriber] Subscribed to {}*", RESULT_CHANNEL_PREFIX);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(Message message, byte[] pattern) {
        try {
            // Extract correlationId from channel name: "agent:result:channel:{correlationId}"
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String correlationId = channel.substring(RESULT_CHANNEL_PREFIX.length());

            String json = new String(message.getBody(), StandardCharsets.UTF_8);

            // Agent-service publishes raw result JSON (not an AgentResultMessage envelope).
            // Reconstruct the envelope from channel name + raw JSON. The runId/nodeId/
            // agentType fields are looked up by AgentAsyncCompletionService from the
            // PendingAgentRegistry entry that AgentNode registered before yielding.
            // Boolean-success logic lives in AgentResultPayloadParser so this path and
            // the recovery path can never disagree on what "success" means.
            Map<String, Object> rawResult = objectMapper.readValue(json, Map.class);
            AgentResultMessage result = AgentResultPayloadParser.decode(
                correlationId, rawResult, null, null, null);

            logger.debug("[AgentResultSubscriber] Received result: correlationId={}, success={}",
                correlationId, result.success());

            agentAsyncCompletionService.onAgentResult(result);

        } catch (Exception e) {
            logger.error("[AgentResultSubscriber] Failed to process result message: {}",
                e.getMessage(), e);
        }
    }
}
