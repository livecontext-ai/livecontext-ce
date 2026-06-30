package com.apimarketplace.catalog.service.http;

/**
 * Thread-bound holder for per-call credential resolution hints. Set by
 * {@link com.apimarketplace.catalog.web.CatalogV1Controller} from the request
 * DTO and read by
 * {@link HttpExecutionService#tryGetCredentialResolution(String, String,
 * com.apimarketplace.catalog.domain.ApiEntity)} and
 * {@link com.apimarketplace.catalog.service.ApiService#executeApiTool}.
 *
 * <p>Three hints flow through here:
 * <ul>
 *   <li><b>{@code explicitSource}</b> - workflow direct calls supply
 *       {@code "user"} or {@code "platform"} from the workflow node's UI
 *       toggle. Strictly honored: no fallback to the other pool.</li>
 *   <li><b>{@code selectedCredentialId}</b> - workflow direct calls with
 *       {@code explicitSource="user"} can pin a concrete user credential id.
 *       Catalog execute and agentic paths leave this null and still use
 *       default-by-integration resolution.</li>
 *   <li><b>{@code agenticOverride}</b> - agentic call paths (chat agents,
 *       image-gen) historically supplied {@code "both"} to enable
 *       user-then-platform fallback. Now an implementation detail; agentic
 *       paths can leave this null and the resolver applies the default
 *       fallback-if-priced behavior.</li>
 * </ul>
 *
 * <p>Why a thread-local: the hints are cross-cutting per-request state.
 * Threading them through {@code ApiService.executeApiTool} → {@code
 * HttpExecutionService.execute*} would touch ~6 method signatures for what
 * is conceptually request-scoped data. Catalog tool execution is fully
 * synchronous in one HTTP thread (no async hops), so the thread-local is
 * cleaned up by the controller's {@code finally} block before the response
 * leaves the service.
 *
 * <p>Mirrors the existing pattern in {@code mapping.adapter.JsonAdapter#DOC_ROOT}.
 */
public final class CredentialModeContext {

    private static final ThreadLocal<String> AGENTIC_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<String> EXPLICIT_SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Long> SELECTED_CREDENTIAL_ID = new ThreadLocal<>();

    /**
     * Whitelist of agentic-override values an external caller may supply.
     * Limited to the fallback-enabling value only - accepting
     * {@code "platform_key"} or {@code "user_key"} here would let any
     * authenticated caller force a platform-credential lookup against an API
     * whose stored mode is {@code "user_key"}, bypassing the workflow-author's
     * deliberate choice and consuming platform-funded API access without
     * paying. {@code "both"} is benign: it is a strict superset of
     * {@code "user_key"} (still tries the user's credential first) and
     * degrades to {@code "user_key"} behavior when no platform credential is
     * configured.
     *
     * <p>The explicit source path ({@link #setExplicitSource}) is a different
     * security model: it is authenticated as a workflow direct call (the
     * workflow node's UI toggle is itself gated on platform-credential
     * pricing publication), so accepting {@code "user"}/{@code "platform"}
     * from that path is safe.
     */
    private static final java.util.Set<String> AGENTIC_ALLOWED = java.util.Set.of("both");

    private static final java.util.Set<String> EXPLICIT_ALLOWED = java.util.Set.of("user", "platform");

    private CredentialModeContext() {}

    /**
     * Sets the agentic-override for the current thread, silently ignoring
     * values outside {@link #AGENTIC_ALLOWED}. Null and blank inputs clear
     * any prior value.
     */
    public static void setOverride(String mode) {
        if (mode == null || mode.isBlank()) {
            AGENTIC_OVERRIDE.remove();
            return;
        }
        if (!AGENTIC_ALLOWED.contains(mode)) {
            // Reject silently - do not echo the rejected value into logs at
            // INFO level (could be a probe). Trace at DEBUG for diagnostics.
            org.slf4j.LoggerFactory.getLogger(CredentialModeContext.class)
                    .debug("Rejected unsupported credentialModeOverride='{}'; allowed={}", mode, AGENTIC_ALLOWED);
            AGENTIC_OVERRIDE.remove();
            return;
        }
        AGENTIC_OVERRIDE.set(mode);
    }

    public static String getOverride() {
        return AGENTIC_OVERRIDE.get();
    }

    /**
     * Sets the workflow node's explicit credential source for the current
     * thread. {@code "user"} or {@code "platform"} only. Other values are
     * silently dropped. Strictly honored by the resolver: no fallback to the
     * other pool.
     */
    public static void setExplicitSource(String source) {
        if (source == null || source.isBlank()) {
            EXPLICIT_SOURCE.remove();
            return;
        }
        String normalized = source.toLowerCase(java.util.Locale.ROOT);
        if (!EXPLICIT_ALLOWED.contains(normalized)) {
            org.slf4j.LoggerFactory.getLogger(CredentialModeContext.class)
                    .debug("Rejected unsupported credentialSource='{}'; allowed={}", source, EXPLICIT_ALLOWED);
            EXPLICIT_SOURCE.remove();
            return;
        }
        EXPLICIT_SOURCE.set(normalized);
    }

    public static String getExplicitSource() {
        return EXPLICIT_SOURCE.get();
    }

    /**
     * Pins workflow user-credential resolution to a concrete credential row.
     * Only honored when {@link #getExplicitSource()} is {@code "user"}; catalog
     * execute and agentic paths continue to resolve by integration/default.
     */
    public static void setSelectedCredentialId(Long credentialId) {
        if (credentialId == null || credentialId <= 0L) {
            SELECTED_CREDENTIAL_ID.remove();
            return;
        }
        SELECTED_CREDENTIAL_ID.set(credentialId);
    }

    public static Long getSelectedCredentialId() {
        return SELECTED_CREDENTIAL_ID.get();
    }

    public static void clear() {
        AGENTIC_OVERRIDE.remove();
        EXPLICIT_SOURCE.remove();
        SELECTED_CREDENTIAL_ID.remove();
    }
}
