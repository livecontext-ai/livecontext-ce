package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chat destinations, always read within one workspace.
 *
 * <p>The default lookup is the hot one: an unattended run resolving "where do I
 * ask this workspace for permission". It filters on {@code active} as well as
 * {@code isDefault} so a destination someone deactivated stops being chosen
 * without having to also clear its default flag, which nobody would remember.
 */
@Repository
public interface ChatChannelLinkRepository extends JpaRepository<ChatChannelLinkEntity, UUID> {

    List<ChatChannelLinkEntity> findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(String organizationId);

    Optional<ChatChannelLinkEntity> findByIdAndOrganizationId(UUID id, String organizationId);

    Optional<ChatChannelLinkEntity> findByOrganizationIdAndIsDefaultTrueAndActiveTrue(String organizationId);

    Optional<ChatChannelLinkEntity> findByBotIdAndChatId(UUID botId, String chatId);

    List<ChatChannelLinkEntity> findByBotId(UUID botId);

    /**
     * Clears the workspace's current default so a new one can be set.
     *
     * <p>A bulk UPDATE rather than load-modify-save because the partial unique
     * index {@code uq_chat_channel_links_default} makes the two-row swap
     * order-sensitive: JPA would flush the new default before clearing the old
     * one and hit the constraint. Callers run this and then set the new flag in
     * the SAME transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatChannelLinkEntity l SET l.isDefault = false "
            + "WHERE l.organizationId = :organizationId AND l.isDefault = true")
    int clearDefault(@Param("organizationId") String organizationId);
}
