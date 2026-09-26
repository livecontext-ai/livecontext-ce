package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.Decision;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget;
import com.apimarketplace.agent.tools.authz.AuthorizationAsk;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Delivers an agent's request for permission to the workspace's chat channel, and
 * applies the answer that comes back from a button.
 *
 * <p><b>Why this exists.</b> A sensitive action raises an authorization card, and
 * in a chat that is enough: the person is looking at it. An agent running from a
 * schedule has the same card and nobody in front of it, so the run would stop
 * until someone happened to open the app. This is the other end of that card, for
 * the case where the person is elsewhere.
 *
 * <p><b>Nothing here can make an action run.</b> The answer is applied by
 * releasing the parked call through conversation-service, exactly as the in-app
 * card does. A failure anywhere on this path leaves the request unanswered, which
 * is the same outcome as having no channel connected at all: the run ends without
 * doing the sensitive thing. That asymmetry is deliberate, and it is why every
 * step below is allowed to fail loudly in the log and quietly to the caller.
 */
@Service
public class AgentAuthorizationChannelService {

    private static final Logger logger = LoggerFactory.getLogger(AgentAuthorizationChannelService.class);

    /** Namespaced callback prefix, distinct from the workflow approval one ({@code lcapr}). */
    public static final String CALLBACK_PREFIX = "lcaut";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Name of the partial unique index that enforces one live request per ask (V517). */
    static final String LIVE_REQUEST_INDEX = "uq_chat_auth_requests_live";

    /**
     * What an expired request's message is edited to say, from whichever path retires it.
     *
     * <p>Three paths retire a row (the sweep; a delivery that finds an overdue one in its way;
     * and a press whose decision could not be applied and could not be handed back), and a person
     * must not be able to tell which one reached their message first. Shared rather than repeated
     * because the difference would only ever show up in somebody's chat.
     *
     * <p>Public because a connector's own cap has to leave room for it: the longest verdict the
     * product can append is what sizes the reserve held back at send time.
     */
    /**
     * Every line this product can append to a delivered decision message, and the only lines it
     * can append.
     *
     * <p><b>A closed type, because the alternative kept failing.</b> A connector holds back room
     * for the longest verdict at send time, and that reserve is only right if it was measured
     * against the real set. A hand-written list drifted from the call sites; deriving the list
     * from {@code *_VERDICT} constants by reflection drifted too, just one step later, because
     * nothing stopped a close site passing a string that was never a constant at all, and a test
     * over the declared constants cannot see that. An enum can: a call site has nothing else to
     * pass, so a new verdict has to be declared here, and declaring it here is what puts it in
     * front of {@code TelegramChannelConnectorTest}'s arithmetic.
     */
    public enum Verdict {
        /** A press was applied. */
        APPROVED("✅ Approved"),
        /** A press was applied, and it was a refusal. */
        REFUSED("❌ Refused"),
        /** Nobody answered before the deadline. Used by every path that retires a request. */
        EXPIRED("⏰ Expired without an answer. The agent will ask again on its next run."),
        /** The request could not be recorded, so its buttons do nothing. */
        UNRECORDED("This request could not be recorded, so its buttons do nothing."),
        /** Another replica sent the same question a moment earlier. */
        SUPERSEDED("Superseded by an identical request.");

        private final String line;

        Verdict(String line) {
            this.line = line;
        }

        /** The sentence appended to the message. */
        public String line() {
            return line;
        }

        /** The longest verdict, which is what a connector's reserve has to cover. */
        public static int longestLineLength() {
            int longest = 0;
            for (Verdict verdict : values()) {
                longest = Math.max(longest, verdict.line.length());
            }
            return longest;
        }
    }

    /** What the caller must know to decide what to tell the agent. */
    public enum DeliveryStatus {
        /** A message with live buttons is now in the user's chat. */
        SENT,
        /** The same question is already waiting there, from an earlier run. */
        ALREADY_PENDING,
        /** This workspace has connected no destination. Nothing was sent. */
        NO_CHANNEL,
        /** A destination exists but the message could not be delivered. */
        FAILED
    }

