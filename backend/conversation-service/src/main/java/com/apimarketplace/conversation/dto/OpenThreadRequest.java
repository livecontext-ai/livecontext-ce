package com.apimarketplace.conversation.dto;

/** Body of POST /api/dm/threads - open (or fetch the existing) thread with another user. */
public record OpenThreadRequest(String otherUserId) {
}
