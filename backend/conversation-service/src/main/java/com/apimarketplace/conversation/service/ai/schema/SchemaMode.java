package com.apimarketplace.conversation.service.ai.schema;

/**
 * Stage 4a.2 - which form of the tools-prefix to ship to the LLM for a
 * given conversation turn. Decouples the routing decision from the
 * {@link SchemaSlimmer} primitive so callers that need the full schema
 * (weak LLMs, exclusion-listed actions) can opt out at a single layer.
 *
 * <ul>
 *   <li>{@link #SLIM} - names-only slim form from {@link SchemaSlimmer}.
 *   The LLM is told to call {@code <tool>(action='help', topic=...)} before
 *   first use. Saves ~90% of the tools-prefix tokens on top/high/mid-tier
 *   models that can bootstrap from a directive.</li>
 *   <li>{@link #FULL} - the untouched {@code ToolDefinition} with full
 *   parameter schemas, enums, descriptions, nested object properties. Used
 *   for models that cannot bootstrap from a help directive (weak-tier or
 *   misbehaving high-tier models like {@code glm-5-turbo}) and for
 *   security-sensitive actions (Stage 4a.3) that must not rely on the
 *   agent discovering required params at runtime.</li>
 * </ul>
 *
 * <p><b>Fail-safe default:</b> {@link #FULL}. Unknown models, null tiers,
 * and misconfigured mappings all resolve to full schema rather than slim,
 * because slim mode assumes the LLM has the help directive working - a
 * wrong guess here would regress tool accuracy, whereas a wrong guess
 * toward FULL just wastes tokens.
 */
public enum SchemaMode {
    SLIM,
    FULL
}
