package com.apimarketplace.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * In-memory EventBus implementation for CE monolith mode.
 * Uses a simple ConcurrentHashMap for channel subscriptions.
 * All dispatch is asynchronous via a thread pool.
 */
public class InMemoryEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final ConcurrentHashMap<String, Set<Consumer<String>>> channelListeners = new ConcurrentHashMap<>();
    private final Set<PatternEntry> patternListeners = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;

    public InMemoryEventBus() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void publish(String channel, String message) {
        // Dispatch to exact channel subscribers
        Set<Consumer<String>> listeners = channelListeners.get(channel);
        if (listeners != null) {
            for (Consumer<String> listener : listeners) {
                executor.submit(() -> {
                    try {
                        listener.accept(message);
                    } catch (Exception e) {
                        log.error("Error dispatching event on channel '{}': {}", channel, e.getMessage(), e);
                    }
                });
            }
        }

        // Dispatch to pattern subscribers
        for (PatternEntry entry : patternListeners) {
            if (entry.matches(channel)) {
                executor.submit(() -> {
                    try {
                        entry.listener.onMessage(channel, message);
                    } catch (Exception e) {
                        log.error("Error dispatching pattern event on channel '{}': {}", channel, e.getMessage(), e);
                    }
                });
            }
        }
    }

    @Override
    public Subscription subscribe(String channel, Consumer<String> listener) {
        Set<Consumer<String>> listeners = channelListeners.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>());
        listeners.add(listener);
        log.debug("Subscribed to channel '{}'", channel);

        return () -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                channelListeners.remove(channel, listeners);
            }
            log.debug("Unsubscribed from channel '{}'", channel);
        };
    }

    @Override
    public PatternSubscription subscribePattern(String pattern, PatternListener listener) {
        PatternEntry entry = new PatternEntry(pattern, listener);
        patternListeners.add(entry);
        log.debug("Subscribed to pattern '{}'", pattern);

        return () -> {
            patternListeners.remove(entry);
            log.debug("Unsubscribed from pattern '{}'", pattern);
        };
    }

    /**
     * Internal entry for pattern-based subscriptions.
     * Converts glob-style patterns (e.g. "ws:workflow:run:*") to regex.
     */
    private static class PatternEntry {
        final String globPattern;
        final Pattern regex;
        final PatternListener listener;

        PatternEntry(String globPattern, PatternListener listener) {
            this.globPattern = globPattern;
            this.listener = listener;
            // Convert glob * to regex .*
            String regexStr = "^" + globPattern
                    .replace(".", "\\.")
                    .replace("*", ".*") + "$";
            this.regex = Pattern.compile(regexStr);
        }

        boolean matches(String channel) {
            return regex.matcher(channel).matches();
        }
    }
}