    /**
     * One request to put in front of a person.
     *
     * <p>{@code summary} is for the MESSAGE and may well be absent; {@code fingerprint}
     * is what identifies the ask, and the caller derives it from the call's own
     * arguments. They are separate because folding them into one made the identity
     * collapse to the rule whenever the summary was missing, so two genuinely different
     * asks of the same rule in one conversation would have been refused as duplicates.
     */
    public record DeliveryRequest(
            String tenantId, String organizationId, String conversationId, String gateKey,
            String rule, String agentId, String agentName, String summary, String fingerprint) {}

    public record DeliveryResult(DeliveryStatus status, String channel, String chatLabel,
                                 Instant requestedAt, String error) {
        public static DeliveryResult of(DeliveryStatus status) {
            return new DeliveryResult(status, null, null, null, null);
        }
    }

    private final ChatChannelService channelService;
    private final ChatChannelConnectorRegistry connectors;
    private final ChatAuthorizationRequestRepository requestRepository;
    private final ChatChannelLinkRepository linkRepository;
    private final AgentAuthorizationAnswerApplier answerApplier;
    private final ObjectProvider<AgentClient> agentClientProvider;
    private final Duration requestTtl;

    /** Product analytics; optional so a unit test can build the service without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    public AgentAuthorizationChannelService(
            ChatChannelService channelService,
            ChatChannelConnectorRegistry connectors,
            ChatAuthorizationRequestRepository requestRepository,
            ChatChannelLinkRepository linkRepository,
            AgentAuthorizationAnswerApplier answerApplier,
            ObjectProvider<AgentClient> agentClientProvider,
            @Value("${orchestrator.channel.authorization.ttl-hours:24}") long ttlHours) {
        this.channelService = channelService;
        this.connectors = connectors;
        this.requestRepository = requestRepository;
        this.linkRepository = linkRepository;
        this.answerApplier = answerApplier;
        this.agentClientProvider = agentClientProvider;
        this.requestTtl = Duration.ofHours(ttlHours > 0 ? ttlHours : 24);
    }

    // ========================================================================
    // OUTBOUND
    // ========================================================================

    /**
     * Put the request in front of the person, unless the same one already is.
     *
     * <p>The duplicate check is what keeps a nightly agent from sending the same
     * question every night until someone answers. It is read first for a clear
     * answer and enforced by a partial unique index for the two-replica race.
     */
    public DeliveryResult deliver(DeliveryRequest request) {
        DeliveryResult result = doDeliver(request);
        if (analytics != null && result != null) {
            analytics.channelRequestDelivered(request.tenantId(), request.organizationId(),
                    EngagementAnalyticsEmitter.RequestType.AGENT_PERMISSION, result.channel(),
                    EngagementAnalyticsEmitter.RequestStatus.of(result.status()));
        }
        return result;
    }

