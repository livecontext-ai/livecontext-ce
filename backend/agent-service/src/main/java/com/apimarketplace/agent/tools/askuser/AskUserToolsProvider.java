package com.apimarketplace.agent.tools.askuser;

import com.apimarketplace.agent.tools.ask.UserQuestion;
import com.apimarketplace.agent.tools.ask.UserQuestionAnswer;
import com.apimarketplace.agent.tools.ask.UserQuestionAnswerEnvelope;
import com.apimarketplace.agent.tools.ask.UserQuestionGateKeys;
import com.apimarketplace.agent.tools.ask.UserQuestionValidator;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.service.execution.ApprovalCardExtractor;
import com.apimarketplace.agent.service.execution.ChannelAuthorizationClient;
import com.apimarketplace.agent.service.execution.ApprovalCardPublisher;
import com.apimarketplace.agent.service.execution.ParkRequests;
import com.apimarketplace.agent.service.execution.ToolApprovalGate;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.arrayParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.generateInputSchema;

/**
 * The {@code ask_user} tool: put a multiple-choice question to the person in the chat and
 * wait for their pick.
 *
 * <p><b>How the wait works.</b> The call parks on {@link ToolApprovalGate}, the same hold
 * the authorization and connect-a-service cards use, after painting its card through
 * {@link ApprovalCardPublisher}. The person's answer travels back through the gate's
 * Redis key as a {@link UserQuestionAnswerEnvelope}. When they answer in time the real
 * answers are the tool result and the turn continues in place. When the park runs out of
 * time (the gate's 240 s budget by default on the direct route, half the run's inactivity
 * window when it carries one, so 150 s on a bridge session whose bridge declared its CLI's
 * wait, and the gate's 25 s floor on a bridge session that declared none) the tool returns
 * {@code pending_user}: a SUCCESS, never an error, whose text tells the agent to
 * end its turn with one sentence. The card stays on screen, and the person's later
 * submission starts the next turn as their own message. That fallback is the same
 * two-turn flow the approval cards had before the gate existed, so nothing here depends
 * on the park succeeding.
 *
 * <p><b>Scope.</b> {@link ToolAuthorizationScope#questionReach} decides the route. A run
 * somebody is watching gets the card. A schedule, a webhook or a task, which nobody watches
 * but whose conversation persists, puts the question in the workspace's connected chat and
 * answers {@code pending_user} with where it went, or {@code unavailable} when there is no
 * chat. A workflow node and a sub-agent get {@code unavailable} at once: an answer arriving
 * later would have nowhere to land.
 *
 * <p>The route is NOT decided by {@code isUserPromptable}, which this gate used until
 * 2026-09-22. A scheduled or webhook run carries a conversation AND a stream id, because the
 * sync path mints one unconditionally, so it passes {@code isUserPromptable} exactly like
 * somebody typing: such a run painted a card into a stream nobody reads, parked for the full
 * 240 s, and handed the agent "the card is on screen". {@code AgentContextBuilder} marks those
 * runs {@code __unattendedRun__}, said by the only code that knows, and {@code questionReach}
 * reads it.
 *
 * <p>Executed locally by {@code RemoteToolExecutionService}, which adds the call's own id
 * and start time to the credentials ({@link #KEY_TOOL_CALL_ID},
 * {@link #KEY_CALL_STARTED_EPOCH_MS}) because the provider contract carries neither.
 */
@Slf4j
@Component
public class AskUserToolsProvider implements ToolsProvider {

    public static final String TOOL_NAME = "ask_user";
    public static final String ACTION_ASK = "ask";
    public static final String ACTION_HELP = "help";

    /** Credential key under which the executing call's id arrives; it is the gate key's root. */
    public static final String KEY_TOOL_CALL_ID = "__toolCallId__";
    /** Credential key under which the executing call's start instant arrives (park ceilings). */
    public static final String KEY_CALL_STARTED_EPOCH_MS = "__callStartedEpochMs__";

    /** The gate key a question park uses for a given tool call; see {@link UserQuestionGateKeys}. */
    public static String gateKeyFor(String toolCallId) {
        return UserQuestionGateKeys.forToolCall(toolCallId);
    }

    /**
     * Reaches the person through a chat they read, for the runs where a card has nobody in
     * front of it.
     *
     * <p>Optional like the gate collaborators: without it a run that cannot be asked in the app
     * answers unavailable, which is exactly what it did before the channel existed.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChannelAuthorizationClient channelClient;

    /** Test seam: wire the channel without a Spring context. */
    void configureChannelClientForTest(ChannelAuthorizationClient client) {
        this.channelClient = client;
    }

