package com.apimarketplace.common.event;

import java.util.function.Consumer;

/**
 * Abstraction for publish/subscribe messaging.
 * <p>
 * EE (microservice mode): backed by Redis Pub/Sub for cross-process communication.
 * CE (monolith mode): backed by in-memory dispatch for single-process communication.
 */
public interface EventBus {

    /**
     * Publish an event to a named channel.
     *
     * @param channel the channel name (e.g. "ws:workflow:run:{runId}")
     * @param message the message payload (typically JSON string)
     */
    void publish(String channel, String message);

    /**
     * Subscribe to a named channel.
     *
     * @param channel  the channel name to listen on
     * @param listener callback invoked for each message
     * @return a subscription handle that can be cancelled
     */
    Subscription subscribe(String channel, Consumer<String> listener);

    /**
     * Subscribe to channels matching a pattern (e.g. "ws:workflow:run:*").
     *
     * @param pattern  the channel pattern (glob-style)
     * @param listener callback invoked with (channel, message) for each message
     * @return a subscription handle that can be cancelled
     */
    default PatternSubscription subscribePattern(String pattern, PatternListener listener) {
        throw new UnsupportedOperationException("Pattern subscriptions not supported by this EventBus implementation");
    }

    /**
     * Handle for a channel subscription that can be cancelled.
     */
    interface Subscription {
        void cancel();
    }

    /**
     * Handle for a pattern subscription that can be cancelled.
     */
    interface PatternSubscription {
        void cancel();
    }

    /**
     * Listener that receives both channel name and message for pattern subscriptions.
     */
    @FunctionalInterface
    interface PatternListener {
        void onMessage(String channel, String message);
    }
}
