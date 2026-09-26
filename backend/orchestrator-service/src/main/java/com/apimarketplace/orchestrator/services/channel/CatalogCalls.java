package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One catalog call on behalf of a chat-channel connector, pinned to the exact credential.
 *
 * <p>Shared by every provider's connector so the two rules that matter are written once. The
 * credential is pinned STRICTLY: without the strict marker the catalog softens an id it cannot
 * resolve into the integration's default credential, so a deleted or foreign credential would run
 * the call against a DIFFERENT account, and the row would name one bot while its messages went out
 * as another. And no scope key is sent, because connecting a channel is setup, not the work a
 * caller is paying for.
 */
@Component
public class CatalogCalls {

    private static final Logger logger = LoggerFactory.getLogger(CatalogCalls.class);

    private final ObjectProvider<ToolsGateway> toolsGatewayProvider;

    public CatalogCalls(ObjectProvider<ToolsGateway> toolsGatewayProvider) {
        this.toolsGatewayProvider = toolsGatewayProvider;
    }

    public Outcome<ExecutionResult> call(String channel, ToolRef tool, Map<String, Object> params,
                                         String tenantId, Long credentialId) {
        ToolsGateway gateway = toolsGatewayProvider.getIfAvailable();
        if (gateway == null) {
            return Outcome.failed("The catalog tools gateway is unavailable in this deployment.");
        }
        try {
            ExecutionResult result = gateway.executeTool(tool, params, tenantId, pinned(credentialId));
            if (!result.isSuccess()) {
                String message = result.getErrorMessage();
                return Outcome.failed(message != null && !message.isBlank()
                        ? message : capitalized(channel) + " refused the " + tool.toolId() + " call.");
            }
            return Outcome.of(result);
        } catch (Exception ex) {
            logger.warn("[chat-channel-{}] {} failed: {}", channel, tool.toolId(), ex.getMessage());
            return Outcome.failed(capitalized(channel) + " call failed: " + ex.getMessage());
        }
    }

    /** The credential, pinned strictly. See the class comment for why strict. */
    static Map<String, Object> pinned(Long credentialId) {
        return Map.of(
                "__credentialSource__", "user",
                "__selectedCredentialId__", credentialId,
                "__credentialSelectionStrict__", true);
    }

    /**
     * The list a provider answered with, wherever the projection put it.
     *
     * <p>A response whose body is an array (Discord's channel and server lists, for instance) has
     * no field name of its own, and the catalog carries it under a wrapper key. Rather than bet on
     * one key and read an empty list the day it is another, the value is accepted as a list
     * directly or under any of the wrapper keys in use.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listOf(Object value) {
        Object candidate = value;
        if (candidate instanceof Map<?, ?> map) {
            for (String key : List.of("data", "items", "result", "value")) {
                if (map.get(key) instanceof List<?>) {
                    candidate = map.get(key);
                    break;
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (candidate instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    private static String capitalized(String channel) {
        return channel == null || channel.isEmpty() ? "The provider"
                : Character.toUpperCase(channel.charAt(0)) + channel.substring(1);
    }
}
