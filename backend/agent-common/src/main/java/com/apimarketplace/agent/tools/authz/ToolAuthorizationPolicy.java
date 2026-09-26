package com.apimarketplace.agent.tools.authz;

import com.apimarketplace.agent.tools.common.ToolParamUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Hand-curated source of truth for which tool actions require a synchronous
 * user authorization before they run in an interactive chat conversation.
 *
 * <p>This is the analogue of {@code BridgeAllowlist} for tool authorization:
 * a single, centralized, in-code list. When the chat agent calls one of these
 * {@code (tool, action)} pairs, the run pauses and an authorization card is
 * shown to the user (mirroring the credential-approval card). See
 * {@code ToolAuthorizationGuard} for the decision logic and the agent-service
 * call-site for enforcement.
 *
 * <p><b>Scope.</b> This list ONLY takes effect for interactive chat
 * conversations. Agents launched via workflow / task / sub-agent are exempt by
 * default - see {@code ToolAuthorizationScopeResolver} in agent-service.
 *
 * <p><b>How to add a rule.</b> Add the action to the {@link Set} of the right
 * tool below (one line). Keys are tool names and actions exactly as the facade
 * tools expose them (lowercase ASCII identifiers - do NOT introduce
 * {@code LabelNormalizer}, which is for workflow node slugs, not tool actions).
 * When the action alone does not say whether the call is sensitive - the same
 * action being ordinary or not depending on an argument - add a
 * {@code ConditionalRule} instead; see {@link #RULE_AGENT_SCHEDULE}.
 *
 * <p>Criterion for "sensitive": spends credit, acquires/installs a resource,
 * executes something external, or performs a notable state mutation.
 */
public final class ToolAuthorizationPolicy {

    private ToolAuthorizationPolicy() {}

    /**
     * Tool name &rarr; set of actions that require synchronous user authorization.
     *
     * <p>v1 (minimal, easily extensible):
     * <ul>
     *   <li>{@code application:acquire} - acquires a marketplace resource;</li>
     *   <li>{@code application:execute} - runs an application workflow (credit + side effects);</li>
     *   <li>{@code workflow:execute} - runs a saved/built workflow directly (same credit + side
     *       effects as application:execute, just by workflow id instead of publication id);</li>
     *   <li>{@code agent:execute} - launches a sub-agent (credit / LLM spend);</li>
     *   <li>{@code catalog:execute} / {@code catalog:call} - calls an external third-party API.</li>
     * </ul>
     * To extend: add an action to a set, or add a {@code Map.entry(tool, Set.of(...))}.
     */
    public static final Map<String, Set<String>> SENSITIVE_ACTIONS = Map.of(
            "application", Set.of("acquire", "execute"),
            // run a saved workflow by id - was UNGATED (bug); plus advancing a paused run
            // (continue an interface / resolve a user approval) mutates run state + can
            // unblock downstream side-effects, so it is gated the same way in chat.
            // stop_run is deliberately NOT here, and the trade-off is NOT free - read this
            // before extending the reasoning to anything else.
            // Every other entry gates something that STARTS work or lets it continue.
            // stop_run ends work, which is why waiting for a user card would defeat it: an
            // agent that cannot stop a runaway execution until a human clicks is not a
            // safety valve. The user's own Stop button is the same operation, ungated.
            // BUT its default mode does reach beyond the single run: mode='cancel' also
            // suspends the workflow's schedule rows, so an agent can leave a scheduled
            // workflow disarmed until someone reactivates it. That is mitigated by
            // disclosure only (tool help, mode param, and the stop response all say it,
            // and point at mode='graceful' for "end this execution only").
            // If agents are observed disarming workflows users wanted running, the fix is
            // to gate 'cancel' specifically or make 'graceful' the default - not to gate
            // the whole action, which would take the safety valve away.
            // restart_from_node belongs with execute rather than with stop_run: it STARTS work.
            // On an automatic run it re-executes the named node and everything downstream
            // unattended, with the same credit spend and the same external side effects as a
            // fresh fire - only the part upstream of the node is spared.
            // pin/unpin decide what PRODUCTION is. Pinning a version hands it every trigger
            // the plan declares - schedules, webhooks, chat and form endpoints all re-sync to
            // it and start firing on their own, with no further call from anyone. That is the
            // one action in this tool whose effects outlive the conversation, so it is gated
            // even though it runs nothing by itself.
            // unpin is gated too, and it is the one entry here that ENDS something rather
            // than starting it - which the stop_run paragraph above argues against. The
            // difference is what is racing: stop_run is a safety valve over an execution
            // happening right now, where waiting on a click defeats the action, while unpin
            // takes a workspace's automation off the air with nothing in flight. Asking costs
            // a click; not asking costs schedules nobody noticed had stopped.
            "workflow",    Set.of("execute", "continue_interface", "resolve_approval", "restart_from_node", "run_node",
                                  "pin", "unpin"),
            "agent",       Set.of("execute"),
            "catalog",     Set.of("execute", "call"),  // "call" is an alias of "execute"
            // Mail that LEAVES the account, or stops existing in it. Gated for the same reason
            // catalog:execute is: the effect lands outside the product, under the user's own
            // identity, and cannot be taken back. Sending in particular is irreversible in a way
            // even a paid API call is not, because the recipient is a person.
            //
            // The reads are deliberately absent. Listing a folder, naming its folders and
            // marking a message seen change nothing a user would want a click over, and gating
            // them would put a card in front of every inbox scan, which is the shape of gate
            // people learn to approve without reading. move and flag are the same: reversible,
            // inside the mailbox, and visible in it afterwards.
            "mailbox",     Set.of("send", "delete")
    );

    /**
     * Rule key for arming a recurring agent: {@code agent:create} or {@code agent:update}
     * carrying a cron.
     *
     * <p>ONE key for both actions on purpose. What the user authorizes is "let an agent run
     * itself on a schedule", not "let you call create" - and a "don't ask again" ticked on
     * the creation must cover the update that changes the same agent's cron, which a
     * per-action key would not.
     */
    public static final String RULE_AGENT_SCHEDULE = "agent:schedule";

    /** The argument that arms an agent's schedule, on create and on update alike. */
    public static final String PARAM_SCHEDULE_CRON = "schedule_cron";

    /**
     * Rule raised when a call turns an agent's tool-authorization requirement OFF.
     * Its own key, not {@link #RULE_AGENT_SCHEDULE}: the card names what is being
     * removed, and a person who armed an agent deliberately should read that.
     */
    public static final String RULE_AGENT_DISARM = "agent:disarm_tool_authorization";

    /** The parameter the condition above reads. */
    public static final String PARAM_REQUIRE_TOOL_AUTHORIZATION = "require_tool_authorization";

    /**
     * A rule that depends on the ARGUMENTS, not only on the action.
     *
     * @param tool    facade tool name, lowercase
     * @param action  action name, lowercase
     * @param when    tested against the call's arguments; true means "gate this call"
     * @param ruleKey the canonical rule this call raises when {@code when} matches
     */
    private record ConditionalRule(String tool, String action,
                                   Predicate<Map<String, Object>> when, String ruleKey) {}

    /**
     * Rules that the {@code (tool, action)} pair alone cannot express.
     *
     * <p>Why this second registry exists. {@code agent:create} is ordinary: naming a model and
     * a prompt does nothing until someone runs it. The same call carrying {@code schedule_cron}
     * is not ordinary at all - it arms an agent that will wake up on its own, spend credit and
     * reach external services with nobody watching, for as long as the cron runs. Putting
     * {@code create} in {@link #SENSITIVE_ACTIONS} would raise a card on every agent ever
     * created to catch the few that are armed; leaving it out lets the armed ones through.
     * Neither is the product rule, so the condition is expressed instead.
     *
     * <p>A BLANK cron is deliberately not here: on both actions that means "delete the
     * schedule", which disarms rather than arms. Same posture as {@code stop_run}.
     */
    private static final List<ConditionalRule> CONDITIONAL_RULES = List.of(
            new ConditionalRule("agent", "create",
                    ToolAuthorizationPolicy::armsAgentSchedule, RULE_AGENT_SCHEDULE),
            new ConditionalRule("agent", "update",
                    ToolAuthorizationPolicy::armsAgentSchedule, RULE_AGENT_SCHEDULE),
            // Turning the tool-authorization requirement OFF removes, permanently, the
            // question a person asked to be asked. Left ungated it is a one-call bypass of
            // the guard itself: an agent that is required to ask before publishing can
            // simply stop being required to. Turning it ON is not gated - tightening needs
            // no permission.
            new ConditionalRule("agent", "update",
                    ToolAuthorizationPolicy::disarmsToolAuthorization, RULE_AGENT_DISARM)
    );

    /**
     * True when the call carries a non-blank cron, which is what arms the schedule.
     *
     * <p><b>Reads the MERGED view, and that is the whole correctness of this rule.</b>
     * {@code AgentCrudModule} starts create and update with
     * {@link ToolParamUtils#mergeParams}, which flattens a nested {@code params} object into
     * the top level, and the agent tool's own help gives that nested form in every scheduled
     * -agent example it publishes. A guard reading only the top level would therefore answer
     * "no cron" for the shape models actually send, arm the schedule, and report success:
     * gate installed, tests green, nothing asked. The condition has to look exactly where the
     * module looks, so it calls the same function the module calls rather than reimplementing
     * the flattening.
     */
    /**
     * True when the call turns the tool-authorization requirement OFF.
     *
     * <p>Reads the MERGED view for the same reason as the schedule condition: the module
     * flattens a nested {@code params} object before looking, so a guard reading only the
     * top level would answer "not disarming" for the shape models actually send.
     */
    private static boolean disarmsToolAuthorization(Map<String, Object> arguments) {
        if (arguments == null) {
            return false;
        }
        Object value = ToolParamUtils.mergeParams(arguments).get(PARAM_REQUIRE_TOOL_AUTHORIZATION);
        return value != null && "false".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static boolean armsAgentSchedule(Map<String, Object> arguments) {
        if (arguments == null) {
            return false;
        }
        Object cron = ToolParamUtils.mergeParams(arguments).get(PARAM_SCHEDULE_CRON);
        return cron != null && !String.valueOf(cron).trim().isEmpty();
    }

    /**
     * The conditional rule this call raises, or {@code null} when no condition matches.
     *
     * @param toolName  facade tool name (any case)
     * @param action    resolved action (any case); {@code null} matches nothing here - an
     *                  unresolvable action is handled by the guard's fail-closed branch
     * @param arguments the call's arguments, which is what the conditions read
     */
    public static String conditionalRuleKey(String toolName, String action,
                                            Map<String, Object> arguments) {
        if (toolName == null || action == null) {
            return null;
        }
        String tool = toolName.toLowerCase(Locale.ROOT);
        String act = action.toLowerCase(Locale.ROOT);
        for (ConditionalRule rule : CONDITIONAL_RULES) {
            if (rule.tool().equals(tool) && rule.action().equals(act) && rule.when().test(arguments)) {
                return rule.ruleKey();
            }
        }
        return null;
    }

    /** True iff this tool has at least one argument-conditional rule. */
    public static boolean hasConditionalRules(String toolName) {
        if (toolName == null) {
            return false;
        }
        String tool = toolName.toLowerCase(Locale.ROOT);
        return CONDITIONAL_RULES.stream().anyMatch(rule -> rule.tool().equals(tool));
    }

    /** True iff this exact {@code (toolName, action)} pair is in the sensitive list. */
    public static boolean requires(String toolName, String action) {
        if (toolName == null || action == null) {
            return false;
        }
        Set<String> actions = SENSITIVE_ACTIONS.get(toolName.toLowerCase(Locale.ROOT));
        return actions != null && actions.contains(action.toLowerCase(Locale.ROOT));
    }

    /**
     * True iff this tool has at least one gated shape - an unconditional sensitive action OR
     * an argument-conditional rule. A tool that only ever gates conditionally still counts:
     * callers use this to decide whether the tool is worth looking at at all.
     */
    public static boolean isSensitiveTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        return SENSITIVE_ACTIONS.containsKey(toolName.toLowerCase(Locale.ROOT))
                || hasConditionalRules(toolName);
    }

    /**
     * The one rule whose approval does NOT mean "now run the tool".
     *
     * <p>{@code application:acquire} hands the job to the USER, who installs from the
     * marketplace modal in their own time; the agent must never acquire on their behalf. So
     * this rule raises a card but can never WAIT on one, and anything sizing a budget or a
     * timeout around "this call might be held" must exclude it - otherwise a hung install
     * backend stalls the chat for the length of a wait that was never going to happen.
     */
    public static boolean isUserPerformedRule(String toolName, String action) {
        return "application".equalsIgnoreCase(toolName) && "acquire".equalsIgnoreCase(action);
    }

    /**
     * True for the calls that can ask the user to CONNECT A SERVICE, which is a different
     * question from "may I run this" and gets a different card.
     *
     * <p>It matters because the two questions come apart. A granted rule raises no
     * authorization card and needs no budget to wait for one - but the same call can still
     * hit a service the user never connected, and THAT card comes from the tool result, not
     * from the grant. Without this exception it had nothing holding the call and silently
     * fell back to the two-turn flow. Not only for people who ticked "always allow":
     * approving one card writes a one-shot grant as well, so anyone whose park expired
     * arrived at the next turn already granted and lost the connect hold there too.
     *
     * <p>Deliberately the narrow set rather than "any sensitive call": the credential
     * pre-flight lives in the catalog execute path, and every pair added here buys a longer
     * ceiling on a hung backend for a card it may never raise.
     */
    public static boolean canRaiseConnectCard(String toolName, String action) {
        return "catalog".equalsIgnoreCase(toolName)
                && ("execute".equalsIgnoreCase(action) || "call".equalsIgnoreCase(action));
    }

    /**
     * Canonical rule key {@code "tool:action"} for a matching pair, else {@code null}.
     * This is the stable identifier used for approvals (transient and persisted)
     * and dedup - never use the LLM-generated {@code toolCallId}, which changes
     * across resume turns.
     */
    public static String ruleKey(String toolName, String action) {
        return requires(toolName, action)
                ? toolName.toLowerCase(Locale.ROOT) + ":" + action.toLowerCase(Locale.ROOT)
                : null;
    }
}
