package com.apimarketplace.orchestrator.services.code;

import com.apimarketplace.orchestrator.services.code.EmbeddedCodeExecutor.StreamCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmbeddedCodeExecutor's truncation-detection helper.
 *
 * Why this matters: when a child process emits >MAX_OUTPUT_BYTES the executor must signal it
 * via the "stdout length exceeded" / "stderr length exceeded" marker so the orchestrator-side
 * guardrail (CodeResult.sandboxKilled) marks the node FAILED. Without the marker, exit=0 with
 * silently-truncated output looks like success and downstream nodes consume an empty/partial
 * result - same bug class as the prod Piston SIGABRT issue (run_<id>, 2026-04-30).
 */
@DisplayName("EmbeddedCodeExecutor.readWithTruncationDetection")
class EmbeddedCodeExecutorTest {

    @Nested
    @DisplayName("Truncation Detection")
    class TruncationDetection {

        @Test
        @DisplayName("Returns truncated=false when stream is shorter than cap")
        void noTruncationWhenStreamShorterThanCap() throws Exception {
            byte[] data = "hello world".getBytes();
            ByteArrayInputStream stream = new ByteArrayInputStream(data);

            StreamCapture result = EmbeddedCodeExecutor.readWithTruncationDetection(stream, 1024);

            assertFalse(result.truncated());
            assertArrayEquals(data, result.bytes());
        }

        @Test
        @DisplayName("Returns truncated=false when stream length is exactly the cap (boundary)")
        void noTruncationAtExactCap() throws Exception {
            byte[] data = new byte[1000];
            Arrays.fill(data, (byte) 'A');
            ByteArrayInputStream stream = new ByteArrayInputStream(data);

            StreamCapture result = EmbeddedCodeExecutor.readWithTruncationDetection(stream, 1000);

            assertFalse(result.truncated(),
                "stream of exactly cap bytes must not be flagged truncated");
            assertEquals(1000, result.bytes().length);
        }

        @Test
        @DisplayName("Returns truncated=true and caps bytes when stream exceeds cap")
        void truncationDetectedAndBytesCappedWhenStreamExceedsCap() throws Exception {
            byte[] data = new byte[1500];
            Arrays.fill(data, (byte) 'B');
            ByteArrayInputStream stream = new ByteArrayInputStream(data);

            StreamCapture result = EmbeddedCodeExecutor.readWithTruncationDetection(stream, 1000);

            assertTrue(result.truncated(),
                "stream beyond cap must be flagged truncated so orchestrator guard triggers");
            assertEquals(1000, result.bytes().length, "bytes must be capped at exactly maxBytes");
        }

        @Test
        @DisplayName("Returns truncated=false on empty stream")
        void noTruncationOnEmptyStream() throws Exception {
            ByteArrayInputStream stream = new ByteArrayInputStream(new byte[0]);

            StreamCapture result = EmbeddedCodeExecutor.readWithTruncationDetection(stream, 100);

            assertFalse(result.truncated());
            assertEquals(0, result.bytes().length);
        }
    }

    /**
     * Why this matters: ProcessBuilder.environment() starts as a full copy of the JVM env, which
     * in CE carries DB_PASSWORD, CREDENTIAL_ENCRYPTION_PASSWORD/SALT, STORAGE_S3_SECRET_KEY,
     * CLOUD_LINK_ENCRYPTION_KEY, etc. User-authored code runs untrusted relative to those secrets
     * (a code node could read the credential-encryption key and decrypt every stored credential).
     * scrubEnvironment must strip everything outside the non-sensitive allowlist before launch.
     * Each test below fails on the pre-fix executor, which passed the full inherited env through.
     */
    @Nested
    @DisplayName("Environment scrubbing")
    class EnvironmentScrubbing {

