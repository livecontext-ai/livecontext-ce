package com.apimarketplace.orchestrator.services.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Embedded code executor for CE monolith mode.
 * Executes code via ProcessBuilder on the host machine.
 * <p>
 * Supports: JavaScript (node), Python (python3), Bash (bash), TypeScript (npx tsx).
 * <p>
 * Security note: This does NOT sandbox like Piston does. Acceptable for self-hosted CE
 * where the user owns the machine. NOT suitable for multi-tenant cloud environments.
 * <p>
 * Activated by: piston.embedded=true
 */
@Service
@ConditionalOnProperty(name = "piston.embedded", havingValue = "true")
public class EmbeddedCodeExecutor implements CodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedCodeExecutor.class);

    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MAX_OUTPUT_BYTES = 1_048_576; // 1 MB

    /**
     * Non-sensitive environment variables the language runtimes need to start. Everything NOT
     * listed here - notably every application secret (DB password, credential-encryption
     * key/salt, S3 secret, cloud-link key, etc.) - is stripped from the child process
     * environment in {@link #scrubEnvironment(Map)}. Matched case-insensitively so it works on
     * Windows (case-insensitive env) and Linux alike. PATH/HOME are preserved so node/python3/
     * bash/npx tsx can still be located and run.
     */
    private static final Set<String> SAFE_ENV_KEYS = Set.of(
        "PATH", "HOME", "LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "TZ",
        "TMPDIR", "TEMP", "TMP", "USER", "LOGNAME", "SHELL", "TERM",
        // Windows runtime essentials (dev hosts)
        "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "PATHEXT", "COMSPEC",
        "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "USERPROFILE",
        "APPDATA", "LOCALAPPDATA", "PROGRAMFILES", "PROGRAMDATA"
    );

    private static final Map<String, String[]> LANGUAGE_COMMANDS = Map.of(
        "javascript", new String[]{"node"},
        "js", new String[]{"node"},
        "python", new String[]{"python3"},
        "python3", new String[]{"python3"},
        "py", new String[]{"python3"},
        "bash", new String[]{"/bin/bash"},
        "sh", new String[]{"/bin/sh"},
        "typescript", new String[]{"npx", "tsx"},
        "ts", new String[]{"npx", "tsx"}
    );

    @Override
    public CodeResult execute(CodeRequest request) throws Exception {
        String language = request.language().toLowerCase().trim();
        String[] command = LANGUAGE_COMMANDS.get(language);

        if (command == null) {
            return new CodeResult(
                language, null,
                "", "Unsupported language: " + language + ". Supported: " + LANGUAGE_COMMANDS.keySet(),
                1, null, "", null, null
            );
        }

        int timeoutMs = request.runTimeoutMs() > 0 ? request.runTimeoutMs() : DEFAULT_TIMEOUT_MS;

        // Write code to a temp file (more reliable than -e for complex code)
        String extension = getExtension(language);
        Path tempFile = Files.createTempFile("lc-code-", extension);

        try {
            Files.writeString(tempFile, request.code(), StandardCharsets.UTF_8);

            List<String> cmd = new ArrayList<>(List.of(command));
            cmd.add(tempFile.toAbsolutePath().toString());

            logger.debug("Executing {}: {} chars, timeout={}ms", language, request.code().length(), timeoutMs);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);

            // SECURITY: ProcessBuilder.environment() starts as a full copy of the JVM's
            // environment, which in CE carries DB_PASSWORD, CREDENTIAL_ENCRYPTION_PASSWORD/SALT,
            // STORAGE_S3_SECRET_KEY, CLOUD_LINK_ENCRYPTION_KEY, etc. User-authored code run here
            // is untrusted relative to those secrets (a code node could otherwise read the
            // credential-encryption key and decrypt every stored credential), so strip the
            // inherited environment down to a minimal non-sensitive allowlist before launching.
            applyChildEnvironment(pb.environment());

            long startTime = System.currentTimeMillis();
            Process process = pb.start();

            // Provide stdin if present
            if (request.stdin() != null && !request.stdin().isEmpty()) {
                try (var os = process.getOutputStream()) {
                    os.write(request.stdin().getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }

            // Read stdout/stderr with overflow detection so the orchestrator-side guard
            // (CodeResult.sandboxKilled) treats overflow as FAILED instead of silently truncating.
            StreamCapture stdoutR = readWithTruncationDetection(process.getInputStream(), MAX_OUTPUT_BYTES);
            StreamCapture stderrR = readWithTruncationDetection(process.getErrorStream(), MAX_OUTPUT_BYTES);

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            String stdout = new String(stdoutR.bytes(), StandardCharsets.UTF_8);
            String stderr = new String(stderrR.bytes(), StandardCharsets.UTF_8);

            // Append distinctive markers so CodeResult.sandboxKilled() picks them up. The
            // "[lc-sandbox]" prefix avoids false positives if user code writes the literal
            // "stdout length exceeded" string to stderr itself.
            if (stdoutR.truncated()) {
                stderr = stderr + (stderr.isEmpty() ? "" : "\n") + "[lc-sandbox] stdout length exceeded";
            }
            if (stderrR.truncated()) {
                stderr = stderr + (stderr.isEmpty() ? "" : "\n") + "[lc-sandbox] stderr length exceeded";
            }

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                long elapsed = System.currentTimeMillis() - startTime;
                logger.warn("Code execution timed out after {}ms for language={}", elapsed, language);
                return new CodeResult(
                    language, null,
                    stdout, stderr + "\n[TIMEOUT] Execution exceeded " + timeoutMs + "ms",
                    124, "SIGKILL",
                    stdout + stderr, null, null
                );
            }

            int exitCode = process.exitValue();
            long elapsed = System.currentTimeMillis() - startTime;
            logger.debug("Code execution completed: language={}, exitCode={}, elapsed={}ms", language, exitCode, elapsed);

            return new CodeResult(
                language, null,
                stdout, stderr,
                exitCode, null,
                stdout + (stderr.isEmpty() ? "" : "\n" + stderr),
                null, null
            );

        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Best effort cleanup
            }
        }
    }

    /**
     * Builds the child-process environment in place: strips the inherited (secret-bearing) JVM
     * environment down to {@link #SAFE_ENV_KEYS}, then re-applies the runtime resource limit.
     * Ordering is load-bearing - {@code NODE_OPTIONS} must be set AFTER the scrub, or the scrub
     * would remove it. This is the single entry point used by {@link #execute(CodeRequest)} so the
     * ordering invariant has one place to hold (and one place to test).
     */
    static void applyChildEnvironment(Map<String, String> env) {
        scrubEnvironment(env);
        env.put("NODE_OPTIONS", "--max-old-space-size=256");
    }

    /**
     * Replaces the inherited process environment with a minimal, non-sensitive allowlist
     * ({@link #SAFE_ENV_KEYS}). The inherited environment is a copy of the JVM's, which in CE
     * carries application secrets; everything outside the allowlist (every secret) is removed
     * so untrusted user code cannot read it. PATH/HOME survive so the language runtimes can
     * still start. The map is mutated in place.
     */
    static void scrubEnvironment(Map<String, String> env) {
        Map<String, String> inherited = new HashMap<>(env);
        env.clear();
        for (Map.Entry<String, String> entry : inherited.entrySet()) {
            if (isSafeEnvKey(entry.getKey())) {
                env.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static boolean isSafeEnvKey(String key) {
        if (key == null) {
            return false;
        }
        for (String safe : SAFE_ENV_KEYS) {
            if (safe.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private String getExtension(String language) {
        return switch (language) {
            case "javascript", "js" -> ".js";
            case "python", "python3", "py" -> ".py";
            case "bash", "sh" -> ".sh";
            case "typescript", "ts" -> ".ts";
            default -> ".tmp";
        };
    }

    /**
     * Reads up to {@code maxBytes} from {@code stream} and signals whether the source had more
     * data than the cap allowed. We read {@code maxBytes + 1} so that, if the stream had exactly
     * {@code maxBytes}, we don't falsely flag truncation.
     */
    static StreamCapture readWithTruncationDetection(InputStream stream, int maxBytes) throws IOException {
        byte[] raw = stream.readNBytes(maxBytes + 1);
        if (raw.length > maxBytes) {
            return new StreamCapture(Arrays.copyOf(raw, maxBytes), true);
        }
        return new StreamCapture(raw, false);
    }

    record StreamCapture(byte[] bytes, boolean truncated) {}
}