    public static final String STATUS_ANSWERED = "answered";
    public static final String STATUS_PENDING_USER = "pending_user";
    public static final String STATUS_DISMISSED = "dismissed";
    public static final String STATUS_UNAVAILABLE = "unavailable";

    private static final List<String> VALID_ACTIONS = List.of(ACTION_ASK, ACTION_HELP);

    /**
     * Declared timeout. The loop grants the park budget on top of it for a promptable call
     * (see {@code AgentLoopExecutor.effectiveTimeoutMs}); this base is what remains for the
     * tool itself, which does nothing but read an envelope once released.
     */
    static final long TIMEOUT_MS = 30_000L;

    private final ToolApprovalGate approvalGate;
    private final ApprovalCardPublisher cardPublisher;

    public AskUserToolsProvider(@Autowired(required = false) ToolApprovalGate approvalGate,
                                @Autowired(required = false) ApprovalCardPublisher cardPublisher) {
        this.approvalGate = approvalGate;
        this.cardPublisher = cardPublisher;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.UTILITY;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters,
                                       ToolExecutionContext context) {
        if (!TOOL_NAME.equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }
        String action = parameters == null ? null : String.valueOf(parameters.getOrDefault("action", ""));
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }
        return switch (action) {
            case ACTION_HELP -> ToolExecutionResult.success(buildHelpPayload());
            case ACTION_ASK -> ask(parameters, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
        };
    }

    // ==================== ask ====================

