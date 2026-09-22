package com.apimarketplace.orchestrator.controllers.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One person who edited a resource, as shown in the resource-info popover.
 *
 * <p>An editor is a PERSON, not an edit: several saves by the same user collapse into a
 * single row carrying their most recent one ({@code editedAt}) plus how many they made
 * ({@code editCount}). A list of edits would be a changelog; the question this answers is
 * "who has been working on this". The version NUMBER is deliberately not carried: the
 * version drawer is where a version is a thing you act on, and shipping a field nothing
 * renders invites a reader to believe it is load-bearing.
 *
 * <p>{@code displayName} is a best-effort fallback only. The frontend resolves a name and
 * an avatar from the workspace roster, which is the richer source (the batch user resolver
 * carries no avatar). It is carried here so an editor who has since LEFT the workspace -
 * absent from that roster, and precisely the case where "who touched this" matters - still
 * reads as a name instead of "former member". Null when the lookup failed or the account
 * is gone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceEditorDto(
        String userId,
        String displayName,
        /** When this editor's most recent save happened. */
        Instant editedAt,
        /** How many stored versions this editor wrote. Bounded by the retention window. */
        int editCount
) {
}
