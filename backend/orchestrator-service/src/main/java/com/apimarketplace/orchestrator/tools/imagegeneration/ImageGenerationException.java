package com.apimarketplace.orchestrator.tools.imagegeneration;

/**
 * Typed failure from an {@link ImageProvider}. {@link Kind} maps to a
 * {@link com.apimarketplace.agent.tools.ToolErrorCode} in the dispatcher
 * so the agent receives a consistent error vocabulary regardless of
 * provider.
 */
public class ImageGenerationException extends RuntimeException {

    public enum Kind {
        /** Caller-supplied {@code prompt} / {@code model} / {@code quality} is unknown or malformed. */
        INVALID_REQUEST,
        /** API key missing or rejected by upstream. */
        AUTH_FAILED,
        /** Upstream content moderation rejected the prompt or image. */
        CONTENT_BLOCKED,
        /** Upstream rate limit / quota. */
        RATE_LIMITED,
        /** Network / 5xx / unparseable response. */
        UPSTREAM_FAILED
    }

    private final Kind kind;

    public ImageGenerationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ImageGenerationException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
