package com.apimarketplace.conversation.exception;

/**
 * Exception lancee lorsqu'une operation est tentee sur une conversation inactive.
 */
public class ConversationInactiveException extends RuntimeException {

    public ConversationInactiveException(String conversationId) {
        super("Conversation is inactive: " + conversationId);
    }
}
