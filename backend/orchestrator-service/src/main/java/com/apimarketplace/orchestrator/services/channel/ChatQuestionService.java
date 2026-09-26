package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.tools.ask.UserQuestion;
import com.apimarketplace.agent.tools.ask.UserQuestionOption;
import com.apimarketplace.agent.tools.ask.UserQuestionValidator;
import com.apimarketplace.agent.tools.authz.AuthorizationAsk;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestKind;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChoiceOption;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Putting an agent's question to the person, in a chat they read.
 *
 * <p>The sibling of {@link AgentAuthorizationChannelService}, which asks permission. Everything
 * underneath is the same and is deliberately not duplicated: the same address book, the same
 * request table, the same claim, the same deadline, the same sweep, the same allow-list. What
 * differs is only what the person is shown and what comes back, which is exactly the amount of
 * code that lives here.
 *
 * <p><b>Why this is a sibling and not a rename of that one.</b> The two share storage and differ
 * in orchestration: an approval has two fixed buttons, one press, one verdict; a question has a
 * variable keyboard, possibly several presses, several messages that must all be answered before
 * anything is applied, and an answer that is a value rather than a decision. Folding both into
 * one class would put every one of those branches behind a kind check in a method that already
 * carries the hardest concurrency in this feature.
 *
 * <p><b>One call, several messages, one answer.</b> {@code ask_user} asks up to a handful of
 * questions and owes the agent a single envelope. Each question becomes its own message, because
 * one message with three keyboards has no way to say which button belongs to which question, and
 * the rows share a {@code group_key} so the last one answered can tell that the set is complete.
 */
