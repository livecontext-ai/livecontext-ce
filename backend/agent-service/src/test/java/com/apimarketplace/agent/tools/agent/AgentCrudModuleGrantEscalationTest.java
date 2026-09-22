package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.config.AgentModuleResolver;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A capability travels DOWN, never up.
 *
 * <p>The {@code agent} tool can create and update agents, and this change gave it a
 * {@code mailbox} parameter so the tool is configurable without the UI. Ungated, that is a
 * way around the switch entirely: an agent that was never given a mailbox creates a
 * sub-agent that has one, runs it, and the access decision a person made becomes one an
 * agent made for itself. For the tenant this was found on, the mailbox behind that switch
 * is a real address someone reads.
 *
 * <p>{@code generation} is here for the same reason and is NOT new: it spends the account's
 * credits, and it has been passable this way since it existed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCrudModule - an agent cannot hand out a capability it does not hold")
class AgentCrudModuleGrantEscalationTest {

    private static final String TENANT = "121";

    @Mock private AgentService agentService;
    @Mock private SkillService skillService;
    @Mock private com.apimarketplace.agent.webhook.AgentWebhookTokenService webhookTokenService;
    @Mock private ModelCatalogService modelCatalogService;
    @Mock private org.springframework.web.client.RestTemplate restTemplate;

    private AgentCrudModule module;

    @BeforeEach
    void setUp() {
        lenient().when(modelCatalogService.isModelAvailable(anyString(), anyString())).thenReturn(true);
        lenient().when(modelCatalogService.getEffectiveDefaultModel()).thenReturn("claude-sonnet-4-6");
        lenient().when(modelCatalogService.getEffectiveDefaultProvider()).thenReturn("anthropic");
        module = new AgentCrudModule(agentService, skillService, webhookTokenService,
                new com.apimarketplace.agent.config.AgentDefaultsConfig(), modelCatalogService,
                restTemplate, "http://localhost:8091", "http://localhost:8080");
    }

