package com.apimarketplace.conversation.exception;

/**
 * Exception fonctionnelle lancee lorsqu'une conversation est introuvable.
 */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(String conversationId) {
        super("Conversation not found: " + conversationId);
    }
}
