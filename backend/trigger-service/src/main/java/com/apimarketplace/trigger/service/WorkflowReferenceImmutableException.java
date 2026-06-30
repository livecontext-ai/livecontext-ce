package com.apimarketplace.trigger.service;

/**
 * Raised when a standalone trigger row already owns a workflow reference and a
 * caller attempts to clear or rebind it.
 */
public class WorkflowReferenceImmutableException extends RuntimeException {

    public WorkflowReferenceImmutableException(String message) {
        super(message);
    }
}