    private ToolExecutionResult ask(Map<String, Object> parameters, ToolExecutionContext context) {
        List<UserQuestion> questions;
        try {
            questions = UserQuestionValidator.parseQuestions(parameters.get("questions"));
        } catch (UserQuestionValidator.InvalidQuestionsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        Map<String, Object> credentials = context != null && context.credentials() != null
                ? context.credentials() : Map.of();
        ToolAuthorizationScope.QuestionReach reach = ToolAuthorizationScope.questionReach(credentials);
        if (reach == ToolAuthorizationScope.QuestionReach.CHANNEL) {
            return askOnTheChannel(questions, credentials, context);
        }
        if (reach != ToolAuthorizationScope.QuestionReach.IN_APP) {
            return ToolExecutionResult.success(unreachable(credentials));
        }

        String toolCallId = stringOf(credentials.get(KEY_TOOL_CALL_ID));
        String streamId = ParkRequests.streamIdOf(credentials);
        String conversationId = ParkRequests.conversationIdOf(credentials);
        if (toolCallId == null || streamId == null) {
            // Without the call's id no card can be keyed, and without a stream none can be
            // shown: every consumer that would paint or persist the card requires both. The
            // stream half is belt and braces (the check above already required one, since an
            // execution with no stream is never promptable and so never gets past it); the id
            // half is real, since only the local dispatch supplies it.
            // Saying "the card is on screen" here would leave the agent waiting for an
            // answer that can never come.
            log.warn("ask_user has no tool call id or stream to raise a card on - answering unavailable");
            return ToolExecutionResult.success(unavailable());
        }
        Map<String, Object> question = questionPayload(toolCallId, questions);

        if (approvalGate == null || cardPublisher == null || !approvalGate.isEnabled()) {
            // Nothing can hold the call. The question is still a real one, so it goes out as a
            // non-blocking card painted by the result consumer, and the answer starts a new turn.
            log.info("ask_user cannot park (gate unavailable) - card will be raised non-blocking");
            return pendingUser(question, false);
        }

        long callStarted = longOf(credentials.get(KEY_CALL_STARTED_EPOCH_MS), System.currentTimeMillis());
        String gateKey = gateKeyFor(toolCallId);
        ToolApprovalGate.ParkRequest park = ParkRequests.of(credentials, gateKey, callStarted);
        if (!approvalGate.beginPark(park)) {
            return pendingUser(question, false);
        }
        String buffered = cardPublisher.publishUserQuestion(streamId, conversationId, question, true, gateKey);
        if (buffered == null) {
            // The card never left. Holding would be minutes behind a spinner with nothing to
            // click; let the consumer paint a non-blocking card instead.
            approvalGate.abandonPark(conversationId, gateKey);
            return pendingUser(question, false);
        }

        ToolApprovalGate.Answer answer = approvalGate.awaitAnswer(park);
        ToolApprovalGate.Decision decision = answer.decision();
        if (decision == ToolApprovalGate.Decision.APPROVED
                || decision == ToolApprovalGate.Decision.DENIED
                || decision == ToolApprovalGate.Decision.STOPPED) {
            // Only a SETTLED card leaves the replay buffer. An unanswered one is still the
            // person's to deal with after a reload.
            cardPublisher.unbuffer(streamId, buffered);
        }
        return switch (decision) {
            case APPROVED -> answered(questions, answer.payloadJson());
            case DENIED -> dismissed("denied",
                    "The person chose not to answer. Continue without it, or ask in plain text if you "
                            + "truly need it. Do not call ask_user again for the same question.");
            case STOPPED -> dismissed("stopped",
                    "The person stopped this turn. The question is closed. Finish your turn.");
            case EXPIRED, UNAVAILABLE -> pendingUser(question, true);
        };
    }

    private ToolExecutionResult answered(List<UserQuestion> questions, String payloadJson) {
        var parsed = UserQuestionAnswerEnvelope.parse(payloadJson);
        if (parsed.isEmpty() || !parsed.get().answered()) {
            // The gate only reports APPROVED for an envelope it parsed as answered, so this is
            // defensive. Never invent an answer: treat an unreadable one as dismissed.
            return dismissed("denied", "The answer could not be read. Ask again in plain text.");
        }
        List<UserQuestionAnswer> answers = parsed.get().answers();
        String validation = null;
        try {
            answers = UserQuestionValidator.parseAnswers(
                    answers.stream().map(UserQuestionAnswer::toMap).toList(), questions);
        } catch (UserQuestionValidator.InvalidQuestionsException e) {
            // The submission is real but does not line up with the options exactly (a stale
            // card, a client drift). Hand the agent what the person said, and say why it looks off.
            validation = e.getMessage();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", STATUS_ANSWERED);
        out.put("answers", answers.stream().map(UserQuestionAnswer::toMap).toList());
        out.put("message", "The person answered. Continue with their choices; each answer carries the "
                + "question's header, the option labels they picked (selected) and anything they typed (freeText).");
        if (validation != null) {
            out.put("validation", validation);
        }
        return ToolExecutionResult.success(out);
    }

    /**
     * The question is still open: on screen, unanswered. A SUCCESS on purpose. An error
     * invites the model to retry, which would paint a second card for the same question.
     *
     * @param cardEmitted true when the gate already painted the (blocking) card, so the
     *                    result consumers must not paint another; false when nobody has
     *                    painted one yet and the consumer should raise a non-blocking card
     */
    private ToolExecutionResult pendingUser(Map<String, Object> question, boolean cardEmitted) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", STATUS_PENDING_USER);
        out.put("toolCallId", question.get("toolCallId"));
        out.put("message", "The question is on the person's screen and has no answer yet. Do not ask it "
                + "again and do not call ask_user again for it. Finish your reply with one short sentence "
                + "saying you are waiting for their choice, then stop: their answer arrives as their next message.");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ApprovalCardExtractor.USER_QUESTION_REQUESTED_KEY, true);
        metadata.put(ApprovalCardExtractor.USER_QUESTION_KEY, question);
        if (cardEmitted) {
            metadata.put(ToolApprovalGate.META_CARD_EMITTED, true);
        }
        return ToolExecutionResult.success(out, metadata);
    }

    /**
     * Put the question in the chat the person reads, for a run nobody is watching.
     *
     * <p>No card is painted here. A card needs a stream, an unattended run's stream has no
     * subscriber, and painting one anyway is exactly the behaviour this replaced: four minutes
     * parked against a screen nobody was looking at.
     *
     * <p>The call is NOT parked either, even when a stream id happens to exist. An answer from
     * a phone can arrive hours later, far past any gate budget, and it arrives as the person's
     * next message in the conversation, which starts the following turn. Holding the call would
     * only delay this turn's ending without changing when the answer lands.
     */
    private ToolExecutionResult askOnTheChannel(List<UserQuestion> questions,
                                                Map<String, Object> credentials,
                                                ToolExecutionContext context) {
        if (channelClient == null) {
            // Nothing wired to reach anybody. Same answer as a context that cannot ask at all,
            // because from the agent's side that is exactly what this is.
            return ToolExecutionResult.success(unavailable());
        }
        String toolCallId = stringOf(credentials.get(KEY_TOOL_CALL_ID));
        String conversationId = ParkRequests.conversationIdOf(credentials);
        if (toolCallId == null) {
            // Only the local dispatch supplies it, and without it no answer could ever be
            // matched back to this call.
            log.warn("ask_user has no tool call id to key a channel question on - answering unavailable");
            return ToolExecutionResult.success(unavailable());
        }
        ChannelAuthorizationClient.Delivery delivery = channelClient.askQuestions(
                context != null ? context.tenantId() : null,
                context != null ? context.orgId() : null,
                conversationId, toolCallId, gateKeyFor(toolCallId),
                stringOf(credentials.get(ToolAuthorizationScope.KEY_AGENT_ID)), questions);

        return switch (delivery.status()) {
            case SENT -> pendingOnChannel(delivery, false);
            case ALREADY_PENDING -> pendingOnChannel(delivery, true);
            case NO_CHANNEL -> ToolExecutionResult.success(noChannel());
            case FAILED -> ToolExecutionResult.success(channelFailed());
        };
    }