@Service
public class ChatQuestionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatQuestionService.class);

    /** The rule stored on a question row, so an approval and a question never share a fingerprint. */
    public static final String QUESTION_RULE = "ask_user";

    /** Free-text escape, offered on every question: the person is never boxed into the options. */
    static final String OTHER_LABEL = "Other...";
    /** Submit, on a multi-select only: a single-select is submitted by the pick itself. */
    static final String DONE_LABEL = "Done";
    /** Prefix on a chosen option of a multi-select. */
    static final String PICKED_PREFIX = "[x] ";
    /**
     * The longest "Answered: ..." line. Inside the connector's verdict reserve (128), so the line
     * fits in the room the capped body left for it.
     */
    static final int ANSWERED_LINE_MAX_CHARS = 120;
    /** Rows read per retry pass; a group appears once whatever its size. */
    static final int RETRY_PER_PASS = 100;
    /** Closing line on questions of a set that could not be sent in full. */
    static final String INCOMPLETE_LINE =
            "Not all of these questions could be sent, so this one was withdrawn. The agent will ask again.";

    private final ChatChannelService channelService;
    private final ChatAuthorizationRequestRepository requestRepository;
    private final ChatChannelConnectorRegistry connectors;
    private final ChatChannelLinkRepository linkRepository;
    private final ChatQuestionAnswerApplier answerApplier;
    private final ChatChannelBotRepository botRepository;
    private final ObjectProvider<AgentClient> agentClientProvider;
    private final int ttlHours;

    /** Product analytics; optional so a unit test can build the service without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    public ChatQuestionService(ChatChannelService channelService,
                               ChatAuthorizationRequestRepository requestRepository,
                               ChatChannelConnectorRegistry connectors,
                               ChatChannelLinkRepository linkRepository,
                               ChatQuestionAnswerApplier answerApplier,
                               ChatChannelBotRepository botRepository,
                               ObjectProvider<AgentClient> agentClientProvider,
                               @Value("${orchestrator.channel.question.ttl-hours:6}") int ttlHours) {
        this.channelService = channelService;
        this.requestRepository = requestRepository;
        this.connectors = connectors;
        this.linkRepository = linkRepository;
        this.answerApplier = answerApplier;
        this.botRepository = botRepository;
        this.agentClientProvider = agentClientProvider;
        this.ttlHours = ttlHours;
    }

    /** What the delivery achieved, in the terms the caller has to describe it to an agent. */
    public enum DeliveryStatus { SENT, ALREADY_PENDING, NO_CHANNEL, FAILED }

    /**
     * @param chatLabel where it went, as the person would name it, so the agent can say it back
     */
    public record DeliveryResult(DeliveryStatus status, String channel, String chatLabel,
                                 Instant requestedAt, String error) {

        static DeliveryResult of(DeliveryStatus status) {
            return new DeliveryResult(status, null, null, null, null);
        }
    }

    /**
     * A question to be asked, as it arrives from the agent side.
     *
     * @param conversationId the thread a later reply continues, which is why a run without one
     *                       never reaches here (see {@code ToolAuthorizationScope.questionReach})
     * @param gateKey        the SAME key the in-app card uses, so the answer endpoint accepts it
     */
    public record QuestionRequest(String tenantId, String organizationId, String conversationId,
                                  String toolCallId, String gateKey, String agentId,
                                  List<UserQuestion> questions) {}

    /**
     * Send every question of one call, or none of them.
     *
     * <p>All or nothing on the FIRST message: if the first cannot be delivered there is no
     * reason to believe the second can, and a half-asked call leaves the person with questions
     * that can never complete an envelope. A failure after the first is different and is kept:
     * the rows already sent stay live and the sweep expires them, because they are messages a
     * real person can already see, and deleting the record of a message that exists is how a
     * later press finds nothing and is told its request was never made.
     */
    public DeliveryResult deliverQuestions(QuestionRequest request) {
        DeliveryResult result = doDeliverQuestions(request);
        if (analytics != null && request != null && result != null) {
            analytics.channelRequestDelivered(request.tenantId(), request.organizationId(),
                    EngagementAnalyticsEmitter.RequestType.ASK_USER, result.channel(),
                    EngagementAnalyticsEmitter.RequestStatus.of(result.status()));
        }
        return result;
    }

    private DeliveryResult doDeliverQuestions(QuestionRequest request) {
        if (request == null || isBlank(request.tenantId()) || isBlank(request.organizationId())
                || isBlank(request.conversationId()) || isBlank(request.toolCallId())
                || isBlank(request.gateKey()) || request.questions() == null
                || request.questions().isEmpty()) {
            // FAILED, not NO_CHANNEL. The agent is told something different for each: "connect
            // a chat" for NO_CHANNEL, which would send somebody to fix a setup that is fine,
            // about what is really a caller that sent an incomplete request.
            return new DeliveryResult(DeliveryStatus.FAILED, null, null, null,
                    "The question request was incomplete.");
        }
        // Same destination rule as a permission request: the agent's own, else the default.
        AgentChannelChoice.Choice choice = AgentChannelChoice.lookup(agentClientProvider,
                request.tenantId(), request.organizationId(), request.agentId());
        if (choice.unknown()) {
            return new DeliveryResult(DeliveryStatus.FAILED, null, null, null, AgentChannelChoice.UNKNOWN_DESTINATION);
        }
        if (choice.switchedOff()) {
            return DeliveryResult.of(DeliveryStatus.NO_CHANNEL);
        }
        Optional<ResolvedTarget> targetOpt = channelService.resolveFor(request.organizationId(), choice.linkId());
        if (targetOpt.isEmpty()) {
            return DeliveryResult.of(DeliveryStatus.NO_CHANNEL);
        }
        ResolvedTarget target = targetOpt.get();
        Optional<ChatChannelConnector> connectorOpt = connectors.forChannel(target.channel());
        if (connectorOpt.isEmpty()) {
            return DeliveryResult.of(DeliveryStatus.NO_CHANNEL);
        }
        ChatChannelConnector connector = connectorOpt.get();

        // The same duplicate rule an approval gets, for the same reason: a nightly agent asking
        // the identical question every night must not stack identical messages nobody can tell
        // apart. Fingerprinted on the questions themselves, so a different question goes through.
        String fingerprint = AuthorizationAsk.fingerprint(QUESTION_RULE, materialOf(request.questions()));
        // Every question of the call, not only the first. Each question's row carries its own
        // fingerprint (see sendOne), and the live index is unique per (conversation, fingerprint),
        // so an earlier call of the same set can still hold ANY of them: its first question
        // answered and its second still waiting is an ordinary state, and checking only the
        // first let the new call through to collide on the second.
        Optional<ChatAuthorizationRequestEntity> live = liveTwin(request.conversationId(),
                fingerprint, request.questions().size());
        if (live.isPresent()) {
            if (!isPastDeadline(live.get(), Instant.now())) {
                // The earlier set is still waiting on the person and can still complete: an
                // answer to its open question hands the whole set over.
                return new DeliveryResult(DeliveryStatus.ALREADY_PENDING, target.channel(),
                        chatLabel(target), live.get().getCreatedAt(), null);
            }
            // Past its deadline and still SENT, because the sweep runs on a timer. The whole
            // earlier set is retired, not the one row found: its rows share one deadline, and
            // any of them left SENT holds its fingerprint and makes the fresh copy of that
            // question deliver and then fail to record.
            if (!retireOverdueGroup(live.get())) {
                return new DeliveryResult(DeliveryStatus.ALREADY_PENDING, target.channel(),
                        chatLabel(target), live.get().getCreatedAt(), null);
            }
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttlHours, ChronoUnit.HOURS);
        // Minted here, NOT the provider's tool call id. Gemini's non-streaming path numbers its
        // calls call_0, call_1 (GeminiProvider), so that id repeats every turn and in every
        // workspace. Grouping on it would let last night's answered rows join tonight's group,
        // completing it instantly with stale answers, and could pull another tenant's row in
        // entirely. The group key's only job is "the rows of THIS delivery", so it is generated.
        String deliveryId = UUID.randomUUID().toString();
        List<ChatAuthorizationRequestEntity> sent = new ArrayList<>();
        for (int index = 0; index < request.questions().size(); index++) {
            UserQuestion question = request.questions().get(index);
            Outcome<String> delivered = sendOne(connector, target, request, question, index,
                    fingerprint, deliveryId, expiresAt, sent);
            if (!delivered.ok()) {
                if (!sent.isEmpty()) {
                    // The set cannot be completed any more: the group holds only the rows that
                    // recorded, so answering those would hand the agent an envelope missing a
                    // question, and half an answer to a set of questions is not an answer. The
                    // ones already in front of the person are closed saying so, and the agent is
                    // told nothing was asked, which is true of the set.
                    logger.warn("[chat-question] question {} of {} failed for call {}, abandoning the "
                            + "set: {}", index + 1, request.questions().size(), request.toolCallId(),
                            delivered.error());
                    for (ChatAuthorizationRequestEntity partial : sent) {
                        retire(partial, INCOMPLETE_LINE);
                    }
                }
                return new DeliveryResult(DeliveryStatus.FAILED, target.channel(), chatLabel(target),
                        null, delivered.error());
            }
        }
        return new DeliveryResult(DeliveryStatus.SENT, target.channel(), chatLabel(target), now, null);
    }

    /**
     * Expire a question whose deadline has passed, so the next ask is not refused as a duplicate.
     *
     * <p>Reports failure rather than throwing: losing a race with the sweep itself is normal,
     * and the caller's fallback (report the old question as pending) is the safe one. The
     * message edit comes after the status for the same reason the approval path puts it there:
     * the row is what the duplicate rule reads, the edit is cosmetic.
     */
    private boolean retireOverdueGroup(ChatAuthorizationRequestEntity found) {
        List<ChatAuthorizationRequestEntity> siblings = isBlank(found.getGroupKey())
                ? List.of(found)
                : requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc(
                        found.getConversationId(), found.getGroupKey());
        boolean retiredAll = true;
        for (ChatAuthorizationRequestEntity row : siblings) {
            if (row.getStatus() == RequestStatus.SENT) {
                // The same line the sweep would have written. A retired question that keeps its
                // buttons invites a press on a question nobody can answer any more.
                retiredAll &= retire(row, AgentAuthorizationChannelService.Verdict.EXPIRED.line());
            }
        }
        return retiredAll;
    }

    /**
     * Mark one question EXPIRED and take its buttons away with the given line.
     *
     * <p>Status first, message second, as on the approval path: the row is what the duplicate
     * rule reads, and the edit is cosmetic. Reports failure rather than throwing, because losing
     * a race with the sweep is normal.
     */
    private boolean retire(ChatAuthorizationRequestEntity row, String line) {
        try {
            row.setStatus(RequestStatus.EXPIRED);
            requestRepository.save(row);
        } catch (Exception ex) {
            logger.info("[chat-question] could not retire question {}: {}", row.getId(), ex.getMessage());
            return false;
        }
        closeWith(row, line);
        return true;
    }

    /** A live row holding any of this call's per-question fingerprints, the first one found. */
    private Optional<ChatAuthorizationRequestEntity> liveTwin(String conversationId, String fingerprint,
                                                              int questionCount) {
        for (int index = 0; index < questionCount; index++) {
            Optional<ChatAuthorizationRequestEntity> live = requestRepository
                    .findByConversationIdAndFingerprintAndStatus(conversationId,
                            fingerprintAt(fingerprint, index), RequestStatus.SENT);
            if (live.isPresent()) {
                return live;
            }
        }
        return Optional.empty();
    }

    /**
     * The fingerprint of question {@code index} of a call.
     *
     * <p>Distinct per question because the live index is unique per (conversation, fingerprint)
     * and the questions of one call share a conversation. The first keeps the plain digest, so
     * the same CALL asked again is recognised as a repeat.
     */
    static String fingerprintAt(String fingerprint, int index) {
        return index == 0 ? fingerprint : fingerprint + ":" + index;
    }

    /**
     * Send one question, then record it.
     *
     * <p>Send first, then write, which is the order the approval path already uses and the one
     * this follows rather than inventing a second. It has a known race, and the race is the
     * cheaper one: a press landing before the row is written finds nothing and is told so,
     * which is what {@code Verdict.UNRECORDED} exists to say. Writing first instead would
     * remove that race and add a worse one, a row whose send failed sitting live under the
     * duplicate index, silently refusing the next run's identical question for the whole TTL.
     */
    private Outcome<String> sendOne(ChatChannelConnector connector, ResolvedTarget target,
                                    QuestionRequest request, UserQuestion question, int index,
                                    String fingerprint, String deliveryId, Instant expiresAt,
                                    List<ChatAuthorizationRequestEntity> sent) {
        String token = CallbackTokens.newToken();
        // Capped HERE, to what this connector accepts, so the row stores the body that was
        // really sent: the close reuses it, and a body kept uncapped would close a message
        // nobody ever read.
        String body = capToConnector(connector, bodyOf(question));

        // Under the request's workspace: an org-shared bot credential does not resolve without
        // the binding, and this thread carries no org header of its own. The holder is because
        // the scope helper takes a Runnable, not a Supplier.
        List<Outcome<String>> sentHolder = new ArrayList<>(1);
        TenantResolver.runWithOrgScope(request.organizationId(), () ->
                sentHolder.add(connector.sendChoiceRequest(request.tenantId(), target.credentialId(),
                        target.chatId(), body, optionsOf(question, token, List.of(),
                                connector.capabilities().acceptsTypedReplies()))));
        Outcome<String> delivered = sentHolder.isEmpty()
                ? Outcome.<String>failed("The send did not run.") : sentHolder.get(0);
        if (!delivered.ok()) {
            return delivered;
        }

        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setTenantId(request.tenantId());
        row.setOrganizationId(request.organizationId());
        row.setLinkId(target.linkId());
        row.setChannel(target.channel());
        row.setCredentialId(target.credentialId());
        row.setChatId(target.chatId());
        row.setCallbackToken(token);
        row.setConversationId(request.conversationId());
        row.setGateKey(request.gateKey());
        row.setRule(QUESTION_RULE);
        row.setAgentId(parseUuidOrNull(request.agentId()));
        row.setKind(RequestKind.CHOICE);
        row.setGroupKey(deliveryId);
        row.setPayload(question.toMap());
        row.setFingerprint(fingerprintAt(fingerprint, index));
        row.setExpiresAt(expiresAt);
        row.setMessageText(body);
        row.setMessageId(delivered.value());
        row.setStatus(RequestStatus.SENT);
        try {
            sent.add(requestRepository.save(row));
        } catch (DataIntegrityViolationException ex) {
            // The message is in front of the person and nothing can resolve a press on it, so
            // its buttons are taken away saying why, as the approval path does. Left live, a
            // press finds no row and is not even acknowledged: the button spins, then nothing.
            logger.warn("[chat-question] question {} of call {} was delivered but could not be "
                            + "recorded: {}", index + 1, request.toolCallId(), ex.getMessage());
            closeWith(row, AgentAuthorizationChannelService.Verdict.UNRECORDED.line());
            return Outcome.failed("The question was sent but could not be recorded.");
        }
        return delivered;
    }

    // ========================================================================
    // ANSWERING
    // ========================================================================

    /**
     * What a press or a reply achieved, in the terms the handler has to acknowledge it.
     *
     * @param handled false only when no row matched, which is a stale or forged token
     */
    public record AnswerOutcome(boolean handled, String replyToUser, boolean asAlert,
                                ChatAuthorizationRequestEntity request, boolean settled) {

        static AnswerOutcome of(boolean handled, String replyToUser, boolean asAlert,
                                ChatAuthorizationRequestEntity request) {
            return new AnswerOutcome(handled, replyToUser, asAlert, request, false);
        }
    }

    /**
     * Somebody pressed a button on a question.
     *
     * <p>Three actions, and only two of them settle anything. A pick on a single-select and a
     * {@code done} on a multi-select finish that question; a toggle changes a draft and leaves
     * the question open, which is why it does NOT claim the row. Claiming on a toggle would
     * settle a question at the first tick and leave the person pressing a dead keyboard.
     */
    public AnswerOutcome answer(String token, String action, String fromUserId, PressOrigin origin) {
        Optional<ChatAuthorizationRequestEntity> found = requestRepository.findByCallbackToken(token);
        if (found.isEmpty() || !origin.admits(found.get().getChannel(), found.get().getCredentialId(),
                found.get().getChatId())) {
            return AnswerOutcome.of(false, null, false, null);
        }
        ChatAuthorizationRequestEntity row = found.get();
        if (row.getKind() == RequestKind.APPROVAL) {
            // An approval's token under the question prefix: a crafted press, since every
            // button carries its own family's prefix. Treated as unknown, so no verdict path
            // ever sees a question's answer and no question path ever settles a permission.
            return AnswerOutcome.of(false, null, false, null);
        }
        if (row.getStatus() == RequestStatus.EXPIRED) {
            // Not "already answered": that sentence would tell somebody their question had an
            // answer when nobody gave one, which is the one thing a closed request must not say.
            return AnswerOutcome.of(true, "This question expired. The agent will ask again on its "
                    + "next run.", false, row);
        }
        if (row.getStatus() != RequestStatus.SENT) {
            return AnswerOutcome.of(true, "This question was already answered.", false, row);
        }
        if (isPastDeadline(row, Instant.now())) {
            return AnswerOutcome.of(true, "This question expired. The agent will ask again on its "
                    + "next run.", false, row);
        }
        if (!isAllowed(row, fromUserId)) {
            return AnswerOutcome.of(true, "You are not one of the people who may answer here.",
                    true, row);
        }
        UserQuestion question = questionOf(row);
        if (question == null) {
            return AnswerOutcome.of(true, "This question could not be read, so its buttons do "
                    + "nothing.", true, row);
        }

        if ("other".equals(action)) {
            // Not an answer yet: the person is telling us they want to type one. Telegram has
            // no way to open a text box from a button, so the only route is a reply to this
            // message, which the reply handler matches back to this row.
            return AnswerOutcome.of(true, "Reply to that message with your answer.", true, row);
        }
        if (question.multiSelect() && action.startsWith("o")) {
            return toggle(row, question, indexOf(action));
        }
        List<String> picked;
        if (question.multiSelect()) {
            // Done: whatever was ticked. Nothing ticked is a real answer to nothing, so it is
            // refused rather than recorded as an empty pick the agent would have to interpret.
            picked = draftOf(row);
            if (picked.isEmpty()) {
                return AnswerOutcome.of(true, "Pick at least one option first.", true, row);
            }
        } else {
            // The label is resolved BEFORE the list is built: the index comes off a button
            // payload, so it can name an option the question no longer has, and List.of would
            // throw on the null rather than let that be answered.
            String label = labelAt(question, indexOf(action));
            if (label == null) {
                return AnswerOutcome.of(true, "That option is no longer part of this question.",
                        true, row);
            }
            picked = List.of(label);
        }
        return settle(row, question, picked, null, fromUserId);
    }

    /**
     * Whether a live question of ours is waiting on that message.
     *
     * <p>Asked BEFORE the webhook decides to divert a reply, which is the whole point: a reply
     * to somebody else's message has to keep flowing to whatever else the bot drives. Bounded
     * by the partial index on exactly this pair over live rows.
     */
    public boolean hasLiveQuestion(String chatId, String messageId, String botProviderId) {
        return liveQuestionFor(chatId, messageId, botProviderId).isPresent();
    }

    /**
     * The one live QUESTION a reply names, or nothing.
     *
     * <p>Three things narrow it, each for a failure that happened or would have. The kind:
     * a reply to an APPROVAL message is not an answer to a question, and matching it consumed
     * the permission request with no verdict at all. The bot: a private chat's id is the
     * person's own, so two workspaces reaching the same person through two bots can both have a
     * live question at the same message id, and the reply names the bot that sent the message
     * it answers. And exactly one: a lookup that still finds several cannot know which the
     * person meant, and a guess would put one workspace's answer into another's agent. Several
     * used to throw on the webhook thread, outside any catch, which Telegram answers by
     * sending the same update again.
     */
    private Optional<ChatAuthorizationRequestEntity> liveQuestionFor(String chatId, String messageId,
                                                                    String botProviderId) {
        if (isBlank(chatId) || isBlank(messageId)) {
            return Optional.empty();
        }
        List<ChatAuthorizationRequestEntity> candidates = requestRepository
                .findByChatIdAndMessageIdAndStatus(chatId, messageId, RequestStatus.SENT).stream()
                .filter(row -> row.getKind() != RequestKind.APPROVAL)
                .toList();
        if (!isBlank(botProviderId)) {
            // Whenever the quoted bot is known, not only when several rows match. One match can
            // still be the wrong one: another workspace's bot (driving a workflow trigger) can
            // have sent its own message 57 in its own private chat with the same person, and a
            // reply to THAT message would otherwise be diverted from its workflow and settle this
            // question with text meant for somebody else. A row whose bot cannot be resolved is
            // kept, so a lookup failure never turns into a refusal nobody can explain.
            candidates = candidates.stream()
                    .filter(row -> {
                        String identity = botIdentityOf(row);
                        return identity == null || botProviderId.equals(identity);
                    })
                    .toList();
        }
        if (candidates.size() > 1) {
            logger.warn("[chat-question] a reply matched {} live questions in chat {} and could not "
                    + "be attributed to one; ignored", candidates.size(), chatId);
            return Optional.empty();
        }
        return candidates.stream().findFirst();
    }

    /** The provider's id for the bot that sent this row's message, or null. */
    private String botIdentityOf(ChatAuthorizationRequestEntity row) {
        return linkRepository.findById(row.getLinkId())
                .flatMap(link -> botRepository.findById(link.getBotId()))
                .map(bot -> bot.getBotIdentity())
                .orElse(null);
    }

    /**
     * Somebody replied to a question in their own words.
     *
     * <p>The free-text half of {@code Other...}, and the reason that button is not a dead end.
     * Matched by the message the reply names, which is the only handle Telegram gives.
     */
    public AnswerOutcome answerWithText(String chatId, String replyToMessageId, String botProviderId,
                                        String text, String fromUserId, PressOrigin origin) {
        if (isBlank(text)) {
            return AnswerOutcome.of(false, null, false, null);
        }
        Optional<ChatAuthorizationRequestEntity> found =
                liveQuestionFor(chatId, replyToMessageId, botProviderId)
                        .filter(row -> origin.admits(row.getChannel(), row.getCredentialId(), row.getChatId()));
        if (found.isEmpty()) {
            // Not a reply to one of ours, or to one already settled. Silence on purpose: this
            // runs for every reply in a chat that may also carry a workflow trigger.
            return AnswerOutcome.of(false, null, false, null);
        }
        ChatAuthorizationRequestEntity row = found.get();
        if (isPastDeadline(row, Instant.now())) {
            return AnswerOutcome.of(true, "That question expired before your answer arrived.",
                    false, row);
        }
        if (!isAllowed(row, fromUserId)) {
            return AnswerOutcome.of(true, "You are not one of the people who may answer here.",
                    false, row);
        }
        UserQuestion question = questionOf(row);
        if (question == null) {
            return AnswerOutcome.of(true, "That question could not be read.", false, row);
        }
        String answer = text.trim();
        if (answer.length() > UserQuestionValidator.MAX_FREE_TEXT_LENGTH) {
            // Refused here, while the person can still shorten it. Telegram allows 4096
            // characters and the answer endpoint refuses more than this, so a longer reply would
            // be recorded, shown as answered, and then fail the whole hand-over with nobody told.
            return AnswerOutcome.of(true, "That answer is too long. Keep it under "
                    + UserQuestionValidator.MAX_FREE_TEXT_LENGTH + " characters and reply again.",
                    false, row);
        }
        return settle(row, question, List.of(), answer, fromUserId);
    }

    /**
     * Move one tick, redraw, and leave the question open.
     *
     * <p>The draft lives in the row rather than in memory because the next press can land on
     * the other replica. A redraw that fails is reported: unlike the closing edit this one is
     * not cosmetic, and a tick that does not appear is a button the person presses again.
     */
    private AnswerOutcome toggle(ChatAuthorizationRequestEntity row, UserQuestion question, int index) {
        String label = labelAt(question, index);
        if (label == null) {
            return AnswerOutcome.of(true, "That option is no longer part of this question.", true, row);
        }
        List<String> picked = new ArrayList<>(draftOf(row));
        if (!picked.remove(label)) {
            picked.add(label);
        }
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("selected", picked);
        row.setAnswer(draft);
        requestRepository.save(row);

        Optional<ChatChannelConnector> connector = connectors.forChannel(row.getChannel());
        if (connector.isEmpty()) {
            return AnswerOutcome.of(true, "Saved.", false, row);
        }
        // The holder is because the scope helper takes a Runnable, not a Supplier.
        List<Outcome<Void>> redrawHolder = new ArrayList<>(1);
        TenantResolver.runWithOrgScope(row.getOrganizationId(), () ->
                redrawHolder.add(connector.get().updateChoiceMarkup(row.getTenantId(),
                        row.getCredentialId(), row.getChatId(), row.getMessageId(),
                        row.getMessageText(), optionsOf(question, row.getCallbackToken(), picked,
                                connector.get().capabilities().acceptsTypedReplies()))));
        Outcome<Void> redrawn = redrawHolder.isEmpty()
                ? Outcome.<Void>failed("The redraw did not run.") : redrawHolder.get(0);
        if (!redrawn.ok()) {
            logger.info("[chat-question] could not redraw the keyboard for request {}: {}",
                    row.getId(), redrawn.error());
            return AnswerOutcome.of(true, "Saved, but the buttons did not refresh.", false, row);
        }
        return AnswerOutcome.of(true, picked.contains(label) ? "Added." : "Removed.", false, row);
    }

    /**
     * Finish one question: claim it, record the answer, close its message.
     *
     * <p>The claim is the same single statement the approval path uses, and it is what makes a
     * double press harmless: the second one matches nothing and is told the question is already
     * answered. Nothing else in here is safe to run twice.
     */
    private AnswerOutcome settle(ChatAuthorizationRequestEntity row, UserQuestion question,
                                 List<String> picked, String freeText, String fromUserId) {
        if (requestRepository.claim(row.getId(), Instant.now()) == 0) {
            // Somebody else got there first, or it expired between the read and here.
            return AnswerOutcome.of(true, "This question was already answered.", false, row);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("header", question.header());
        answer.put("selected", picked);
        answer.put("freeText", freeText);
        answer.put("custom", freeText != null);
        row.setAnswer(answer);
        row.setStatus(RequestStatus.RESOLVED);
        row.setDecidedBy(row.getChannel() + ":" + (fromUserId != null ? fromUserId : "unknown"));
        row.setDecidedAt(Instant.now());
        try {
            requestRepository.save(row);
        } catch (Exception ex) {
            // Claimed but not recorded. Left like that the row is RESOLVED with no answer, which
            // the group check counts as answered, so the set would be handed over with a hole in
            // it. The claim goes back and the person is asked to try again, as the approval path
            // does when it cannot apply.
            logger.warn("[chat-question] could not record the answer to question {}: {}",
                    row.getId(), ex.getMessage());
            try {
                requestRepository.releaseClaim(row.getId());
            } catch (Exception releaseFailed) {
                logger.warn("[chat-question] question {} left claimed with no answer", row.getId());
            }
            // And in memory: the caller reads this row to decide what to tell the person, and a
            // row still saying RESOLVED would read as an answer that went through.
            row.setStatus(RequestStatus.SENT);
            row.setAnswer(null);
            return AnswerOutcome.of(true, "Your answer could not be saved. Please try again.", true, row);
        }

        String shown = freeText != null ? freeText : String.join(", ", picked);
        closeAnswered(row, shown);
        // NOT applied here. Handing the answers over starts the agent's follow-up turn, which is
        // a full synchronous LLM run, and the caller still owes the person an acknowledgement:
        // Telegram stops accepting one for a press after a short window, so acking behind the
        // turn left the last button of a question spinning and never confirmed. The caller acks
        // first and then calls applyIfComplete.
        return new AnswerOutcome(true, "Answer recorded: " + shown, false, row, true);
    }

    /**
     * Take the buttons off an answered question and say what was chosen.
     *
     * <p>Cosmetic by contract, like the approval close: the answer is already recorded, so an
     * edit the provider refuses must not turn a successful answer into a failure. It matters
     * anyway, because buttons left live invite a second press on a question already settled.
     */
    private void closeAnswered(ChatAuthorizationRequestEntity row, String shown) {
        closeWith(row, answeredLine(shown));
    }

    /**
     * "Answered: ..." capped to the room the body left for it.
     *
     * <p>The body was capped at send to the provider's limit minus a fixed reserve, precisely so
     * a closing line fits. A typed answer runs to thousands of characters, and the connector
     * truncates the combined text from the END, so an uncapped line lost the verdict itself.
     */
    static String answeredLine(String shown) {
        String line = "Answered: " + (shown != null ? shown : "");
        int max = ANSWERED_LINE_MAX_CHARS;
        return line.length() <= max ? line : line.substring(0, max - 3) + "...";
    }

    /** Take the buttons off a settled question and append one line saying why. */
    private void closeWith(ChatAuthorizationRequestEntity row, String line) {
        try {
            connectors.forChannel(row.getChannel()).ifPresent(connector ->
                    TenantResolver.runWithOrgScope(row.getOrganizationId(), () ->
                            connector.closeDecisionRequest(row.getTenantId(), row.getCredentialId(),
                                    row.getChatId(), row.getMessageId(), row.getMessageText(),
                                    line)));
        } catch (Exception ex) {
            logger.info("[chat-question] settled request {} but could not close its message: {}",
                    row.getId(), ex.getMessage());
        }
    }

    /**
     * Hand the whole call's answers over, once the last question has one.
     *
     * <p>One call owes the agent ONE envelope, so nothing is applied until every question of
     * the group is settled. A call whose second question expired never completes, which is
     * correct: half an answer to a set of questions is not an answer.
     */
    public void applyIfComplete(ChatAuthorizationRequestEntity row) {
        if (row == null) {
            return;
        }
        if (isBlank(row.getGroupKey()) || answerApplier == null) {
            return;
        }
        List<ChatAuthorizationRequestEntity> group = requestRepository
                .findByConversationIdAndGroupKeyOrderByCreatedAtAsc(row.getConversationId(),
                        row.getGroupKey());
        if (group.isEmpty() || group.stream().anyMatch(r -> r.getStatus() != RequestStatus.RESOLVED)) {
            return;
        }
        if (group.stream().anyMatch(r -> r.getAnswer() == null)) {
            // RESOLVED with nothing recorded: a claim whose save failed and could not be handed
            // back. Handing the set over now would give the agent an envelope with a question
            // missing, which it would read as complete.
            logger.warn("[chat-question] call {} has a settled question with no recorded answer; "
                    + "not handed over", row.getGroupKey());
            return;
        }
        // Reading a complete group is not enough to act on it. Two answers landing in the same
        // second both read it complete, and the hand-over starts the agent's follow-up turn, so
        // acting twice means two LLM runs on one conversation, two replies, and an agent reading
        // its own answer twice. One statement decides which caller proceeds.
        if (requestRepository.claimGroupForApply(row.getConversationId(), row.getGroupKey(),
                Instant.now()) == 0) {
            return;
        }
        boolean handedOver;
        try {
            handedOver = answerApplier.apply(group);
        } catch (Exception ex) {
            logger.warn("[chat-question] could not hand the answers of call {} to the "
                    + "conversation: {}", row.getGroupKey(), ex.getMessage());
            handedOver = false;
        }
        if (handedOver && analytics != null) {
            // Once per set: only the caller that won the group claim AND handed it over gets here
            // (a failed hand-over releases the claim and is retried, and only that retry reports).
            analytics.channelRequestAnswered(row.getTenantId(), row.getOrganizationId(),
                    EngagementAnalyticsEmitter.RequestType.ASK_USER, row.getChannel(),
                    EngagementAnalyticsEmitter.Decision.ANSWERED, inputOf(row));
        }
        if (!handedOver) {
            // The answers ARE recorded and the person has been told so, so this cannot be
            // undone; it can be retried. The claim goes back and the expiry scheduler's retry
            // pass picks the group up again. Kept claimed, a thirty-second outage of
            // conversation-service at the moment the last answer landed lost the set for good.
            try {
                requestRepository.releaseGroupApply(row.getConversationId(), row.getGroupKey());
            } catch (Exception releaseFailed) {
                logger.warn("[chat-question] call {} could not be handed back for retry: {}",
                        row.getGroupKey(), releaseFailed.getMessage());
            }
        }
    }

    /**
     * How the answer that completed the set came in: a typed reply is recorded as custom, a
     * button pick is not. Null when the row carries no recorded answer to tell from.
     */
    static EngagementAnalyticsEmitter.Input inputOf(ChatAuthorizationRequestEntity row) {
        Map<String, Object> answer = row.getAnswer();
        if (answer == null) return null;
        return Boolean.TRUE.equals(answer.get("custom"))
                ? EngagementAnalyticsEmitter.Input.TEXT : EngagementAnalyticsEmitter.Input.BUTTON;
    }

    /**
     * Hand over the answered sets that did not reach the conversation the first time.
     *
     * <p>The only retry the hand-over has. It happens once, from the request that settled the
     * last question, and that request cannot wait out an outage of conversation-service; a
     * failure there hands the group back ({@link #applyIfComplete}) and this picks it up. The
     * group claim still decides who proceeds, so this and a late press can never both hand
     * the same set over.
     */
    @Scheduled(fixedDelayString = "${orchestrator.channel.question.handover-retry-ms:120000}")
    @SchedulerLock(name = "chat-question-handover-retry", lockAtMostFor = "PT2M")
    public void retryUnappliedAnswers() {
        Instant now = Instant.now();
        List<ChatAuthorizationRequestEntity> pending;
        try {
            pending = requestRepository.findUnappliedAnswers(now.minus(1, ChronoUnit.MINUTES),
                    now.minus(1, ChronoUnit.DAYS), PageRequest.of(0, RETRY_PER_PASS));
        } catch (Exception ex) {
            logger.warn("[chat-question] hand-over retry scan failed: {}", ex.getMessage());
            return;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ChatAuthorizationRequestEntity row : pending) {
            if (!seen.add(row.getConversationId() + "|" + row.getGroupKey())) {
                continue;
            }
            try {
                applyIfComplete(row);
            } catch (Exception ex) {
                logger.warn("[chat-question] hand-over retry of call {} failed: {}",
                        row.getGroupKey(), ex.getMessage());
            }
        }
    }

    /**
     * Whether this person may answer, read from the LINK at press time.
     *
     * <p>Fails OPEN when the link is gone, because the token is the real capability and a link
     * deleted mid-flight must not turn into a refusal nobody can lift. The id comes from an
     * unauthenticated payload; the real boundary is membership of the chat plus a token nobody
     * can guess.
     */
    private boolean isAllowed(ChatAuthorizationRequestEntity row, String fromUserId) {
        List<String> allowed = linkRepository.findById(row.getLinkId())
                .map(ChatChannelLinkEntity::getAllowedUserIds)
                .orElse(List.of());
        return allowed.isEmpty() || (fromUserId != null && allowed.contains(fromUserId));
    }

    /** The question as it was sent, rebuilt from the row rather than from the tool call. */
    @SuppressWarnings("unchecked")
    static UserQuestion questionOf(ChatAuthorizationRequestEntity row) {
        Map<String, Object> payload = row.getPayload();
        if (payload == null) {
            return null;
        }
        List<UserQuestionOption> options = new ArrayList<>();
        Object raw = payload.get("options");
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    options.add(new UserQuestionOption(asString(map.get("label")),
                            asString(map.get("description"))));
                }
            }
        }
        return new UserQuestion(asString(payload.get("header")), asString(payload.get("question")),
                options, Boolean.TRUE.equals(payload.get("multiSelect")));
    }

    /** The labels ticked so far on a multi-select, or empty. */
    @SuppressWarnings("unchecked")
    private static List<String> draftOf(ChatAuthorizationRequestEntity row) {
        Map<String, Object> answer = row.getAnswer();
        if (answer == null || !(answer.get("selected") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static String labelAt(UserQuestion question, int index) {
        return index >= 0 && index < question.options().size()
                ? question.options().get(index).label() : null;
    }

    private static int indexOf(String action) {
        try {
            return Integer.parseInt(action.substring(1));
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private static String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * The buttons for one question, with the already-picked ones ticked.
     *
     * <p>Descriptions go in the BODY, not on the buttons: a Telegram button has no subtitle, so
     * the only place a description can be read is the message text, and dropping it would lose
     * the half of an option that says what it means.
     */
    static List<ChoiceOption> optionsOf(UserQuestion question, String token, List<String> picked,
                                        boolean offerOther) {
        List<ChoiceOption> buttons = new ArrayList<>();
        List<UserQuestionOption> options = question.options();
        for (int index = 0; index < options.size(); index++) {
            String label = options.get(index).label();
            boolean ticked = question.multiSelect() && picked.contains(label);
            buttons.add(new ChoiceOption(ticked ? PICKED_PREFIX + label : label,
                    CallbackTokens.questionPayload(token, "o" + index)));
        }
        // Offered wherever a typed reply can come back. The tool help tells the agent never to
        // declare an "Other" option because the person can always answer in their own words, and
        // that promise holds on every provider that can bring the words back. On one that cannot
        // (a Slack or Discord interaction carries no text), the button would promise an answer
        // with nowhere to go, so it is left out rather than offered and ignored.
        if (offerOther) {
            buttons.add(new ChoiceOption(OTHER_LABEL, CallbackTokens.questionPayload(token, "other")));
        }
        if (question.multiSelect()) {
            buttons.add(new ChoiceOption(DONE_LABEL, CallbackTokens.questionPayload(token, "done")));
        }
        return buttons;
    }

    /** Header, question, then one line per option so its description is readable. */
    static String bodyOf(UserQuestion question) {
        StringBuilder body = new StringBuilder();
        if (!isBlank(question.header())) {
            body.append(question.header()).append("\n\n");
        }
        body.append(question.question());
        for (UserQuestionOption option : question.options()) {
            body.append("\n- ").append(option.label());
            if (!isBlank(option.description())) {
                body.append(": ").append(option.description());
            }
        }
        if (question.multiSelect()) {
            body.append("\n\nPick as many as apply, then press ").append(DONE_LABEL).append('.');
        }
        return body.toString();
    }

    /**
     * What makes "the same question asked again" recognisable.
     *
     * <p>The questions themselves, not the tool call id: a call id is new every run, so keying
     * on it would make the duplicate rule match nothing and a nightly agent would stack a fresh
     * copy of the same question every night.
     */
    private static String materialOf(List<UserQuestion> questions) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("questions", questions.stream().map(UserQuestion::toMap).toList());
        return AuthorizationAsk.material(QUESTION_RULE, material);
    }

    private static boolean isPastDeadline(ChatAuthorizationRequestEntity row, Instant now) {
        return row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now);
    }

    private static String capToConnector(ChatChannelConnector connector, String text) {
        int max = connector.maxDecisionTextChars();
        return text != null && text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * The stored title when there is one, else the raw id: "your Ops room" reads as something a
     * person recognises, "-100123" does not. Same rule the approval path applies, because the
     * agent says this sentence back to the person and they have to recognise the place.
     */
    private String chatLabel(ResolvedTarget target) {
        return linkRepository.findById(target.linkId())
                .map(ChatChannelLinkEntity::getChatTitle)
                .filter(title -> title != null && !title.isBlank())
                .orElseGet(target::chatId);
    }

    private static java.util.UUID parseUuidOrNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return java.util.UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
