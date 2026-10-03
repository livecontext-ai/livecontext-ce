package com.apimarketplace.common.classification;

import java.util.Locale;
import java.util.Map;

/**
 * Sensitivity tag carried by a stored payload, a conversation tool result or an agent
 * execution.
 *
 * <p>Two values only, on purpose. The one distinction the platform must be able to make is
 * "Google restricted-scope user data (Gmail, Drive) or not", because that is the data the
 * Google API Services User Data Policy (Limited Use) governs and the data a CASA assessor asks
 * to see the controls for. Which integrations produce it is decided in exactly one place,
 * {@link RestrictedDataPolicy}.
 *
 * <p>The persisted form is the enum name ({@code NORMAL} / {@code RESTRICTED}) in a
 * {@code data_sensitivity} column whose database default is {@code NORMAL}.
 *
 * <p>Introduced for LC-066 (CASA remediation).
 */
public enum DataSensitivity {

    /** Ordinary platform data: a web-search result, a public API response, a transform output. */
    NORMAL,

    /**
     * Derived from an integration that reads Google restricted-scope data. Bounded retention,
     * hard deletion, no full-text index, no payload logging, and only allow-listed LLM
     * processors may receive it.
     */
    RESTRICTED;

    /**
     * Key under which the tag travels in an execution's credentials map (the map every
     * agent execution and tool call already carries). Namespaced like the other internal
     * markers so a user-supplied credential can never collide with it.
     */
    public static final String CREDENTIAL_KEY = "__dataSensitivity__";

    public boolean isRestricted() {
        return this == RESTRICTED;
    }

    /** The more sensitive of the two. Classification only ever ratchets up. */
    public DataSensitivity max(DataSensitivity other) {
        return (this == RESTRICTED || other == RESTRICTED) ? RESTRICTED : NORMAL;
    }

    /** Body field a calling service forwards the tag in, when it relays a tool call. */
    public static final String REQUEST_FIELD = "dataSensitivity";

    /**
     * Restores the tag a calling service forwarded with a relayed tool call (Gmail / Drive content
     * in the caller's context) into the execution credentials the tool runs with. Only RESTRICTED
     * is honoured: a request body can tighten what a tool does, never relax it.
     */
    public static void restoreForwardedTag(Map<String, ?> request, Map<String, Object> credentials) {
        if (request != null && credentials != null && parse(request.get(REQUEST_FIELD)).isRestricted()) {
            credentials.put(CREDENTIAL_KEY, RESTRICTED.name());
        }
    }

    /**
     * Parse a persisted or wire value. Null, blank and unknown values are {@link #NORMAL}: a row
     * written before the column existed must stay readable.
     */
    public static DataSensitivity parse(Object value) {
        if (value instanceof DataSensitivity sensitivity) {
            return sensitivity;
        }
        if (value instanceof Boolean flag) {
            return flag ? RESTRICTED : NORMAL;
        }
        if (value == null) {
            return NORMAL;
        }
        String text = value.toString().trim().toUpperCase(Locale.ROOT);
        return "RESTRICTED".equals(text) || "TRUE".equals(text) ? RESTRICTED : NORMAL;
    }

    /** Tag carried by an execution's credentials map, {@link #NORMAL} when absent. */
    public static DataSensitivity fromCredentials(Map<String, ?> credentials) {
        if (credentials == null) {
            return NORMAL;
        }
        return parse(credentials.get(CREDENTIAL_KEY));
    }
}
