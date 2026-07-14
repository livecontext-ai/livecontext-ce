package com.apimarketplace.agent.service.execution;

/**
 * Shared helper for stripping markdown fences and extracting the first well-formed JSON
 * object from an LLM response. Used by {@link ClassifyService} and {@link GuardrailService}
 * for structured single-shot completions.
 *
 * <h3>Why the naïve indexOf('{') approach fails</h3>
 * Some models (notably Gemini 2.5+) prefix the answer with inline reasoning text that can
 * contain stray braces before the actual JSON object, e.g.:
 * <pre>
 *   Let me think... {intermediate step}
 *   {"selected_category": "finance", ...}
 * </pre>
 * {@code indexOf('{')} would pick up the first {@code {}, producing {@code {{intermediate…}
 * which is invalid JSON and causes a Jackson parse error on the outer caller.
 *
 * <h3>Fix: outermost-balanced extraction</h3>
 * We scan forward until we find a {@code {} that, when followed by its balanced closing
 * {@code }}, produces a valid candidate substring. The LAST character we accept is the
 * {@code }} that brings the depth back to zero.  This naturally handles nested objects in
 * the response payload (reasoning strings with braces, etc.).
 */
final class LlmJsonExtractor {

    private LlmJsonExtractor() {}

    /**
     * Strip markdown fences, then extract the first complete (balanced) JSON object.
     *
     * <p>Returns the trimmed original content when no {@code {...}} is found (the caller
     * will then fail with a clear Jackson error rather than an AIOOB or silent truncation).
     */
    static String extractJson(String content) {
        content = content.trim();

        // Strip markdown code fences  ```json … ``` or ``` … ```
        if (content.startsWith("```json")) {
            content = content.substring(7).trim();
        } else if (content.startsWith("```")) {
            content = content.substring(3).trim();
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3).trim();
        }

        // Find the first balanced JSON object by counting brace depth.
        // This avoids picking up stray { in preamble text (thinking output, disclaimers, …).
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    // Found a balanced object - return it
                    return content.substring(start, i + 1).trim();
                }
                if (depth < 0) {
                    // A stray unmatched '}' in the preamble (e.g. `} {"x":1}`) would otherwise
                    // poison the scan: depth never returns to 0, so a well-formed object later
                    // is never extracted. Reset so scanning resumes from the next '{'.
                    depth = 0;
                    start = -1;
                }
            }
        }

        // No balanced object found - return as-is (will produce a clear parse error upstream)
        return content;
    }
}