    private DeliveryResult doDeliver(DeliveryRequest request) {
        if (request.organizationId() == null || request.organizationId().isBlank()
                || request.conversationId() == null || request.conversationId().isBlank()
                || request.gateKey() == null || request.gateKey().isBlank()) {
            return DeliveryResult.of(DeliveryStatus.NO_CHANNEL);
        }
        // The agent's own destination when it chose one (V523), the workspace default otherwise.
        // A chosen destination that is gone reads as "nothing connected", never as another chat.
        AgentChannelChoice.Choice choice = AgentChannelChoice.lookup(agentClientProvider,
                request.tenantId(), request.organizationId(), request.agentId());
        if (choice.unknown()) {
            // Whether it chose a destination is unknown: sending to the default could ask people
            // nobody picked. Nothing is sent; the request stays answerable in the app.
            return new DeliveryResult(DeliveryStatus.FAILED, null, null, null, AgentChannelChoice.UNKNOWN_DESTINATION);
        }
        if (choice.switchedOff()) {
            // Its owner chose that nothing leaves the app: the same answer as no channel at all.
            return DeliveryResult.of(DeliveryStatus.NO_CHANNEL);
        }
        Optional<ResolvedTarget> targetOpt = channelService.resolveFor(request.organizationId(), choice.linkId());
        if (targetOpt.isEmpty()) {
            return DeliveryResult.of(DeliveryStatus.NO_CHANNEL);
        }
        ResolvedTarget target = targetOpt.get();
        // The caller's material when it has any, the display summary as a fallback.
        String fingerprint = fingerprintOf(request.rule(),
                request.fingerprint() != null && !request.fingerprint().isBlank()
                        ? request.fingerprint() : request.summary());

        Instant now = Instant.now();
        Optional<ChatAuthorizationRequestEntity> live = requestRepository
                .findByConversationIdAndFingerprintAndStatus(
                        request.conversationId(), fingerprint, RequestStatus.SENT);
        if (live.isPresent() && !live.get().isPastDeadline(now)) {
            return pending(live.get(), target);
        }

        ChatChannelConnector connector = connectors.forChannel(target.channel()).orElse(null);
        if (connector == null) {
            logger.warn("[chat-auth] no connector for channel {} - request not delivered", target.channel());
            return DeliveryResult.of(DeliveryStatus.NO_CHANNEL);
        }

        if (live.isPresent()) {
            // Past its deadline, and the sweep has not reached it. Reporting it as pending would
            // tell the agent to wait for an answer to a question nobody can answer any more, for
            // as long as it takes a scheduler to get to this one row.
            //
            // Retired the SAME way the sweep retires it, message edit included. Flipping only the
            // status would hide the row from the sweep forever (it selects SENT), leaving live
            // buttons on a dead question: a press would then be met with "This request was
            // already decided", which is the sentence this feature goes out of its way never to
            // say when nobody decided anything. It runs AFTER the connector is resolved, so a
            // workspace with no connector does not lose its old request to a delivery that was
            // never going to happen.
            if (!retire(live.get())) {
                logger.warn("[chat-auth] could not retire overdue request {} - not asking again yet",
                        live.get().getId());
                return pending(live.get(), target);
            }
        }

        String token = newToken();
        String agentName = request.agentName() != null && !request.agentName().isBlank()
                ? request.agentName() : choice.agentName();
        // Capped HERE, to what this connector accepts, so the row stores the body that was
        // really sent. Storing an uncapped one would keep text the provider dropped, and the
        // closing edit would then be built from a message nobody ever read.
        String text = capToConnector(connector, buildMessage(agentName, request));
        // Under the request's workspace: an org-shared bot credential does not resolve
        // without the binding, and this thread carries no org header of its own.
        // The holder is because the scope helper takes a Runnable, not a Supplier.
        List<ChatChannelConnector.Outcome<String>> sentHolder = new java.util.ArrayList<>(1);
        TenantResolver.runWithOrgScope(request.organizationId(), () ->
                sentHolder.add(connector.sendDecisionRequest(request.tenantId(), target.credentialId(),
                        target.chatId(), text,
                        CALLBACK_PREFIX + ":" + token + ":a", CALLBACK_PREFIX + ":" + token + ":r")));
        ChatChannelConnector.Outcome<String> sent = sentHolder.isEmpty()
                ? ChatChannelConnector.Outcome.failed("The send did not run.")
                : sentHolder.get(0);
        if (!sent.ok()) {
            logger.warn("[chat-auth] delivery failed for conversation {}: {}",
                    request.conversationId(), sent.error());
            return new DeliveryResult(DeliveryStatus.FAILED, target.channel(), null, null, sent.error());
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
        row.setRule(request.rule());
        row.setAgentId(parseUuid(request.agentId()));
        row.setAgentName(agentName);
        row.setFingerprint(fingerprint);
        row.setMessageId(sent.value());
        // The body AS SENT. The closing edit reuses it instead of rebuilding a shorter message,
        // which used to delete the summary of what was approved from the only audit trail a
        // phone user has.
        row.setMessageText(text);
        row.setStatus(RequestStatus.SENT);
        row.setExpiresAt(Instant.now().plus(requestTtl));
        try {
            requestRepository.save(row);
        } catch (DataIntegrityViolationException ex) {
            // ONLY the live-request index means "somebody asked this a moment ago". Any
            // other integrity failure (a null rule, an over-long gate key) would otherwise
            // be reported to the person as "superseded by an identical request", replacing
            // a real question with a sentence that is not true.
            if (!isDuplicateLiveRequest(ex)) {
                logger.warn("[chat-auth] could not record the request for conversation {}: {}",
                        request.conversationId(), ex.getMessage());
                TenantResolver.runWithOrgScope(request.organizationId(), () ->
                        connector.closeDecisionRequest(request.tenantId(), target.credentialId(),
                                target.chatId(), sent.value(), text,
                                Verdict.UNRECORDED.line()));
                return new DeliveryResult(DeliveryStatus.FAILED, target.channel(), null, null, ex.getMessage());
            }
            // Lost the race with another replica that sent the same question a
            // moment ago. The message we just sent is a duplicate: close it so the
            // person is not offered two identical pairs of buttons.
            logger.info("[chat-auth] duplicate request for conversation {} - closing the message just sent",
                    request.conversationId());
            TenantResolver.runWithOrgScope(request.organizationId(), () ->
                    connector.closeDecisionRequest(request.tenantId(), target.credentialId(), target.chatId(),
                            sent.value(), text, Verdict.SUPERSEDED.line()));
            return requestRepository.findByConversationIdAndFingerprintAndStatus(
                            request.conversationId(), fingerprint, RequestStatus.SENT)
                    .map(existing -> pending(existing, target))
                    .orElseGet(() -> DeliveryResult.of(DeliveryStatus.FAILED));
        }
        logger.info("[chat-auth] asked {} on {} for rule {} (conversation {})",
                target.chatId(), target.channel(), request.rule(), request.conversationId());
        return new DeliveryResult(DeliveryStatus.SENT, target.channel(),
                chatLabel(target), row.getCreatedAt() != null ? row.getCreatedAt() : Instant.now(), null);
    }

    // ========================================================================
    // INBOUND
    // ========================================================================

    /** What a channel's callback handler reports back after a button press. */
    public record AnswerOutcome(boolean handled, String replyToUser, boolean asAlert,
                                ChatAuthorizationRequestEntity request) {}

    /**
     * Apply a button press: claim the request, release the parked call, then reflect
     * the verdict on the message.
     *
     * <p><b>Claim before apply, not after.</b> Two presses reach here routinely - a
     * double tap, two members of a group, a provider retry after a slow
     * acknowledgement - and the terminal check alone lets both through, the second
     * one writing a standing grant for a call that is no longer parked. The
     * conditional UPDATE lets exactly one caller past. The rare failure AFTER a
     * successful claim hands the row back, because a question that was never
     * answered must not sit there looking decided while the duplicate rule refuses
     * the agent's next ask for a day.
     */
    public AnswerOutcome answer(String token, boolean approve, String fromUserId, PressOrigin origin) {
        Optional<ChatAuthorizationRequestEntity> rowOpt = requestRepository.findByCallbackToken(token);
        if (rowOpt.isEmpty() || !origin.admits(rowOpt.get().getChannel(), rowOpt.get().getCredentialId(),
                rowOpt.get().getChatId())) {
            // No row, or a token answered from somewhere its message never went (see
            // PressOrigin): the two are told apart by nobody, on purpose.
            // Stale message from a purged workspace, or a guessed token. With no row
            // there is not even a credential to acknowledge the press with.
            return new AnswerOutcome(false, null, false, null);
        }
        ChatAuthorizationRequestEntity row = rowOpt.get();
        if (row.getKind() != ChatAuthorizationRequestEntity.RequestKind.APPROVAL) {
            // A question's token under the approval prefix. Nothing in Telegram produces that:
            // each button carries its own family's prefix, so this is a crafted press. Treated
            // exactly as an unknown token, because resolving it would apply a verdict to a row
            // that asked for a value, and post an approval against a question's gate key.
            return new AnswerOutcome(false, null, false, null);
        }
        if (row.isTerminal()) {
            return new AnswerOutcome(true, "This request was already decided.", false, row);
        }
        if (row.isPastDeadline(Instant.now())) {
            // The sweep has not reached this row, but the deadline is the deadline. Answering
            // here would authorize an action the agent was already told it could not do, on a
            // run that has long since ended, and in an unattended agent the same question has
            // most likely been asked and answered again since.
            return new AnswerOutcome(true, "This request expired. The agent will ask again on its "
                    + "next run.", true, row);
        }
        if (!isAllowed(row, fromUserId)) {
            return new AnswerOutcome(true, "You are not allowed to decide this.", true, row);
        }
        if (requestRepository.claim(row.getId(), Instant.now()) == 0) {
            // Somebody else got there first, or it expired between the read and here.
            return new AnswerOutcome(true, "This request was already decided.", false, row);
        }

        boolean applied = answerApplier.apply(row, approve, fromUserId);
        if (!applied) {
            // Nothing was authorized, so nothing may look decided: give the row back and
            // say so rather than showing a verdict the run will never act on.
            //
            // Handing it back can itself be refused: while this one was claimed, the
            // agent's next run may have asked the same question again and taken the live
            // slot, which the partial unique index allows exactly one of. Retiring it
            // instead keeps the row terminal: answerable by nobody, blocking nobody.
            //
            // And retiring it means CLOSING it. The sweep only sees rows that are still SENT, so
            // a bare status flip strands this message with two live buttons for good, and the
            // person's next press is answered "This request was already decided" about a
            // decision nobody took. This comment used to claim the sweep would pick the row up
            // afterwards. It never would.
            boolean handedBack;
            try {
                // The RETURN VALUE, not just the absence of a throw. A zero means the conditional
                // UPDATE matched nothing, which leaves the row RESOLVED with no decision: still
                // terminal, still invisible to the sweep, and its buttons still live. Reading
                // only the exception treats that as a successful hand-back and strands it.
                handedBack = requestRepository.releaseClaim(row.getId()) > 0;
                if (handedBack) {
                    row.setStatus(RequestStatus.SENT);
                } else {
                    logger.info("[chat-auth] request {} could not be handed back (no row matched)",
                            row.getId());
                }
            } catch (Exception ex) {
                logger.info("[chat-auth] could not hand request {} back, retiring it instead: {}",
                        row.getId(), ex.getMessage());
                handedBack = false;
            }
            if (!handedBack) {
                row.setStatus(RequestStatus.EXPIRED);
                try {
                    requestRepository.save(row);
                    closeExpired(row);
                } catch (Exception ignored) {
                    logger.warn("[chat-auth] request {} left claimed with no decision", row.getId());
                }
            }
            return new AnswerOutcome(true, "Could not apply this decision. Try again from the app.", true, row);
        }

        row.setStatus(RequestStatus.RESOLVED);
        row.setDecision(approve ? Decision.APPROVED : Decision.REJECTED);
        row.setDecidedBy(row.getChannel() + ":" + (fromUserId != null ? fromUserId : "unknown"));
        row.setDecidedAt(Instant.now());
        requestRepository.save(row);

        // Under the row's workspace, like every other catalog call made on its behalf:
        // this runs on a public webhook thread with no org header, and without the
        // binding an org-shared bot credential does not resolve. The failure would be
        // silent - the edit's outcome is best-effort - and would leave live buttons on
        // a question that is already settled.
        closeUnderOrgScope(row, approve ? Verdict.APPROVED : Verdict.REFUSED);

        if (analytics != null) {
            // The request's owner, the person the gate asked for: the presser is a chat user id,
            // not a platform user. Only a button press reaches this method.
            analytics.channelRequestAnswered(row.getTenantId(), row.getOrganizationId(),
                    EngagementAnalyticsEmitter.RequestType.AGENT_PERMISSION, row.getChannel(),
                    approve ? EngagementAnalyticsEmitter.Decision.APPROVED
                            : EngagementAnalyticsEmitter.Decision.REJECTED,
                    EngagementAnalyticsEmitter.Input.BUTTON);
        }
        return new AnswerOutcome(true, approve ? "Approved ✅" : "Refused ❌", false, row);
    }

    /** Acknowledge the press on the provider's UI, best-effort. */
    public void acknowledge(ChatAuthorizationRequestEntity row, String buttonEventId, String text, boolean asAlert) {
        if (row == null || buttonEventId == null || text == null) {
            return;
        }
        connectors.forChannel(row.getChannel()).ifPresent(connector ->
                connector.ackButton(row.getTenantId(), row.getCredentialId(), buttonEventId, text, asAlert));
    }

    /** Close the message under the request's own workspace scope. See {@link #answer}. */
    private void closeUnderOrgScope(ChatAuthorizationRequestEntity row, Verdict verdict) {
        connectors.forChannel(row.getChannel()).ifPresent(connector ->
                TenantResolver.runWithOrgScope(row.getOrganizationId(), () -> {
                    try {
                        var closed = connector.closeDecisionRequest(row.getTenantId(), row.getCredentialId(),
                                row.getChatId(), row.getMessageId(), closingBody(row), verdict.line());
                        if (closed == null || !closed.ok()) {
                            // Cosmetic, but not invisible: buttons left live on a settled
                            // question is exactly what this call exists to prevent.
                            logger.info("[chat-auth] could not close request {} on {}: {}", row.getId(),
                                    row.getChannel(), closed != null ? closed.error() : "no outcome");
                        }
                    } catch (Exception ex) {
                        // This runs AFTER the decision was applied. Letting it escape would
                        // turn an approval that really happened into a failure the person
                        // is told to retry, on a question that is already settled.
                        logger.info("[chat-auth] swallowed close of request {}: {}",
                                row.getId(), ex.getMessage());
                    }
                }));
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    /** The live-request index, and nothing else, means "already asked". */
    static boolean isDuplicateLiveRequest(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(LIVE_REQUEST_INDEX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retire a request whose deadline has passed, so the next ask is not refused as a duplicate.
     *
     * <p>Same outcome as the sweep, taken on the path that noticed first. It reports failure
     * rather than throwing: losing a race with the sweep itself is normal, and the caller's
     * fallback (report the old request as pending) is the safe one.
     *
     * <p>A plain save rather than a CAS, unlike {@code claim} and {@code releaseClaim}, and what
     * makes that safe lives in the READER, not here: a press arriving while this write is still
     * in flight is refused by {@code claim}'s own {@code expiresAt > :now} predicate, because by
     * the time anything calls retire that deadline is already in the past and cannot move. So a
     * lost update here costs a status flip, never a decision. Changing {@code claim}'s predicate
     * would silently take that guarantee away, which is why it is written down on both sides.
     */
    private boolean retire(ChatAuthorizationRequestEntity row) {
        try {
            row.setStatus(RequestStatus.EXPIRED);
            requestRepository.save(row);
        } catch (Exception ex) {
            // The row is what the duplicate rule reads, so a failure here means the old question
            // is still live and the new one must not be sent: the partial unique index would
            // refuse it anyway.
            logger.info("[chat-auth] could not retire request {}: {}", row.getId(), ex.getMessage());
            return false;
        }
        // Best-effort, and deliberately AFTER the status: the edit is cosmetic, the row is not.
        // A provider that refuses the edit must not make the agent wait another cycle.
        closeExpired(row);
        return true;
    }

    /**
     * Take the buttons off a request that is over, whatever retired it.
     *
     * <p>Every path that writes {@code EXPIRED} owes the message this edit. The sweep only ever
     * looks at rows that are still {@code SENT}, so a status flip on its own hides the row from
     * the sweep permanently and the buttons stay live for good. The next press is then answered
     * "This request was already decided" about a decision nobody took, which is the one sentence
     * this feature goes out of its way never to say.
     *
     * <p>Resolved from the ROW's own channel, not from whatever the workspace's default happens
     * to be now: the overdue request is found by conversation and fingerprint, which are not
     * channel-scoped, so a workspace whose default has since moved would otherwise edit one
     * provider's message through another provider's connector.
     */
    private void closeExpired(ChatAuthorizationRequestEntity row) {
        try {
            connectors.forChannel(row.getChannel()).ifPresent(connector ->
                    TenantResolver.runWithOrgScope(row.getOrganizationId(), () ->
                            connector.closeDecisionRequest(row.getTenantId(), row.getCredentialId(),
                                    row.getChatId(), row.getMessageId(), closingBody(row),
                                    Verdict.EXPIRED.line())));
        } catch (Exception ex) {
            logger.info("[chat-auth] retired request {} but could not close its message: {}",
                    row.getId(), ex.getMessage());
        }
    }

    private DeliveryResult pending(ChatAuthorizationRequestEntity existing, ResolvedTarget target) {
        return new DeliveryResult(DeliveryStatus.ALREADY_PENDING, target.channel(), chatLabel(target),
                existing.getCreatedAt(), null);
    }

    /**
     * The allow-list is per destination, so it is read from the link rather than
     * copied onto the request: a workspace that tightens who may decide should not
     * have to wait for the requests already in flight to expire.
     *
     * <p>Fails OPEN when the link is gone, because the token is the real capability
     * and a link deleted mid-flight must not turn into a refusal nobody can lift.
     */
    private boolean isAllowed(ChatAuthorizationRequestEntity row, String fromUserId) {
        List<String> allowed = linkRepository.findById(row.getLinkId())
                .map(ChatChannelLinkEntity::getAllowedUserIds)
                .orElse(List.of());
        return allowed.isEmpty() || (fromUserId != null && allowed.contains(fromUserId));
    }

    /**
     * What to call this destination when telling the agent where the question went.
     * The stored title when there is one, else the raw id: "your Ops room" reads as
     * something a person recognises, "-100123" does not.
     */
    private String chatLabel(ResolvedTarget target) {
        return linkRepository.findById(target.linkId())
                .map(ChatChannelLinkEntity::getChatTitle)
                .filter(title -> title != null && !title.isBlank())
                .orElseGet(target::chatId);
    }

    /** What this connector will accept, so the stored body is the one that was sent. */
    private static String capToConnector(ChatChannelConnector connector, String text) {
        int max = connector.maxDecisionTextChars();
        return text != null && text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * The message body. Names the agent and what it wants to do, because the person
     * reading it on their phone has none of the context the app would have given
     * them.
     */
    private static String buildMessage(String agentName, DeliveryRequest request) {
        StringBuilder text = new StringBuilder();
        String agent = agentName != null && !agentName.isBlank() ? agentName : "An agent";
        text.append(agent).append(" needs your permission to continue.\n\n");
        if (request.summary() != null && !request.summary().isBlank()) {
            text.append(request.summary()).append("\n\n");
        }
        text.append("Action: ").append(request.rule());
        text.append("\n\nNothing has run yet. Approving lets it run; refusing stops it.");
        return text.toString();
    }

    /**
     * The body to edit when closing: the one that was sent.
     *
     * <p>This used to rebuild a shorter message from the agent name and the rule, which dropped
     * the summary. The person then had, in place of "publish the September report to LinkedIn",
     * only "Action: publish_post" plus a verdict, on the single record of the decision they
     * hold. The fallback stays for a row written before the body was stored, so an old request
     * still closes with something rather than an empty edit.
     */
    static String closingBody(ChatAuthorizationRequestEntity row) {
        if (row.getMessageText() != null && !row.getMessageText().isBlank()) {
            return row.getMessageText();
        }
        String agent = row.getAgentName() != null && !row.getAgentName().isBlank()
                ? row.getAgentName() : "An agent";
        return agent + " needs your permission to continue.\n\nAction: " + row.getRule();
    }

    /**
     * What makes two asks "the same ask": the rule plus a digest of the summary the
     * caller built from the call's own arguments. Hashed rather than stored raw so
     * the column stays bounded whatever the arguments looked like.
     */
    static String fingerprintOf(String rule, String summary) {
        // The shared one, because this value is no longer only an index key: the grant written
        // for a late answer carries it, and agent-service recomputes it from the call to decide
        // whether that grant covers it. Two copies of the digest would drift into a permission
        // that never matches, which looks exactly like a person who never pressed the button.
        return AuthorizationAsk.fingerprint(rule, summary);
    }

    /** Delegated, so the question path cannot end up with a weaker token than this one. */
    private static String newToken() {
        return CallbackTokens.newToken();
    }

    private static java.util.UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
