package com.apimarketplace.orchestrator.services.code;

/**
 * Abstraction for code execution.
 * <p>
 * EE: PistonClient (sandboxed container execution via Piston API)
 * CE: EmbeddedCodeExecutor (ProcessBuilder execution on host)
 */
public interface CodeExecutor {

    /**
     * Execute code and return the result.
     *
     * @param request execution request with language, code, timeouts
     * @return execution result with stdout, stderr, exit code
     * @throws Exception if execution fails
     */
    CodeResult execute(CodeRequest request) throws Exception;

    /**
     * Code execution request.
     */
    record CodeRequest(
        String language,
        String version,
        String code,
        String stdin,
        int runTimeoutMs,
        int compileTimeoutMs,
        long memoryLimit
    ) {}

    /**
     * Code execution result.
     */
    record CodeResult(
        String language,
        String version,
        String stdout,
        String stderr,
        int exitCode,
        String signal,
        String output,
        String compileStdout,
        String compileStderr
    ) {
        public boolean isSuccess() {
            return exitCode == 0 && signal == null && !sandboxKilled();
        }

        public boolean hasCompileError() {
            return compileStderr != null && !compileStderr.isBlank();
        }

        /**
         * True when the stderr contains a sandbox-failure marker we recognize:
         *  - "Sandbox keeper received fatal signal" - emitted by Piston EE when its keeper crashes
         *    (e.g. on stdout-cap overflow at PISTON_OUTPUT_MAX_SIZE). Specific phrase, low
         *    collision risk with user output.
         *  - "[lc-sandbox] stdout length exceeded" / "[lc-sandbox] stderr length exceeded" -
         *    emitted by EmbeddedCodeExecutor (CE) when output exceeds MAX_OUTPUT_BYTES. The
         *    "[lc-sandbox]" prefix prevents false positives if user code legitimately writes
         *    the literal string "stdout length exceeded" to stderr.
         */
        public boolean sandboxKilled() {
            if (stderr == null) return false;
            return stderr.contains("Sandbox keeper received fatal signal")
                || stderr.contains("[lc-sandbox] stdout length exceeded")
                || stderr.contains("[lc-sandbox] stderr length exceeded");
        }

        public String failureReason() {
            if (sandboxKilled()) {
                if (stderr.contains("[lc-sandbox]")) {
                    return "stdout/stderr exceeded MAX_OUTPUT_BYTES cap";
                }
                return "sandbox killed: " + stderr.trim();
            }
            if (signal != null) {
                return "killed by signal " + signal;
            }
            if (exitCode != 0) {
                String trimmedStderr = (stderr != null && !stderr.isBlank()) ? " - " + stderr.trim() : "";
                return "exit " + exitCode + trimmedStderr;
            }
            return null;
        }
    }
}
