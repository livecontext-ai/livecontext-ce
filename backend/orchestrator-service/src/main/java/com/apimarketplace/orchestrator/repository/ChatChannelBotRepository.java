package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Connected chat bots, always read within one workspace.
 *
 * <p>Every finder takes {@code organizationId} as its first argument rather
 * than offering a bare {@code findById}: the isolation boundary for this table
 * is the workspace, and a finder that can be called without it is one that will
 * eventually be called without it.
 */
@Repository
public interface ChatChannelBotRepository extends JpaRepository<ChatChannelBotEntity, UUID> {

    List<ChatChannelBotEntity> findByOrganizationIdOrderByCreatedAtAsc(String organizationId);

    Optional<ChatChannelBotEntity> findByIdAndOrganizationId(UUID id, String organizationId);

    Optional<ChatChannelBotEntity> findByOrganizationIdAndChannelAndCredentialId(
            String organizationId, String channel, Long credentialId);

    /**
     * The one unscoped read, for a provider's callback, which arrives with no session and names its
     * bot only by the row id in its URL. It returns the key the callback must then verify against,
     * so it hands out nothing a caller could use without also holding the provider's signature.
     */
    Optional<ChatChannelBotEntity> findByIdAndChannel(UUID id, String channel);

    /**
     * The bot a credential backs, for a connector whose sends need something only the row holds
     * (WhatsApp's sending number). Unscoped because a connector call carries a credential, not a
     * workspace; the credential itself was already resolved for the caller.
     */
    Optional<ChatChannelBotEntity> findFirstByChannelAndCredentialId(String channel, Long credentialId);
}