        @Test
        @DisplayName("Removes application secrets from the inherited environment")
        void removesApplicationSecrets() {
            Map<String, String> env = new HashMap<>();
            env.put("DB_PASSWORD", "supersecret");
            env.put("CREDENTIAL_ENCRYPTION_PASSWORD", "enc-key");
            env.put("CREDENTIAL_ENCRYPTION_SALT", "enc-salt");
            env.put("STORAGE_S3_SECRET_KEY", "s3-secret");
            env.put("CLOUD_LINK_ENCRYPTION_KEY", "cl-key");
            env.put("STRIPE_SECRET_KEY", "sk_live_xxx");
            env.put("SOME_RANDOM_TOKEN", "tok");

            EmbeddedCodeExecutor.scrubEnvironment(env);

            assertFalse(env.containsKey("DB_PASSWORD"), "DB password must be stripped");
            assertFalse(env.containsKey("CREDENTIAL_ENCRYPTION_PASSWORD"));
            assertFalse(env.containsKey("CREDENTIAL_ENCRYPTION_SALT"));
            assertFalse(env.containsKey("STORAGE_S3_SECRET_KEY"));
            assertFalse(env.containsKey("CLOUD_LINK_ENCRYPTION_KEY"));
            assertFalse(env.containsKey("STRIPE_SECRET_KEY"));
            assertFalse(env.containsKey("SOME_RANDOM_TOKEN"),
                "anything outside the allowlist must be stripped, not just known secret names");
        }

        @Test
        @DisplayName("Preserves PATH and HOME so the language runtimes can start")
        void preservesRuntimeEssentials() {
            Map<String, String> env = new HashMap<>();
            env.put("PATH", "/usr/local/bin:/usr/bin");
            env.put("HOME", "/home/app");
            env.put("DB_PASSWORD", "supersecret");

            EmbeddedCodeExecutor.scrubEnvironment(env);

            assertEquals("/usr/local/bin:/usr/bin", env.get("PATH"));
            assertEquals("/home/app", env.get("HOME"));
            assertFalse(env.containsKey("DB_PASSWORD"));
        }

        @Test
        @DisplayName("Allowlist match is case-insensitive (Windows env compatibility)")
        void allowlistIsCaseInsensitive() {
            Map<String, String> env = new HashMap<>();
            env.put("Path", "C:\\Windows\\System32");
            env.put("SystemRoot", "C:\\Windows");
            env.put("DB_PASSWORD", "supersecret");

            EmbeddedCodeExecutor.scrubEnvironment(env);

            assertEquals("C:\\Windows\\System32", env.get("Path"), "Path must survive regardless of case");
            assertEquals("C:\\Windows", env.get("SystemRoot"));
            assertFalse(env.containsKey("DB_PASSWORD"));
        }

        @Test
        @DisplayName("An already-empty environment stays empty")
        void emptyEnvironmentStaysEmpty() {
            Map<String, String> env = new HashMap<>();

            EmbeddedCodeExecutor.scrubEnvironment(env);

            assertTrue(env.isEmpty());
        }

        @Test
        @DisplayName("applyChildEnvironment strips secrets AND keeps NODE_OPTIONS (ordering invariant)")
        void applyChildEnvironmentStripsSecretsAndKeepsNodeOptions() {
            // Pins the ordering bug class: NODE_OPTIONS must be applied AFTER the scrub. If a future
            // edit reorders them (put NODE_OPTIONS, then scrub) this test fails - NODE_OPTIONS would
            // be stripped. Also guards that the executor's single env entry point still scrubs.
            Map<String, String> env = new HashMap<>();
            env.put("DB_PASSWORD", "supersecret");
            env.put("PATH", "/usr/bin");

            EmbeddedCodeExecutor.applyChildEnvironment(env);

            assertFalse(env.containsKey("DB_PASSWORD"), "secrets must still be stripped");
            assertEquals("/usr/bin", env.get("PATH"), "runtime PATH must survive");
            assertEquals("--max-old-space-size=256", env.get("NODE_OPTIONS"),
                "NODE_OPTIONS must survive the scrub (applied after it)");
        }
    }
}