    /**
     * The question is in front of somebody, somewhere the agent cannot see.
     *
     * <p>Carries WHERE, in both halves: the app to go and look in, and which conversation there,
     * because a workspace whose bot sits in three rooms has three of those. Without it the agent
     * says "I asked on telegram" to somebody who then has to guess where.
     */
    private ToolExecutionResult pendingOnChannel(ChannelAuthorizationClient.Delivery delivery,
                                                 boolean alreadyAsked) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", STATUS_PENDING_USER);
        out.put("via", delivery.channel());
        out.put("destination", delivery.chatLabel());
        if (alreadyAsked) {
            out.put("reason", "already_asked");
            out.put("message", "You ALREADY asked the user exactly this on " + placeOf(delivery)
                    + (delivery.requestedAt() != null ? " (sent " + delivery.requestedAt() + ")" : "")
                    + " and they have not answered yet. No new message was sent. Do NOT ask again: "
                    + "while that question is waiting, repeating it is refused as a duplicate. Say in "
                    + "one sentence that you are still waiting, then finish your turn.");
        } else {
            out.put("message", "Your question was sent to the user on " + placeOf(delivery)
                    + " and has no answer yet. Do NOT ask it again and do NOT call ask_user again "
                    + "for it. Say in one sentence that you are waiting for their answer, then finish "
                    + "your turn: their answer arrives as their next message, and the run continues "
                    + "from there.");
        }
        // Deliberately no card metadata: nothing was painted, and claiming otherwise would stop
        // the result consumer painting the one card that could still be seen if somebody opened
        // the conversation.
        return ToolExecutionResult.success(out);
    }

    private Map<String, Object> noChannel() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", STATUS_UNAVAILABLE);
        out.put("reason", "no_channel");
        out.put("message", "Nobody is watching this run and this workspace has no connected chat to "
                + "ask in, so the question cannot be put to anyone. Decide with the information you "
                + "have and state the assumption you made in your reply. If you write a summary, say "
                + "that connecting a chat would have let you ask instead of assuming.");
        return out;
    }

    private Map<String, Object> channelFailed() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", STATUS_UNAVAILABLE);
        out.put("reason", "channel_failed");
        out.put("message", "The question could not be delivered to the user's chat, so nobody was "
                + "asked. Decide with the information you have and state the assumption you made in "
                + "your reply.");
        return out;
    }

    /** "telegram (Ops room)" when the destination has a name, "telegram" when it does not. */
    private static String placeOf(ChannelAuthorizationClient.Delivery delivery) {
        String channel = delivery.channel() != null ? delivery.channel() : "the connected chat";
        return delivery.chatLabel() != null && !delivery.chatLabel().isBlank()
                ? channel + " (" + delivery.chatLabel() + ")" : channel;
    }

    private ToolExecutionResult dismissed(String decision, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", STATUS_DISMISSED);
        out.put("message", message);
        Map<String, Object> metadata = new HashMap<>();
        // Settled: the card is gone and must not come back on the next page load.
        metadata.put(ToolApprovalGate.META_CARD_EMITTED, true);
        metadata.put(ToolApprovalGate.META_DECISION, decision.toLowerCase(Locale.ROOT));
        return ToolExecutionResult.success(out, metadata);
    }

    /**
     * Why this run cannot put a question to the person, said so the agent does the one useful thing
     * left. A sub-agent is being waited on by the agent that started it, which CAN ask: the question
     * has to travel back in the reply. A workflow step cannot be re-entered by a late answer: the
     * person decides through a User Approval step, which the agent can only point out.
     */
    static Map<String, Object> unreachable(Map<String, Object> credentials) {
        if (ToolAuthorizationScope.agentDepth(credentials) >= 1) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", STATUS_UNAVAILABLE);
            out.put("reason", "asked_by_caller");
            out.put("message", "You were started by another agent, which is waiting for your reply: nobody "
                    + "reads this conversation, so a question cannot be shown here. End your reply with the "
                    + "question written out, with its options if it has any, and say it is for the person: "
                    + "the agent that started you will put it to them and can run you again with the answer.");
            return out;
        }
        if (ToolAuthorizationScope.isWorkflowRun(credentials)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", STATUS_UNAVAILABLE);
            out.put("reason", "workflow_step");
            out.put("message", "You run as a step of a workflow: an answer could not come back to this step "
                    + "once it has finished, so a question cannot be asked from here. Decide with the "
                    + "information you have and state the assumption in your output. If the person must "
                    + "decide, say so in your output: the workflow needs a User Approval step for it, which "
                    + "asks them (in the app, or on their chat channel) and waits for the answer.");
            return out;
        }
        return unavailable();
    }

    private static Map<String, Object> unavailable() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", STATUS_UNAVAILABLE);
        out.put("reason", "no_live_chat");
        out.put("message", "Nobody is watching this conversation (it runs unattended), so a question "
                + "cannot be shown. Decide with the information you have and state the assumption you made "
                + "in your reply, or finish with the question written out in plain text.");
        return out;
    }

    /** The channel-agnostic payload: what the card (or a future channel) renders. */
    static Map<String, Object> questionPayload(String toolCallId, List<UserQuestion> questions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolCallId);
        payload.put("questions", questions.stream().map(UserQuestion::toMap).toList());
        return payload;
    }

    private static String stringOf(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static long longOf(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    // ==================== Tool definition & help ====================

    private AgentToolDefinition buildUnifiedTool() {
        var params = List.of(
                ToolParameter.builder()
                        .name("action")
                        .type("string")
                        .description("ask | help")
                        .required(true)
                        .enumValues(VALID_ACTIONS)
                        .build(),
                arrayParam("questions",
                        "1 to " + UserQuestionValidator.MAX_QUESTIONS + " questions (ask). Each: "
                                + "{header: short unique label (max " + UserQuestionValidator.MAX_HEADER_LENGTH
                                + " chars, how you read the answer back), question: the full text, "
                                + "options: " + UserQuestionValidator.MIN_OPTIONS + " to " + UserQuestionValidator.MAX_OPTIONS
                                + " of {label, description}, multiSelect: boolean (default false)}. "
                                + "The person can always type their own answer, so never add an 'Other' option.",
                        false, "object")
        );

        String description = "Ask the person a multiple-choice question and wait for their answer.\n"
                + "- ask: returns status 'answered' with answers[] ({header, selected[], freeText, custom}), "
                + "or 'pending_user' when the question is in front of them but not answered yet (then finish "
                + "your turn in one sentence and stop; the answer arrives as their next message), or "
                + "'dismissed', or 'unavailable' when there is nobody to ask.\n"
                + "- In a run nobody is watching, the question goes to the workspace's connected chat instead "
                + "of the screen, and the result says where (via, destination). With no connected chat it "
                + "answers unavailable: decide with what you have and state your assumption.\n"
                + "- Use it when a choice changes what you do next and guessing would waste work. Not for a "
                + "yes/no you can infer, and never twice for the same question.";

        return AgentToolDefinition.builder()
                .name(TOOL_NAME)
                .description(description)
                .category(ToolCategory.UTILITY)
                .parameters(params)
                .requiredParameters(List.of("action"))
                .inputSchema(generateInputSchema(params, List.of("action")))
                .helpText("Call ask_user(action='help') for the question shape, limits and examples.")
                .requiresAuth(false)
                .tags(List.of("ask", "question", "user", "choice", "form", "clarify"))
                .timeoutMs(TIMEOUT_MS)
                .build();
    }

    private Map<String, Object> buildHelpPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
                "ASK_USER - put a multiple-choice question to the person and read their pick back. One call "
                        + "asks all your questions and every answer comes back together in one result. Where the "
                        + "question appears depends on the run: on their screen when somebody is watching, and in "
                        + "the workspace's connected chat when nobody is, so a question from a scheduled run can "
                        + "still be answered from a phone.");

        Map<String, Object> actions = new LinkedHashMap<>();
        Map<String, Object> ask = new LinkedHashMap<>();
        ask.put("summary", "Put the question to the person and wait for the answer.");
        ask.put("params", Map.of(
                "questions", "required - list of 1 to " + UserQuestionValidator.MAX_QUESTIONS + " questions, see question_shape"));
        ask.put("returns", Map.of(
                "answered", "{status:'answered', answers:[{header, selected:[labels], freeText, custom}]}. "
                        + "selected is always a list, even for a single choice. custom=true means the person typed "
                        + "freeText instead of picking. A 'validation' field, when present, explains why an answer "
                        + "does not line up with the options you gave; still use what the person said.",
                "pending_user", "{status:'pending_user', toolCallId} on screen, or {status:'pending_user', via, "
                        + "destination} when it went to their chat, where via is the chat service and destination "
                        + "is the room. reason:'already_asked' means this exact question is still waiting there "
                        + "from an earlier run and nothing new was sent. In every case: do NOT call ask_user again "
                        + "for it. End your reply with one short sentence saying you are waiting, then stop. Their "
                        + "answer arrives as their next message, with each header and the chosen labels.",
                "dismissed", "{status:'dismissed'}. They chose not to answer, or stopped the turn. Continue "
                        + "without it or ask in plain text; never ask the same question again.",
                "unavailable", "{status:'unavailable', reason, message}. 'asked_by_caller': you were started "
                        + "by another agent, which is waiting for your reply; end your reply with the question "
                        + "written out (with its options) and say it is for the person: that agent will ask them. "
                        + "'workflow_step': you run as a workflow step and an answer could not come back to it; "
                        + "decide with what you have, state your assumption, and if the person must decide, say "
                        + "in your output that the workflow needs a User Approval step. 'no_live_chat': nobody "
                        + "to ask and no way for an answer to come back. 'no_channel': nobody is watching and the "
                        + "workspace has no chat connected. 'channel_failed': the chat refused the message. For "
                        + "these last three: decide with what you have and state your assumption in your reply."));
        actions.put("ask", ask);
        actions.put("help", Map.of("summary", "This payload. No params."));
        out.put("actions", actions);

        out.put("question_shape", Map.of(
                "header", "Short unique label naming the topic (max " + UserQuestionValidator.MAX_HEADER_LENGTH
                        + " chars). It is echoed on every answer, so make it distinct.",
                "question", "The full question, one or two sentences.",
                "options", UserQuestionValidator.MIN_OPTIONS + " to " + UserQuestionValidator.MAX_OPTIONS
                        + " of {label, description}. label is what they pick and what you read back; description "
                        + "is one line on what choosing it means. Put your recommended option first.",
                "multiSelect", "true to let them pick several options (default false)."));

        out.put("when_to_use", List.of(
                "A decision changes the work you are about to do (which target, which tone, which of two approaches).",
                "You need a preference you cannot infer from the conversation.",
                "NOT for confirmations you can reasonably assume, and NOT to ask the same thing twice: one card, then act on the answer."));

        out.put("constraints", List.of(
                "At most " + UserQuestionValidator.MAX_QUESTIONS + " questions per call; ask the most important now and the rest later.",
                "Never add an 'Other' option: the card always offers a free-text answer.",
                "Headers must be unique within a call.",
                "The call may return before the person answers (pending_user). That is normal: close your turn and wait."));

        out.put("examples", List.of(
                Map.of("action", "ask",
                        "questions", List.of(Map.of(
                                "header", "Tone",
                                "question", "Which tone should the newsletter use?",
                                "options", List.of(
                                        Map.of("label", "Friendly", "description", "Warm, first person, short sentences"),
                                        Map.of("label", "Formal", "description", "Neutral and precise")),
                                "multiSelect", false)),
                        "comment", "One question, single choice."),
                Map.of("action", "ask",
                        "questions", List.of(
                                Map.of("header", "Channels", "question", "Where should the post go?",
                                        "options", List.of(Map.of("label", "LinkedIn"), Map.of("label", "X"), Map.of("label", "Newsletter")),
                                        "multiSelect", true),
                                Map.of("header", "Length", "question", "How long should it be?",
                                        "options", List.of(Map.of("label", "Short"), Map.of("label", "Medium"), Map.of("label", "Long")))),
                        "comment", "Two questions on one card, the first multi-select.")));

        return out;
    }
}
