package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;

/**
 * Single source of truth for freezing publisher identity into a
 * {@link WorkflowPublicationEntity} at (re)publish time.
 *
 * <p>Three publish entry points - workflow, agent, resource - used to inline
 * three near-identical blocks of (a) call AuthClient (b) null-check
 * (c) set the three columns. Replacing them with one helper guarantees the
 * fallback rules cannot diverge between paths (previous drift: workflow wrote
 * {@code null} on null displayName, agent / resource fell back to
 * {@code tenantId} - same DTO, two different stored values).
 *
 * <p>Rule for null displayName/email/avatarUrl is now uniform: persist as-is.
 * The marketplace UI is responsible for rendering an initials fallback when
 * displayName is null (sibling {@code PublisherAvatar} already does this for
 * avatars).
 */
final class PublisherProfileSnapshotter {

    private PublisherProfileSnapshotter() {}

    /**
     * Resolve the publisher identity for {@code tenantId} via {@link AuthClient}
     * and freeze it onto {@code publication}. Frontend-supplied publisher
     * fields are intentionally not consulted - the request body is untrusted.
     *
     * @throws PublisherProfileUnavailableException if auth-service cannot
     *         supply a profile (transport failure, unknown user). Fail-loud
     *         on purpose: a silent fallback would re-introduce the snapshot
     *         drift bug class the original refactor closed.
     */
    static void snapshotInto(WorkflowPublicationEntity publication,
                             AuthClient authClient,
                             String tenantId) {
        PublisherProfileDto profile = authClient.getPublisherProfile(tenantId);
        if (profile == null) {
            throw new PublisherProfileUnavailableException(tenantId);
        }
        publication.setPublisherName(profile.displayName());
        publication.setPublisherEmail(profile.email());
        publication.setPublisherAvatarUrl(profile.avatarUrl());
    }
}
