package com.apimarketplace.auth.service;

/**
 * The founder window closed between an approval's up-front check and its founder grant. Thrown
 * (not returned) so the partner code the approval already created rolls back with it; the admin
 * endpoint answers it like the up-front refusal, 409 {@code founder_closed}.
 */
public class FounderWindowClosedException extends RuntimeException {
    public FounderWindowClosedException() {
        super("founder_closed");
    }
}
