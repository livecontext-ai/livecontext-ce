package com.apimarketplace.agent.domain;

/**
 * Stage 1a.1 - one slice of the layered system prompt.
 *
 * <p>The system prompt used to be a single concatenated string; Anthropic's
 * prompt cache can only set breakpoints at discrete positions in the request,
 * so callers now emit an ordered list of {@link SystemBlock} entries and mark
 * up to two of them as breakpoint boundaries (the remaining two of Anthropic's
 * 4-breakpoint budget are used by {@code tools} and the last history message).
 *
 * <p>Providers that don't honor breakpoints (Gemini, OpenAI, Ollama) simply
 * concatenate {@link #text()} across all blocks into their native
 * {@code systemInstruction} / first system message. Only Claude reads
 * {@link #cacheBreakpoint()} and emits a native {@code system: [...]} array
 * with {@code cache_control: ephemeral} on marked blocks.
 *
 * @param text the raw block text. Never {@code null}; callers emit empty
 *             strings for optional sections and the serializer skips them.
 * @param cacheBreakpoint whether Claude should close a prompt-cache segment
 *             at the end of this block. Ignored by non-Claude providers.
 */
public record SystemBlock(String text, boolean cacheBreakpoint) {

    public SystemBlock {
        if (text == null) {
            throw new IllegalArgumentException("SystemBlock.text must not be null");
        }
    }

    /**
     * Shortcut for a non-breakpoint block - the common case.
     */
    public static SystemBlock of(String text) {
        return new SystemBlock(text, false);
    }

    /**
     * Shortcut for a breakpoint-closing block - used on the static base prompt
     * and at the end of the skills tree per the Stage 1a.1 layout.
     */
    public static SystemBlock breakpoint(String text) {
        return new SystemBlock(text, true);
    }

    public boolean isBlank() {
        return text.isBlank();
    }
}
