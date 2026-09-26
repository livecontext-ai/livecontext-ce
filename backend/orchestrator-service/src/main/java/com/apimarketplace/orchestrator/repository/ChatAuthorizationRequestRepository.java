package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authorization requests delivered to a chat.
 *
 * <p>The token lookup is unscoped by design: it is the capability the button
 * carries, and the row it finds is what tells us which workspace the click even
 * belongs to. Everything else here is scoped.
 */
@Repository
public interface ChatAuthorizationRequestRepository extends JpaRepository<ChatAuthorizationRequestEntity, UUID> {

    Optional<ChatAuthorizationRequestEntity> findByCallbackToken(String callbackToken);

    // Every claim statement below is @Modifying, which needs a transaction of its own:
    // they are called from a webhook thread that has none.

    /**
     * The live request for this exact ask, if one is already out there.
     *
     * <p>Read before sending, so the agent can be told "you already asked" instead
     * of a second identical message being delivered. The partial unique index is
     * what actually guarantees it under a race; this is the friendly path.
     */
    Optional<ChatAuthorizationRequestEntity> findByConversationIdAndFingerprintAndStatus(
            String conversationId, String fingerprint, RequestStatus status);

    /**
     * The overdue backlog, oldest deadline first and capped by the caller.
     *
     * <p>Capped in SQL rather than in the loop that consumes it. The sweep runs
     * platform-wide, not per workspace, so the set it selects is as large as every
     * unanswered request everywhere: a provider outage lasting a day makes the whole
     * backlog arrive in one list, of which the pass would use the first 200.
     *
     * <p>Oldest first so the cap cannot starve anybody. An unordered LIMIT hands back
     * whatever the plan happens to produce, which under a backlog larger than one pass
     * can be the same rows every five minutes while the oldest request never expires.
     */
    List<ChatAuthorizationRequestEntity> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            RequestStatus status, Instant cutoff, Pageable page);

    /**
     * The rows of one {@code ask_user} call, in the order the questions were asked.
     *
     * <p>Read on every answer, to find out whether the one just given was the last. The agent
     * is owed ONE envelope for the whole call, so nothing can be applied until every question
     * in the group has an answer.
     */
    List<ChatAuthorizationRequestEntity> findByConversationIdAndGroupKeyOrderByCreatedAtAsc(
            String conversationId, String groupKey);

    /**
     * Claim one call's answers for hand-over, so it happens exactly once.
     *
     * <p>The same single-statement shape as {@link #claim}: the first caller matches the rows
     * and wins, the second matches none. Without it, two answers arriving within the same
     * second both read a fully answered group and both hand it over, which starts a second
     * agent turn on the same conversation while the first is still running.
     *
     * <p>{@code @Transactional} for the same reason {@link #claim} carries it and not as
     * decoration: this runs on a webhook thread that has no transaction of its own, and a
     * {@code @Modifying} query without one throws {@code TransactionRequiredException} before it
     * touches a row. It shipped without the annotation once, which did not make the hand-over
     * happen twice, it made it happen never: the answer was recorded, the message closed
     * "Answered", and the agent was never told. The throw landed in the handler's catch-all and
     * became one counter increment.
     *
     * @return the number of rows claimed; anything above zero means this caller is the one
     *         that must hand the answers over
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatAuthorizationRequestEntity r SET r.appliedAt = :now "
            + "WHERE r.conversationId = :conversationId AND r.groupKey = :groupKey "
            + "AND r.appliedAt IS NULL")
    int claimGroupForApply(@Param("conversationId") String conversationId,
                           @Param("groupKey") String groupKey,
                           @Param("now") Instant now);

    /**
     * Give a group's hand-over back, because it did not reach the conversation.
     *
     * <p>Without it a hand-over that failed was final: the claim stayed set, nothing would ever
     * try again, and the person read "Answered" while the agent never learnt the answer. Handed
     * back, the group is picked up by {@code ChatQuestionService.retryUnappliedAnswers}.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatAuthorizationRequestEntity r SET r.appliedAt = NULL "
            + "WHERE r.conversationId = :conversationId AND r.groupKey = :groupKey")
    int releaseGroupApply(@Param("conversationId") String conversationId,
                          @Param("groupKey") String groupKey);

    /**
     * Answered questions whose set was never handed over, oldest first, for the retry pass.
     *
     * <p>Only rows settled a while ago: a group whose last answer landed a second ago is being
     * handed over right now by the request that settled it, and retrying it here would race it.
     * And only rows settled recently: a set with one question that expired unanswered is never
     * complete, so without a floor its answered rows would be read again on every pass forever.
     */
    @Query("SELECT r FROM ChatAuthorizationRequestEntity r "
            + "WHERE r.kind <> com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestKind.APPROVAL "
            + "AND r.status = com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus.RESOLVED "
            + "AND r.appliedAt IS NULL AND r.decidedAt < :settledBefore AND r.decidedAt > :settledAfter "
            + "ORDER BY r.decidedAt ASC")
    List<ChatAuthorizationRequestEntity> findUnappliedAnswers(@Param("settledBefore") Instant settledBefore,
                                                              @Param("settledAfter") Instant settledAfter,
                                                              Pageable page);

    /**
     * The live question a Telegram reply is replying to.
     *
     * <p>A reply names the message it answers and nothing else, so this pair is the only way
     * back to the row. Restricted to SENT because a settled row must never match a late reply:
     * the person would be told their answer counted when it changed nothing.
     */
    List<ChatAuthorizationRequestEntity> findByChatIdAndMessageIdAndStatus(
            String chatId, String messageId, RequestStatus status);

    /**
     * Take ownership of a live request, atomically.
     *
     * <p>Without it two presses (a double tap, two members of a group, a provider retry
     * after a slow acknowledgement) both read a {@code SENT} row, both pass the terminal
     * check and both answer the conversation - the second one writing a standing grant
     * for a call that is no longer parked. The conditional UPDATE lets exactly one
     * through; the loser is told it was already decided.
     *
     * <p><b>The deadline is part of the claim, not only of the sweep.</b> The sweep runs on a
     * timer and takes 200 rows a pass, so a message whose TTL has passed can still be sitting
     * in a chat with live buttons. Without the {@code expiresAt} predicate a press hours or
     * days late claimed that row and authorized the action, while the agent had already been
     * told its question expired and had, in an unattended run, quite possibly asked again. A
     * deadline enforced only by a scheduler is a deadline the person cannot rely on, so it is
     * enforced here, where the decision is actually taken.
     *
     * <p>Something else leans on this predicate: {@code AgentAuthorizationChannelService.retire}
     * flips a row to EXPIRED with a plain save, not a CAS, and it is safe because a press racing
     * that write is refused HERE anyway. Removing or loosening {@code expiresAt > :now} would take
     * that away silently, with no test on the retire side to notice.
     *
     * @param now the instant the press is being applied, passed in rather than read from the
     *            database clock so the caller and this predicate agree on one deadline
     * @return 1 when this caller claimed it, 0 when somebody else already had, or it expired
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatAuthorizationRequestEntity r SET r.status = com.apimarketplace.orchestrator"
            + ".domain.channel.ChatAuthorizationRequestEntity.RequestStatus.RESOLVED "
            + "WHERE r.id = :id AND r.status = com.apimarketplace.orchestrator.domain.channel"
            + ".ChatAuthorizationRequestEntity.RequestStatus.SENT "
            + "AND r.expiresAt > :now")
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Hand a claimed request back because the decision could not be applied.
     *
     * <p>The claim has to happen BEFORE the answer is applied, or two presses both apply
     * it. So the rare failure after a successful claim has to give the row back, or a
     * question that was never answered would sit there looking decided and the duplicate
     * rule would refuse the agent's next ask for a day.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatAuthorizationRequestEntity r SET r.status = com.apimarketplace.orchestrator"
            + ".domain.channel.ChatAuthorizationRequestEntity.RequestStatus.SENT "
            + "WHERE r.id = :id AND r.status = com.apimarketplace.orchestrator.domain.channel"
            + ".ChatAuthorizationRequestEntity.RequestStatus.RESOLVED AND r.decidedAt IS NULL")
    int releaseClaim(@Param("id") UUID id);
}
