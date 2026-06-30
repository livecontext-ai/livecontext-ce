package com.apimarketplace.conversation.exception;

/**
 * Exception lancee lorsque la charge utile d'un message est invalide.
 */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
