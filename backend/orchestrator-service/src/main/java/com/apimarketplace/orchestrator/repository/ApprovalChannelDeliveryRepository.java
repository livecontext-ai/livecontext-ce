package com.apimarketplace.orchestrator.repository;


import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for delegated-approval channel deliveries (approval_channel_deliveries, V393).
 *
 * The inbound click handler resolves a button callback with
 * {@link #findByCallbackToken}; the post-resolution editors fan out over
 * {@link #findBySignalWaitIdInAndStatus}. Insert idempotency (event replays,
 * multi-replica) is enforced by the {@code UNIQUE (signal_wait_id, channel)}
 * constraint, checked by the emitter before sending.
 */
@Repository
public interface ApprovalChannelDeliveryRepository
        extends JpaRepository<ApprovalChannelDeliveryEntity, Long> {

    /** Lookup by HMAC of the plaintext callback token (TokenAtRest.hash); the column itself is encrypted. */
    Optional<ApprovalChannelDeliveryEntity> findByCallbackTokenHash(String callbackTokenHash);

    Optional<ApprovalChannelDeliveryEntity> findBySignalWaitIdAndChannel(Long signalWaitId, String channel);

    List<ApprovalChannelDeliveryEntity> findBySignalWaitIdInAndStatus(
            Collection<Long> signalWaitIds, DeliveryStatus status);

    /**
     * Idempotent creation: inserts the PENDING delivery row unless one already
     * exists for (signal_wait_id, channel). Returns 1 when this call inserted the
     * row (the caller then owns the channel send) and 0 on replay/replica races
     * (the caller must NOT send again). Mirrors the notifications ON CONFLICT
     * pattern; the unique constraint is the arbiter, not a read-then-write.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO orchestrator.approval_channel_deliveries
            (signal_wait_id, channel, callback_token, callback_token_hash, status, tenant_id, org_id, run_id, node_id,
             item_id, epoch, credential_id, chat_id, allowed_user_ids, created_at)
        VALUES (:signalWaitId, :channel, :callbackTokenEncrypted, :callbackTokenHash, 'PENDING', :tenantId, :orgId, :runId, :nodeId,
             :itemId, :epoch, :credentialId, :chatId, CAST(:allowedUserIdsJson AS jsonb), :now)
        ON CONFLICT (signal_wait_id, channel) DO NOTHING
        """, nativeQuery = true)
    int insertPendingIfAbsent(
            @Param("signalWaitId") Long signalWaitId,
            @Param("channel") String channel,
            // Native SQL bypasses the entity converter and listener: the caller passes the
            // ciphertext (TokenAtRest.encrypt) and the hash (TokenAtRest.hash) explicitly.
            @Param("callbackTokenEncrypted") String callbackTokenEncrypted,
            @Param("callbackTokenHash") String callbackTokenHash,
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("runId") String runId,
            @Param("nodeId") String nodeId,
            @Param("itemId") String itemId,
            @Param("epoch") int epoch,
            @Param("credentialId") Long credentialId,
            @Param("chatId") String chatId,
            @Param("allowedUserIdsJson") String allowedUserIdsJson,
            @Param("now") Instant now);

    /**
     * READ-ONLY plaintext match for a row written before 2026-09-17 (token in clear, no hash).
     * Native on purpose: a JPQL comparison would convert the parameter through the encrypting
     * converter. Rewrites nothing; the delayed startup backfill does. Gated by the service on
     * {@code PlaintextTokenBackfill.mayHaveLegacyRows}.
     */
    @Query(value = "SELECT * FROM orchestrator.approval_channel_deliveries WHERE callback_token = :plain AND callback_token_hash IS NULL", nativeQuery = true)
    Optional<ApprovalChannelDeliveryEntity> findLegacyPlaintext(@Param("plain") String plain);
}
