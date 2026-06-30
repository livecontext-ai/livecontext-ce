package com.apimarketplace.interfaces.service;

import java.util.List;

/**
 * Pure (no-IO) search/replace patcher for interface templates (html / css / js).
 *
 * <p>This is the "edit" model coding agents use: instead of re-sending a whole
 * template to change a few lines, the caller supplies a list of
 * {@code {oldText -> newText}} edits. Each edit names the exact current substring
 * to find and what to replace it with. Edits are applied <b>in order</b> and
 * <b>all-or-nothing</b> - if any edit fails to match, a {@link PatchException} is
 * thrown and the partially-built result is discarded (the service never writes it).
 *
 * <h2>Matching contract</h2>
 * <ul>
 *   <li>Line endings are normalized to {@code \n} on both the template and the edit
 *       strings before matching. A CRLF/LF mismatch is the single most common
 *       real-world failure (templates are stored with mixed endings); normalizing
 *       removes that whole failure class. The returned content is therefore also
 *       {@code \n}-normalized - harmless for HTML/CSS/JS.</li>
 *   <li>Matching is otherwise <b>exact</b>: no whitespace-insensitive or fuzzy
 *       fallback. A silent edit at the wrong location is worse than an explicit
 *       failure the agent can recover from (re-read the current content, copy the
 *       {@code old} string verbatim).</li>
 *   <li>With {@code replaceAll = false} (default) each edit's {@code oldText} MUST
 *       occur exactly once - zero occurrences → {@code NOT_FOUND}, more than one →
 *       {@code AMBIGUOUS} (the agent must add surrounding context or opt into
 *       {@code replaceAll}).</li>
 *   <li>With {@code replaceAll = true} every occurrence of {@code oldText} is
 *       replaced.</li>
 * </ul>
 */
public final class InterfaceTemplatePatcher {

    private InterfaceTemplatePatcher() {}

    /** One search/replace operation. */
    public record Edit(String oldText, String newText) {}

    /** Outcome: the new content + how many substrings were replaced in total. */
    public record PatchResult(String content, int replacements) {}

    /**
     * Structured failure so the tool layer can emit an agent-recoverable message
     * (which edit failed, why, and how many times the search text matched).
     */
    public static final class PatchException extends RuntimeException {
        public enum Code { EMPTY_EDITS, EMPTY_OLD, NO_OP, NOT_FOUND, AMBIGUOUS }

        private final Code code;
        private final int editIndex;   // 0-based index of the offending edit (-1 if N/A)
        private final int matchCount;  // occurrences found (0 for NOT_FOUND, n for AMBIGUOUS)

        PatchException(Code code, int editIndex, int matchCount, String message) {
            super(message);
            this.code = code;
            this.editIndex = editIndex;
            this.matchCount = matchCount;
        }

        public Code code() { return code; }
        public int editIndex() { return editIndex; }
        public int matchCount() { return matchCount; }
    }

    /**
     * Apply {@code edits} to {@code original} sequentially.
     *
     * @param original   current template content ({@code null} treated as empty string)
     * @param edits      ordered, non-empty list of search/replace operations
     * @param replaceAll if {@code false}, each edit's {@code oldText} must match exactly
     *                   once; if {@code true}, every occurrence is replaced
     * @return the patched content and the total number of replacements performed
     * @throws PatchException if the edit list is empty, or any edit has an empty/no-op
     *                        {@code oldText}, is not found, or is ambiguous
     */
    public static PatchResult apply(String original, List<Edit> edits, boolean replaceAll) {
        if (edits == null || edits.isEmpty()) {
            throw new PatchException(PatchException.Code.EMPTY_EDITS, -1, 0,
                "No edits provided. Pass a non-empty list of {old, new} edits.");
        }

        String content = normalize(original);
        int totalReplacements = 0;

        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            String oldText = normalize(edit == null ? null : edit.oldText());
            String newText = normalize(edit == null ? null : edit.newText());

            if (oldText.isEmpty()) {
                throw new PatchException(PatchException.Code.EMPTY_OLD, i, 0,
                    "Edit #" + (i + 1) + ": 'old' is empty. Provide the exact existing text to replace.");
            }
            if (oldText.equals(newText)) {
                throw new PatchException(PatchException.Code.NO_OP, i, 0,
                    "Edit #" + (i + 1) + ": 'old' and 'new' are identical - nothing to change.");
            }

            int count = countOccurrences(content, oldText);
            if (count == 0) {
                throw new PatchException(PatchException.Code.NOT_FOUND, i, 0,
                    "Edit #" + (i + 1) + ": the 'old' text was not found in the current content.");
            }
            if (count > 1 && !replaceAll) {
                throw new PatchException(PatchException.Code.AMBIGUOUS, i, count,
                    "Edit #" + (i + 1) + ": the 'old' text matches " + count + " places. "
                        + "Add surrounding context to make it unique, or set replace_all=true.");
            }

            content = replaceAll
                ? content.replace(oldText, newText)        // replaces all literal (non-regex) occurrences
                : replaceFirst(content, oldText, newText);
            totalReplacements += replaceAll ? count : 1;
        }

        return new PatchResult(content, totalReplacements);
    }

    /** Collapse CRLF and lone CR to LF so matching never trips on line-ending differences. */
    private static String normalize(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** Count non-overlapping occurrences (same semantics as {@link String#replace}). */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Replace only the first occurrence (caller guarantees at least one match). */
    private static String replaceFirst(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        return haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }
}
