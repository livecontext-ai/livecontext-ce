package com.apimarketplace.agent.dto;

import java.util.List;

/**
 * @param content           the note body
 * @param mentionedUserIds  auth user ids @-mentioned in the note (F11); each
 *                          workspace member is notified. {@code null} = none.
 */
public record AddNoteRequest(String content, List<String> mentionedUserIds) {

    /** Back-compat for callers that don't mention anyone. */
    public AddNoteRequest(String content) {
        this(content, null);
    }
}
