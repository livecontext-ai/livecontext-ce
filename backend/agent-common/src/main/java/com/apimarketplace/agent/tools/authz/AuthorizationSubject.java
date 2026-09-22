package com.apimarketplace.agent.tools.authz;

import com.apimarketplace.agent.tools.common.ToolParamUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What an authorization card is ABOUT, extracted from the call that raised it.
 *
 * <p><b>Why a card needs this at all.</b> "Run this action?" is not a question anyone can
 * answer well. The user is being asked to put a workflow into production or to arm an agent
 * that will wake up on its own, and the only honest card names the thing: which workflow,
 * which version, how often. Everything here comes from the call's own arguments, so it
 * describes exactly what would run and never a guess.
 *
 * <p><b>One field, not six.</b> Every value on a card crosses seven hops to reach the screen
 * and survive a reload - the two card publishers (direct and CLI bridge), the pending-action
 * record that replays it, and four frontend types. Sending {@code workflowId},
 * {@code version}, {@code cron}… separately would pay that crossing once per value and again
 * for every rule added later. This map crosses once, opaquely, and a new rule fills it
 * without touching the transport.
 *
 * <p><b>What it deliberately does NOT carry: the workflow's NAME.</b> agent-service has no
 * orchestrator client (each service queries its own schema; there is no {@code workflow-client}
 * JAR), so the producer genuinely cannot resolve it. The card fetches it by {@code id}, the
 * same way it already fetches a publication for {@code application:acquire}. A card whose
 * fetch fails falls back to copy that names no workflow, never to an empty card.
 *
 * <p>Pure function on the arguments, free of agent-runtime types, so it lives beside the
 * policy it serves and is unit-testable without Spring.
 */
public final class AuthorizationSubject {

    private AuthorizationSubject() {}

    /** A workflow being pinned or unpinned. The card resolves its name from {@code id}. */
    public static final String KIND_WORKFLOW = "workflow";
    /** An agent being armed with a cron. */
    public static final String KIND_AGENT = "agent";

    /** Metadata key under which the subject travels, alongside {@code rule} / {@code action}. */
    public static final String METADATA_KEY = "subject";

    /**
     * Describes the call behind {@code rule}, or {@code null} when that rule has nothing worth
     * naming (every rule that predates this, which the card renders as it always has).
     *
     * @param rule      the canonical rule the guard matched
     * @param arguments the call's arguments; {@code null} yields {@code null}
     * @return an insertion-ordered map safe to serialize, or {@code null}
     */
    public static Map<String, Object> of(String rule, Map<String, Object> arguments) {
        if (rule == null || arguments == null) {
            return null;
        }
        return switch (rule.toLowerCase(Locale.ROOT)) {
            case "workflow:pin" -> workflowSubject(arguments, true);
            case "workflow:unpin" -> workflowSubject(arguments, false);
            case ToolAuthorizationPolicy.RULE_AGENT_SCHEDULE -> agentScheduleSubject(arguments);
            default -> null;
        };
    }

    /**
     * {@code {kind, id, version?}}. The id is what the card resolves the name from, so a call
     * without one yields no subject at all rather than a card promising a name it cannot show.
     */
    private static Map<String, Object> workflowSubject(Map<String, Object> arguments, boolean withVersion) {
        // Deliberately NOT merged, unlike the agent case: WorkflowCrudModule reads its
        // parameters directly, so a nested params object never reaches the pin either. Naming
        // a workflow the pin would not touch is worse than naming none.
        //
        // Both spellings are accepted by the tool itself (workflow_id, falling back to id),
        // so both have to be read here or the card loses its name on the second spelling.
        String id = firstNonBlank(arguments, "workflow_id", "id");
        if (id == null) {
            return null;
        }
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("kind", KIND_WORKFLOW);
        subject.put("id", id);
        if (withVersion) {
            // Resolved by the SAME function WorkflowCrudModule.executePin uses, so the card
            // can never name a version the pin would then refuse. It accepts a number or a
            // plain integer string and nothing else; "12.0", " 12 " and "latest" all make the
            // pin fail with "version is required", and a card that had named "version 12" for
            // any of them would have described a promotion that never happens.
            Integer version = ToolParamUtils.getIntParam(arguments, "version");
            // executePin applies one more check after that parse: a version <= 0 is refused
            // as well. Without the same bound the card announces "Version 0 takes over", the
            // user authorizes a promotion, and the pin then fails - the card would have
            // described something that never happens.
            if (version != null && version > 0) {
                subject.put("version", version);
            }
        }
        return subject;
    }

    /**
     * {@code {kind, cron, timezone?, id?, name?}}. The cron is the point of the card, so a
     * subject without one is not built; {@code id} (update) or {@code name} (create) is
     * whichever the call happens to carry, and neither is required.
     */
    private static Map<String, Object> agentScheduleSubject(Map<String, Object> rawArguments) {
        // The MERGED view, for the same reason the rule itself uses it: AgentCrudModule
        // flattens a nested `params` object before reading anything, and the agent tool's help
        // publishes that nested form in every scheduled-agent example. Reading the top level
        // only would leave the card with no cron to name on the common shape.
        Map<String, Object> arguments = ToolParamUtils.mergeParams(rawArguments);
        String cron = firstNonBlank(arguments, ToolAuthorizationPolicy.PARAM_SCHEDULE_CRON);
        if (cron == null) {
            return null;
        }
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("kind", KIND_AGENT);
        subject.put("cron", cron);
        String timezone = firstNonBlank(arguments, "schedule_timezone");
        // Mirror of what AgentCrudModule stores when the caller omits it, so the card states
        // the zone the schedule will actually fire in rather than leaving it to be assumed.
        subject.put("timezone", timezone != null ? timezone : "UTC");
        String id = firstNonBlank(arguments, "agent_id");
        if (id != null) {
            subject.put("id", id);
        }
        String name = firstNonBlank(arguments, "name");
        if (name != null) {
            subject.put("name", name);
        }
        return subject;
    }

    /** First argument among {@code keys} with a non-blank value, trimmed; else {@code null}. */
    private static String firstNonBlank(Map<String, Object> arguments, String... keys) {
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

}
