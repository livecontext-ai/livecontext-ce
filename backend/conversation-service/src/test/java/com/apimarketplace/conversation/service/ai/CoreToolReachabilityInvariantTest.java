package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool an agent cannot call is not a restricted tool, it is a tool that does not
 * exist, and nothing anywhere says so.
 *
 * <p>Three lists have to agree for a facade tool to reach an agent, and they live in
 * three modules: {@code BASE_CORE_TOOL_NAMES} here decides what conversation-service
 * even fetches, {@code AgentModuleResolver} decides what a given caller has enabled,
 * and {@code getCoreTools} keeps only the intersection. A name missing from any of
 * them is a whole feature that answers nothing, with a green build and no error: the
 * chat-channel tool shipped exactly that way and was caught by review, not by a test.
 */
@DisplayName("Core tool reachability - a registered tool must actually reach an agent")
class CoreToolReachabilityInvariantTest {

    @SuppressWarnings("unchecked")
    private static Set<String> baseCoreToolNames() throws Exception {
        Field field = CoreToolsProvider.class.getDeclaredField("BASE_CORE_TOOL_NAMES");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    @Test
    @DisplayName("every tool a prompt module advertises is one conversation-service fetches")
    void everyAdvertisedToolIsFetched() throws Exception {
        Set<String> fetched = baseCoreToolNames();
        Set<String> advertised = new TreeSet<>();
        DefaultSystemPrompts.ALL_RESOURCE_MODULES.forEach(module -> advertised.addAll(module.toolNames()));

        // The system prompt tells the agent the tool exists; this list decides whether it
        // is ever put in its hands. A name in the first and not the second is a tool the
        // agent is told to call and cannot.
        assertThat(fetched).containsAll(advertised);
    }

    @Test
    @DisplayName("every always-on module resolves to a tool that is fetched")
    void everyAlwaysOnModuleIsFetched() throws Exception {
        Set<String> fetched = baseCoreToolNames();

        // null toolsConfig = the default caller, which is what a plain chat and most
        // agents are. Whatever this yields must be callable.
        Set<String> enabled = AgentModuleResolver.resolveEnabledModules(null);

        assertThat(enabled).isNotEmpty();
        assertThat(fetched).containsAll(enabled);
    }

    @Test
    @DisplayName("the chat-channel tool is reachable, which is the only way a workspace gets a destination")
    void channelToolIsReachable() throws Exception {
        // Named explicitly, not just covered by the two rules above: connecting a chat is
        // the ONLY way a destination is ever created, so if this one is unreachable the
        // out-of-app delivery has nothing to resolve, for ever, in silence.
        assertThat(baseCoreToolNames()).contains("channel");
        assertThat(AgentModuleResolver.resolveEnabledModules(null)).contains("channel");
        assertThat(DefaultSystemPrompts.ALL_RESOURCE_MODULES)
                .anySatisfy(module -> assertThat(module.toolNames()).contains("channel"));
    }

    @Test
    @DisplayName("an agent with a restrictive tools config still keeps the always-on tools")
    void restrictiveConfigKeepsAlwaysOnTools() {
        // mode=custom with nothing granted is the tightest configuration a user can set.
        Set<String> enabled = AgentModuleResolver.resolveEnabledModules(
                java.util.Map.of("mode", "custom"));

        // These are registered, not unrestricted: each enforces its own scoping inside.
        assertThat(enabled).contains("wait", "ask_user", "channel");
    }
}
