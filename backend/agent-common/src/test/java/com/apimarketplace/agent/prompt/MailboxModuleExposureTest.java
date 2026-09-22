package com.apimarketplace.agent.prompt;

import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether an agent can see the mailbox tool at all, and under which configurations.
 *
 * <p>Registering a {@code ToolsProvider} does NOT expose its tool. Exposure is decided by two
 * lists this class pins, and a tool absent from either is built, registered, routed, and then
 * filtered out of every tool list with nothing logged: {@code DefaultSystemPrompts.build}
 * collects {@code coreToolNames} from {@link DefaultSystemPrompts#ALL_RESOURCE_MODULES}, and
 * every consumer keeps only tools in that set.
 *
 * <p>That is precisely how the first version of this tool shipped. Eight unit tests passed on
 * a provider no agent could ever call, because each one invoked it directly.
 */
class MailboxModuleExposureTest {

    @Test
    @DisplayName("the mailbox module is in the resource list the system prompt is built from")
    void moduleIsDeclared() {
        assertThat(DefaultSystemPrompts.ALL_RESOURCE_MODULES)
                .as("absent here, the tool is registered and then filtered out of every tool list")
                .contains(DefaultSystemPrompts.MAILBOX);
        assertThat(DefaultSystemPrompts.MAILBOX.toolNames()).containsExactly("mailbox");
    }

    /**
     * The prompt line is the only description an agent reads before deciding to call it, so it
     * has to carry the two facts that make the tool usable: the handle every action takes, and
     * that reading and sending are separate credentials.
     */
    @Test
    @DisplayName("the prompt line names the uid handle and the two separate credentials")
    void promptLineIsActionable() {
        String line = DefaultSystemPrompts.MAILBOX.promptSection();

        assertThat(line).contains("mailbox(action='read'").contains("uid");
        assertThat(line).contains("IMAP").contains("SMTP");
        assertThat(line)
                .as("an agent must know the refusal is the user's to resolve, and how to ask")
                .contains("credential(action='require'");
    }

    /**
     * OPT-IN, and the comparison that settles it is web_search: that one reads the public web,
     * this one reads someone's mail and can send from their address, to a person, with no undo.
     * Connecting an IMAP credential is therefore NOT enough for an agent to use it; someone has
     * to turn it on for that agent, which is the point.
     */
    @Test
    @DisplayName("an agent with no tools config does NOT get the mailbox: it is opt-in")
    void offByDefault() {
        assertThat(AgentModuleResolver.resolveEnabledModules(null))
                .as("a capability nobody enabled is one nobody has to reason about")
                .doesNotContain("mailbox")
                .as("the opt-OUT modules are unaffected")
                .contains("web_search", "files");

        assertThat(AgentModuleResolver.resolveEnabledModules(new HashMap<>()))
                .doesNotContain("mailbox");
    }

    @Test
    @DisplayName("mailbox=true turns it on, and that is the only thing that does")
    void enabledOnlyWhenAskedFor() {
        Map<String, Object> config = new HashMap<>();
        config.put("mailbox", true);

        assertThat(AgentModuleResolver.resolveEnabledModules(config)).contains("mailbox");

        config.put("mailbox", false);
        assertThat(AgentModuleResolver.resolveEnabledModules(config)).doesNotContain("mailbox");
    }

    /**
     * The richer shape is not decoration: {@code generation} is persisted this way, the
     * chat path hands the value through RAW, and a reader that understood only the boolean
     * would report an enabled agent as disabled. The frontend then SAVES that reading back,
     * so a half-supported shape does not merely display wrong, it erases the grant.
     */
    @Test
    @DisplayName("the {enabled:...} shape decides too, in both directions, like generation")
    void objectShapeIsHonoured() {
        Map<String, Object> on = new HashMap<>();
        on.put("mailbox", Map.of("enabled", true));
        assertThat(AgentModuleResolver.resolveEnabledModules(on)).contains("mailbox");

        Map<String, Object> off = new HashMap<>();
        off.put("mailbox", Map.of("enabled", false));
        assertThat(AgentModuleResolver.resolveEnabledModules(off)).doesNotContain("mailbox");

        // An object with no explicit flag counts as enabled: the same rule generation uses,
        // and the only reading under which a stored {quota: ...} does not silently revoke.
        Map<String, Object> bare = new HashMap<>();
        bare.put("mailbox", Map.of("quota", 10));
        assertThat(AgentModuleResolver.resolveEnabledModules(bare)).contains("mailbox");
    }

    /**
     * {@code mode=none} blocks the CATALOG, not everything that reaches outside: in that same
     * branch {@code web_search} and {@code generation} each keep their own toggle. Mailbox
     * follows them, so an agent explicitly given it keeps it, and one that was never given it
     * still does not have it. Reading mode=none as "no external reach at all" would be a rule
     * this resolver does not apply to the two modules that were already there.
     */
    @Test
    @DisplayName("mode=none blocks the catalog; the mailbox keeps its own toggle, like web_search")
    void modeNoneBlocksTheCatalogNotTheToggles() {
        Map<String, Object> off = new HashMap<>();
        off.put("mode", "none");
        assertThat(AgentModuleResolver.resolveEnabledModules(off))
                .as("never enabled, so still absent")
                .doesNotContain("mailbox")
                .doesNotContain("catalog");

        Map<String, Object> on = new HashMap<>();
        on.put("mode", "none");
        on.put("mailbox", true);
        assertThat(AgentModuleResolver.resolveEnabledModules(on))
                .as("explicitly enabled, and honoured exactly as webSearch is in this branch")
                .contains("mailbox")
                .doesNotContain("catalog");
    }

    @Test
    @DisplayName("mode=off leaves no modules at all, mailbox included")
    void withheldWhenEverythingIsOff() {
        Map<String, Object> config = new HashMap<>();
        config.put("mode", "off");

        assertThat(AgentModuleResolver.resolveEnabledModules(config)).isEmpty();
    }

    /**
     * The last link of the chain. Being in the module list makes the tool VISIBLE; a call on it
     * still has to reach the service that owns it, and only the orchestrator has the mail nodes.
     * The default is what carries it, so the assertion is here to catch a future entry in
     * {@code TOOL_OWNER} pointing the name somewhere that cannot execute it.
     */
    @Test
    @DisplayName("a mailbox call is routed to the orchestrator, where the mail nodes live")
    void routedToTheServiceThatOwnsTheNodes() {
        assertThat(com.apimarketplace.agent.tools.remote.ToolServiceTopology.serviceFor("mailbox"))
                .isEqualTo(com.apimarketplace.agent.tools.remote.ToolServiceTopology.ServiceKey.ORCHESTRATOR);
    }

    /**
     * Sending mail is irreversible in a way a paid API call is not, because the recipient is a
     * person; deleting removes something only the provider still holds. Both raise the same
     * authorization card {@code catalog:execute} raises, and for the same reason.
     */
    @Test
    @DisplayName("send and delete are gated; the reads and the reversible actions are not")
    void writesAreGated() {
        Set<String> sensitive = ToolAuthorizationPolicy.SENSITIVE_ACTIONS.get("mailbox");

        assertThat(sensitive).containsExactlyInAnyOrder("send", "delete");
        assertThat(sensitive)
                .as("gating an inbox scan teaches people to approve cards without reading them")
                .doesNotContain("read", "folders", "mark_read", "flag", "move");
    }
}