    /**
     * An AGENT caller whose enabled modules are stated. The {@code __agentId__} matters:
     * escalation is a thing an agent does, and a caller without one is a person in a chat,
     * who is granting a right rather than passing one on.
     */
    private static ToolExecutionContext callerWithout(String... modules) {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of(modules));
        credentials.put("__agentId__", "agent-7");
        return new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null);
    }

    private static Map<String, Object> createParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("name", "Triage");
        p.put("system_prompt", "read the inbox");
        return p;
    }

    @Test
    @DisplayName("create: a caller without the mailbox cannot give one to the agent it creates")
    void refusesToGrantAMailboxItDoesNotHold() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);

        ToolExecutionResult result = module.execute("create", p, TENANT,
                callerWithout("table", "workflow")).orElseThrow();

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error())
                .as("the refusal has to say whose decision it is, or the agent retries a "
                        + "different way instead of asking the person")
                .contains("do not have it yourself")
                .contains("ask the user");
        // Stronger than naming one method: nothing at all was written.
        verifyNoInteractions(agentService);
    }

    @Test
    @DisplayName("update: the same, since an update is the other way to write the same field")
    void refusesOnUpdateToo() {
        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", java.util.UUID.randomUUID().toString());
        p.put("mailbox", true);

        ToolExecutionResult result = module.execute("update", p, TENANT,
                callerWithout("table")).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(agentService);
    }

    /**
     * The direction that must keep working. A mail agent delegating to a sub-agent is the
     * whole point of the parameter; refusing that would make the escalation guard a ban.
     */
    @Test
    @DisplayName("a caller that HAS the mailbox may pass it on")
    void allowsWhenTheCallerHoldsIt() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);

        ToolExecutionResult result = module.execute("create", p, TENANT,
                callerWithout("mailbox", "table")).orElseThrow();

        assertThat(result.errorCode())
                .as("it may fail further down on a mocked service, but never on permission")
                .isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("turning the mailbox OFF is never an escalation, whoever asks")
    void switchingItOffIsAlwaysAllowed() {
        Map<String, Object> p = createParams();
        p.put("mailbox", false);

        ToolExecutionResult result = module.execute("create", p, TENANT,
                callerWithout("table")).orElseThrow();

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * Silence is not a denial. A caller with no module list is every path that has no bound
     * agent at all, a schedule-fired workflow among them, plus everything that predates the
     * key. Reading it as refusal would break work that was always allowed, invisibly.
     */
    @Test
    @DisplayName("a caller whose capabilities are unstated is not refused")
    void unstatedCapabilitiesAreNotARefusal() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);

        // An AGENT, so the early return on 'no bound agent' is not what makes this pass:
        // what makes it pass is that its capabilities are unstated.
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__agentId__", "agent-7");
        ToolExecutionResult result = module.execute("create", p, TENANT,
                new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null))
                .orElseThrow();

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("generation is gated the same way, which closes it for the first time")
    void generationIsGatedToo() {
        Map<String, Object> p = createParams();
        p.put("generation", true);

        ToolExecutionResult result = module.execute("create", p, TENANT,
                callerWithout("table")).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("generation");
    }
    /**
     * The caller every product has by default: a plain general chat. Its enabled modules are
     * NO_CONFIG_MODULES, which deliberately excludes both opt-ins, so a guard that reads them
     * as "does not hold it" refuses a person who is fully entitled to grant it, and who can
     * do exactly that two clicks away in the agent modal.
     */
    @Test
    @DisplayName("a plain chat asked to build a generating agent is NOT an escalation")
    void plainChatMayStillCreateAGeneratingAgent() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY,
                List.copyOf(AgentModuleResolver.NO_CONFIG_MODULES));
        Map<String, Object> p = createParams();
        p.put("generation", true);

        ToolExecutionResult result = module.execute("create", p, TENANT,
                new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null))
                .orElseThrow();

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * The hole the module list cannot see. A read-only mail agent holds the module, so it
     * passes the check above, and could hand a sub-agent the write mode it was itself denied.
     * Read-only is the one restriction no approval wildcard can lift, so leaving this open
     * would make the guard that matters most the easiest one to walk around.
     */
    @Test
    @DisplayName("a read-only mail agent cannot create a sub-agent that may SEND")
    void refusesToWidenTheAccessMode() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of("mailbox"));
        credentials.put("__agentId__", "agent-7");
        credentials.put("__mailboxAccessMode__", "read");
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null))
                .orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("read-only");
        verifyNoInteractions(agentService);
    }

    @Test
    @DisplayName("the same agent may still pass on the read-only mode it holds")
    void allowsPassingOnTheSameMode() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of("mailbox"));
        credentials.put("__agentId__", "agent-7");
        credentials.put("__mailboxAccessMode__", "read");
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "read");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null))
                .orElseThrow();

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("an agent with FULL mailbox access may still grant full access")
    void allowsWidenFromAFullAccessCaller() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                callerWithout("mailbox")).orElseThrow();

        assertThat(result.errorCode())
                .as("its own mode is unstated, which means full access, not a denial")
                .isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /** A read-only mail agent, stated under the in-process spelling. */
    private static ToolExecutionContext readOnlyMailAgent() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of("mailbox"));
        credentials.put("__agentId__", "agent-7");
        credentials.put("__mailboxAccessMode__", "read");
        return new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null);
    }

    /**
     * The bypass hiding inside the previous fix. Refusing only an explicit "write" left the
     * quieter route wide open: say NOTHING about the mode, and the sub-agent is created with
     * no mode at all, which reads as full access everywhere. A read-only agent could hand over
     * the sending right it was itself denied by omitting a parameter.
     */
    @Test
    @DisplayName("a read-only mail agent cannot grant a mailbox by SAYING NOTHING about the mode")
    void refusesAGrantThatOmitsTheMode() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);

        ToolExecutionResult result = module.execute("create", p, TENANT, readOnlyMailAgent()).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error())
                .as("the refusal has to name the omission, or the agent retries the same call")
                .contains("without mailbox_access_mode, which means FULL access");
        verifyNoInteractions(agentService);
    }

    /**
     * The narrower route the same caller must keep: changing ONLY the mode on an agent that
     * already has a mailbox. It carries no mailbox=true, so a guard keyed on the grant alone
     * would miss it.
     */
    @Test
    @DisplayName("an update that widens only the mode is refused too")
    void refusesAModeOnlyUpdate() {
        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", java.util.UUID.randomUUID().toString());
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("update", p, TENANT, readOnlyMailAgent()).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(agentService);
    }

    /**
     * And the direction that must stay open: an update touching neither the grant nor the mode
     * is not an escalation, so a read-only mail agent can still rename or re-prompt a sub-agent.
     */
    @Test
    @DisplayName("an update that touches neither the grant nor the mode is untouched by the guard")
    void leavesUnrelatedUpdatesAlone() {
        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", java.util.UUID.randomUUID().toString());
        p.put("name", "Renamed");

        ToolExecutionResult result = module.execute("update", p, TENANT, readOnlyMailAgent()).orElseThrow();

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("the caller mode is read under the plain spelling too, not only the namespaced one")
    void honoursBothSpellingsOfTheCallerMode() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of("mailbox"));
        credentials.put("__agentId__", "agent-7");
        credentials.put("mailboxAccessMode", "read");
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null))
                .orElseThrow();

        assertThat(result.errorCode())
                .as("a gate honouring one spelling is enforced on one route and open on the other")
                .isEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * The bypass my own previous fix opened, and which a test of mine then blessed.
     *
     * <p>I had reasoned that an update MERGES, so saying nothing about the mode keeps the
     * stored one and widens nothing. True only when a mode IS stored. An update that turns
     * the mailbox on for the FIRST time has none to keep, normalizeToolsConfig backfills
     * nothing for this key, and the row lands with a grant and no mode, which reads as full
     * access. Create without the mailbox, then update with it: two calls doing what one was
     * refused.
     */
    @Test
    @DisplayName("the two-step route is refused too: create bare, then update the mailbox on")
    void refusesTheTwoStepGrant() {
        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", java.util.UUID.randomUUID().toString());
        p.put("mailbox", true);

        ToolExecutionResult result = module.execute("update", p, TENANT, readOnlyMailAgent()).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error()).contains("without mailbox_access_mode, which means FULL access");
        verifyNoInteractions(agentService);
    }

    /**
     * The enforcer compares the mode trimmed and case-insensitively, so this guard has to as
     * well. Reading " READ " as "not read-only" would let the caller count as unrestricted and
     * pass on a right the tool would then deny it: a disagreement in the permissive direction.
     */
    @Test
    @DisplayName("a padded, upper-case read mode is still read-only, as the enforcer reads it")
    void readsTheModeTheWayTheEnforcerDoes() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of("mailbox"));
        credentials.put("__agentId__", "agent-7");
        credentials.put("__mailboxAccessMode__", "  READ ");
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "WRITE");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null))
                .orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * When the two spellings disagree, the guard reads the one the ENFORCER reads.
     *
     * <p>They do disagree, in a state this feature created: a sub-agent inherits its parent's
     * namespaced mode and then has its OWN plain mode written over it. ToolAccessControl reads
     * plain, so such a child genuinely may send, and a guard that called it read-only would
     * refuse it from passing on a right the platform grants it. A guard that disagrees with
     * the gate it protects teaches people to distrust one of the two.
     */
    @Test
    @DisplayName("the plain spelling decides, exactly as ToolAccessControl decides")
    void readsTheSameSpellingTheEnforcerReads() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of("mailbox"));
        credentials.put("__agentId__", "agent-7");
        credentials.put("mailboxAccessMode", "write");
        credentials.put("__mailboxAccessMode__", "read");
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null))
                .orElseThrow();

        assertThat(result.errorCode())
                .as("this caller may send, so it may say so for the agent it creates")
                .isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("and the namespaced one still decides when it is the only one there")
    void fallsBackToTheNamespacedSpelling() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of("mailbox"));
        credentials.put("__agentId__", "agent-7");
        credentials.put("__mailboxAccessMode__", "read");
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null))
                .orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("a null context is not a refusal: it is a caller this guard knows nothing about")
    void nullContextIsNotARefusal() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);

        ToolExecutionResult result = module.execute("create", p, TENANT, null).orElseThrow();

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * A refusal changed nothing, so it must not spend one of the three consecutive updates.
     * Without the refund, three refused calls make the FOURTH answer "the configuration is
     * COMPLETE" to an agent that has not succeeded once, which is both false and the worst
     * possible moment to tell it to stop trying.
     */
    @Test
    @DisplayName("a refused update hands its rate-limit slot back, so a refusal is not a strike")
    void aRefusedUpdateDoesNotSpendASlot() {
        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", java.util.UUID.randomUUID().toString());
        p.put("mailbox", true);

        for (int attempt = 1; attempt <= 5; attempt++) {
            ToolExecutionResult result = module.execute("update", p, TENANT, readOnlyMailAgent()).orElseThrow();
            assertThat(result.errorCode())
                    .as("attempt %d must still be the escalation refusal, never a rate-limit STOP", attempt)
                    .isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("mailbox");
        }
    }

    /**
     * Two different mistakes deserve two sentences. Telling a caller that asked for 'write'
     * that "a mailbox without a mode means FULL access" is noise about a rule it did not
     * break, and noise in a refusal is what teaches an agent to skim the next one.
     */
    @Test
    @DisplayName("the refusal names the mistake actually made, not both of them")
    void theRefusalNamesTheRightMistake() {
        Map<String, Object> explicit = createParams();
        explicit.put("mailbox", true);
        explicit.put("mailbox_access_mode", "write");
        String explicitError = module.execute("create", explicit, TENANT, readOnlyMailAgent())
                .orElseThrow().error();

        Map<String, Object> omitted = createParams();
        omitted.put("mailbox", true);
        String omittedError = module.execute("create", omitted, TENANT, readOnlyMailAgent())
                .orElseThrow().error();

        assertThat(explicitError).contains("you asked for").doesNotContain("without mailbox_access_mode");
        assertThat(omittedError).contains("without mailbox_access_mode");
    }

    /**
     * The string form is what a model actually sends, and getBooleanParam accepts it. A guard
     * reading only a real Boolean would wave through the exact spelling most likely to arrive.
     */
    @Test
    @DisplayName("mailbox sent as the STRING \"true\" is still a grant, not an unset parameter")
    void honoursTheStringFormOfTheGrant() {
        Map<String, Object> p = createParams();
        p.put("mailbox", "true");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                callerWithout("table")).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * A value outside the two the enum names is denied at enforcement, so it is not an
     * escalation. It is worse in a quieter way: the agent is persisted with a mailbox mode
     * nothing accepts, every call fails, and no one is told why. Refused at the door.
     */
    @Test
    @DisplayName("a mode that is neither read nor write widens, because it is not read")
    void refusesAModeOutsideTheTwo() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "full");

        ToolExecutionResult result = module.execute("create", p, TENANT, readOnlyMailAgent()).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(agentService);
    }

    /**
     * The sibling refusal, and the refund that goes with it. Both were changed in the same
     * breath as the escalation one and neither was covered: neutralising the decrement left
     * every other test green. Five attempts, because the limiter allows three.
     */
    @Test
    @DisplayName("an unusable boolean is refused every time, and never spends an update slot")
    void anUnusableBooleanIsRefusedAndRefunded() {
        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", java.util.UUID.randomUUID().toString());
        p.put("mailbox", "perhaps");

        for (int attempt = 1; attempt <= 5; attempt++) {
            ToolExecutionResult result = module.execute("update", p, TENANT,
                    callerWithout("mailbox")).orElseThrow();
            assertThat(result.errorCode())
                    .as("attempt %d must still name the bad value, never a rate-limit STOP", attempt)
                    .isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(result.error()).contains("must be true or false").contains("perhaps");
        }
        verifyNoInteractions(agentService);
    }

    /**
     * The generic refusal on the UPDATE path. The create twin is covered; this one asserted
     * only the code, so a message that stopped naming the capability would go unnoticed.
     */
    @Test
    @DisplayName("the update refusal names the capability and whose decision it is")
    void theUpdateRefusalIsAsInformativeAsTheCreateOne() {
        Map<String, Object> p = new HashMap<>();
        p.put("agent_id", java.util.UUID.randomUUID().toString());
        p.put("generation", true);

        ToolExecutionResult result = module.execute("update", p, TENANT,
                callerWithout("table")).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.error())
                .contains("generation")
                .contains("do not have it yourself")
                .contains("ask the user");
    }

    /**
     * Taking a capability AWAY is the one direction that never needed a guard. A revoking
     * call widens nothing whatever mode rides along with it, since the mode of a mailbox
     * nobody has is inert, and refusing it would leave a read-only agent unable to tidy up
     * after itself.
     */
    @Test
    @DisplayName("revoking a mailbox is allowed even when a write mode rides along with it")
    void revokingIsNeverAWidening() {
        Map<String, Object> p = createParams();
        p.put("mailbox", false);
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("create", p, TENANT, readOnlyMailAgent()).orElseThrow();

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * The refusal quotes what the caller actually sent. Telling an agent that asked for
     * 'full' that it "asked for write" describes a call it did not make, and a refusal that
     * misreports the input is one an agent argues with instead of fixing.
     */
    @Test
    @DisplayName("the refusal quotes the value sent, not a value it assumes")
    void theRefusalQuotesTheValueActuallySent() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "full");

        String error = module.execute("create", p, TENANT, readOnlyMailAgent()).orElseThrow().error();

        assertThat(error).contains("you asked for 'full'");
        assertThat(error).doesNotContain("asked for 'write'");
    }

    /** An agent driven by a workflow, identified by its run rather than by an entity id. */
    private static ToolExecutionContext workflowDrivenAgent(String key, String value, String... modules) {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put(AgentModuleResolver.ENABLED_MODULES_CREDENTIAL_KEY, List.of(modules));
        credentials.put(key, value);
        return new ToolExecutionContext(TENANT, credentials, Map.of(), null, null, null, null, null);
    }

    /**
     * The caller both halves of this feature were written for, and which each half let through
     * because of the other.
     *
     * <p>An Agent node configured INLINE in a workflow has no agent entity, so AgentNode stamps
     * no {@code __agentId__}. It is nonetheless an agent: no human is typing, its modules
     * exclude the mailbox, and it holds the {@code agent} tool by default. Exempting it meant a
     * workflow could mint itself a sending mail agent and run it, while the credential mirror
     * added for that very caller carried the proof it should be refused.
     */
    @Test
    @DisplayName("an INLINE workflow agent node is an agent, even with no agent id to show")
    void anInlineWorkflowAgentNodeIsStillAnAgent() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                workflowDrivenAgent("__workflowRunId__", "run-9", "agent", "table")).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(agentService);
    }

    @Test
    @DisplayName("the hosting node id alone is enough, for the same reason")
    void aWorkflowNodeIdIsEnoughToCountAsAnAgent() {
        Map<String, Object> p = createParams();
        p.put("generation", true);

        ToolExecutionResult result = module.execute("create", p, TENANT,
                workflowDrivenAgent("__workflowNodeId__", "agent:triage", "agent")).orElseThrow();

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    /**
     * And the exemption still covers the caller it was written for: a person in a direct chat,
     * with no agent bound and no workflow behind them. Narrowing an exemption must not turn
     * into refusing everyone.
     */
    @Test
    @DisplayName("a direct chat, with no agent and no workflow, is still a person")
    void aDirectChatIsStillExempt() {
        Map<String, Object> p = createParams();
        p.put("mailbox", true);
        p.put("mailbox_access_mode", "write");

        ToolExecutionResult result = module.execute("create", p, TENANT,
                workflowDrivenAgent("conversationId", "conv-1",
                        AgentModuleResolver.NO_CONFIG_MODULES.toArray(new String[0]))).orElseThrow();

        assertThat(result.errorCode()).isNotEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }
}
