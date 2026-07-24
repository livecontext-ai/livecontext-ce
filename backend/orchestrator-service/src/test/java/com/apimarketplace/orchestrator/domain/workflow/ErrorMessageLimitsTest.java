package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the contract of {@link ErrorMessageLimits#truncate(String)} - the
 * centralised cap shared by {@link StepExecutionResult}'s compact constructor
 * and {@code StepMetadataBuilder}'s Exception path. These tests double as
 * regression guards for the app-host 2026-05-11 incident: any future refactor
 * that breaks fast-path identity or removes the truncation marker is caught
 * here before it ships.
 */
@DisplayName("ErrorMessageLimits")
class ErrorMessageLimitsTest {

    @Nested
    @DisplayName("truncate() fast-path (no allocation expected)")
    class FastPath {

        @Test
        @DisplayName("Returns null unchanged")
        void nullPassesThrough() {
            assertNull(ErrorMessageLimits.truncate(null));
        }

        @Test
        @DisplayName("Returns the same reference when message is empty")
        void emptyReturnedAsIs() {
            String empty = "";
            assertSame(empty, ErrorMessageLimits.truncate(empty),
                    "Empty string must hit the no-alloc identity branch");
        }

        @Test
        @DisplayName("Returns the same reference when length is well below MAX_LENGTH")
        void shortMessageReturnedAsIs() {
            String msg = "Connection timeout after 30s";
            assertSame(msg, ErrorMessageLimits.truncate(msg),
                    "Short messages must hit the no-alloc identity branch - guards "
                            + "the hot path of every successful step result");
        }

        @Test
        @DisplayName("Returns the same reference at exactly MAX_LENGTH (boundary)")
        void exactBoundaryReturnedAsIs() {
            String msg = "x".repeat(ErrorMessageLimits.MAX_LENGTH);
            assertSame(msg, ErrorMessageLimits.truncate(msg),
                    "MAX_LENGTH is inclusive - equal length must not allocate");
        }
    }

    @Nested
    @DisplayName("truncate() U+0000 strip (PG rejects NUL in the TEXT error_message column)")
    class NulStrip {

        /** The NUL codepoint, built per convention via (char) 0 - never a source escape. */
        private static final String NUL = String.valueOf((char) 0);

        @Test
        @DisplayName("removes U+0000 preserving surrounding text: a<NUL>b -> ab")
        void stripsNulPreservingSurroundingText() {
            assertEquals("ab", ErrorMessageLimits.truncate("a" + NUL + "b"),
                    "a NUL byte in a diagnostic (e.g. raw upstream bytes in an exception "
                            + "message) would otherwise 22P05 the whole step-row INSERT");
        }

        @Test
        @DisplayName("removes multiple U+0000 occurrences")
        void stripsAllOccurrences() {
            assertEquals("abc", ErrorMessageLimits.truncate(NUL + "a" + NUL + "b" + NUL + "c" + NUL));
        }

        @Test
        @DisplayName("NUL-free messages keep the no-alloc identity fast path")
        void nulFreeKeepsIdentity() {
            String msg = "plain diagnostic";
            assertSame(msg, ErrorMessageLimits.truncate(msg));
        }

        @Test
        @DisplayName("strip composes with truncation: an oversized NUL-laden message is both stripped and capped")
        void stripComposesWithTruncation() {
            // 2*MAX visible chars + NULs: still over the cap AFTER the strip,
            // so both mechanisms must fire on the same message.
            String msg = ("xy" + NUL).repeat(ErrorMessageLimits.MAX_LENGTH);
            String out = ErrorMessageLimits.truncate(msg);
            assertFalse(out.contains(NUL), "no NUL may survive");
            assertTrue(out.length() <= ErrorMessageLimits.MAX_LENGTH);
            assertTrue(out.contains("…[truncated, was "), "truncation marker must still fire");
        }

        @Test
        @DisplayName("a NUL-laden message that fits the cap AFTER stripping is stripped but NOT truncated")
        void strippedMessageWithinCapNotTruncated() {
            String msg = ("x" + NUL).repeat(ErrorMessageLimits.MAX_LENGTH);
            String out = ErrorMessageLimits.truncate(msg);
            assertFalse(out.contains(NUL));
            assertEquals(ErrorMessageLimits.MAX_LENGTH, out.length(),
                    "post-strip length is exactly MAX_LENGTH - inclusive cap, no marker");
            assertFalse(out.contains("…[truncated"), "no spurious truncation of an in-budget message");
        }
    }

    @Nested
    @DisplayName("truncate() slow-path (cap enforced)")
    class SlowPath {

        @Test
        @DisplayName("Truncates one char over the limit and appends suffix")
        void oneCharOverIsTruncated() {
            int originalLen = ErrorMessageLimits.MAX_LENGTH + 1;
            String msg = "y".repeat(originalLen);

            String result = ErrorMessageLimits.truncate(msg);

            assertNotSame(msg, result);
            assertTrue(result.length() <= ErrorMessageLimits.MAX_LENGTH,
                    "Output must respect the cap");
            assertTrue(result.endsWith("…[truncated, was " + originalLen + " chars]"),
                    "Suffix must record the original length so operators can tell "
                            + "the message was clipped - actual tail: "
                            + result.substring(Math.max(0, result.length() - 50)));
            assertTrue(result.startsWith("y"),
                    "Prefix must be drawn from the original content, not lost");
        }

        @Test
        @DisplayName("Output respects MAX_LENGTH on extreme inputs (1 MB)")
        void extremeInputStaysUnderCap() {
            int originalLen = 1_000_000;
            String msg = "z".repeat(originalLen);

            String result = ErrorMessageLimits.truncate(msg);

            assertTrue(result.length() <= ErrorMessageLimits.MAX_LENGTH,
                    "1 MB input must not produce a >MAX_LENGTH output - measured "
                            + result.length());
            assertTrue(result.contains("was " + originalLen + " chars"),
                    "Suffix must mention the true original length");
        }

        @Test
        @DisplayName("Truncation suffix is appended even on pathological-prefix-heavy input")
        void htmlPrefixIsPreserved() {
            // Simulates the actual incident: Google's 4 KB 404 HTML page wrapped
            // in a "404 Not Found" prefix from RestTemplate
            String prefix = "404 Not Found on POST request: <!DOCTYPE html><html><head>";
            String pageBody = "<style>" + "p".repeat(ErrorMessageLimits.MAX_LENGTH) + "</style>";
            String full = prefix + pageBody;

            String result = ErrorMessageLimits.truncate(full);

            assertTrue(result.startsWith(prefix),
                    "Diagnostic prefix must be preserved - operators need it to identify the failure mode");
            assertTrue(result.length() <= ErrorMessageLimits.MAX_LENGTH);
            assertTrue(result.endsWith("chars]"));
        }
    }

    @Nested
    @DisplayName("MAX_LENGTH constant")
    class CapValue {

        @Test
        @DisplayName("MAX_LENGTH is 16 384 characters - the agreed-on cap")
        void capIsSixteenK() {
            // Pin the documented value. Changing this requires re-validating
            // against the OOM 2026-05-07 incident profile and Postgres TOAST
            // behaviour - see class-level javadoc.
            assertEquals(16_384, ErrorMessageLimits.MAX_LENGTH);
        }
    }
}
