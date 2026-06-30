package com.apimarketplace.orchestrator.services.code;

import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeRequest;
import com.apimarketplace.orchestrator.services.code.CodeExecutor.CodeResult;
import com.apimarketplace.orchestrator.services.code.PistonClient.PistonRequest;
import com.apimarketplace.orchestrator.services.code.PistonClient.PistonResponse;
import com.apimarketplace.orchestrator.services.code.PistonClient.PistonRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PistonClient.
 * Verifies HTTP communication with the Piston code execution engine.
 *
 * Uses a lightweight JDK HttpServer to handle real HTTP requests since
 * PistonClient.execute() uses HttpURLConnection internally.
 */
@DisplayName("PistonClient")
class PistonClientTest {

    private HttpServer server;
    private ObjectMapper objectMapper;
    private PistonClient pistonClient;
    private HttpClient httpClient;

    private String pistonUrl;

    private static final int DEFAULT_TIMEOUT = 10000;
    private static final long DEFAULT_MEMORY = 128000000L;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        httpClient = HttpClient.newHttpClient();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        int port = server.getAddress().getPort();
        pistonUrl = "http://localhost:" + port;
        pistonClient = new PistonClient(objectMapper, httpClient, pistonUrl, DEFAULT_TIMEOUT, DEFAULT_MEMORY);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Helper to set up the mock server to respond with a given status and body
     * on the /api/v2/execute endpoint.
     */
    private AtomicReference<String> stubExecuteEndpoint(int statusCode, String responseBody) {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext("/api/v2/execute", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        return receivedBody;
    }

    // ===============================================================
    // PistonRequest record tests
    // ===============================================================

    @Nested
    @DisplayName("PistonRequest")
    class PistonRequestTests {

        @Test
        @DisplayName("Should create request with all fields")
        void shouldCreateRequestWithAllFields() {
            PistonRequest request = new PistonRequest("javascript", "18.15.0",
                "console.log('hello')", "input", 5000, 5000, 64000000L);

            assertEquals("javascript", request.language());
            assertEquals("18.15.0", request.version());
            assertEquals("console.log('hello')", request.code());
            assertEquals("input", request.stdin());
            assertEquals(5000, request.runTimeoutMs());
            assertEquals(5000, request.compileTimeoutMs());
            assertEquals(64000000L, request.memoryLimit());
        }

        @Test
        @DisplayName("Should allow null version")
        void shouldAllowNullVersion() {
            PistonRequest request = new PistonRequest("python", null,
                "print('hi')", null, 0, 0, 0);

            assertNull(request.version());
            assertNull(request.stdin());
        }
    }

    // ===============================================================
    // PistonResponse / PistonRun record tests
    // ===============================================================

    @Nested
    @DisplayName("PistonResponse")
    class PistonResponseTests {

        @Test
        @DisplayName("Should create response with compile and run")
        void shouldCreateResponseWithCompileAndRun() {
            PistonRun compile = new PistonRun("", "", 0, null, "");
            PistonRun run = new PistonRun("hello\n", "", 0, null, "hello\n");
            PistonResponse response = new PistonResponse("javascript", "18.15.0", compile, run);

            assertEquals("javascript", response.language());
            assertEquals("18.15.0", response.version());
            assertNotNull(response.compile());
            assertNotNull(response.run());
            assertEquals("hello\n", response.run().stdout());
            assertEquals(0, response.run().code());
        }

        @Test
        @DisplayName("Should allow null compile phase")
        void shouldAllowNullCompilePhase() {
            PistonRun run = new PistonRun("output", "err", 1, "SIGTERM", "output");
            PistonResponse response = new PistonResponse("python", "3.10.0", null, run);

            assertNull(response.compile());
            assertEquals(1, response.run().code());
            assertEquals("SIGTERM", response.run().signal());
        }
    }

    // ===============================================================
    // execute() tests
    // ===============================================================

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("Should send correct request to Piston API")
        void shouldSendCorrectRequestToPistonApi() throws Exception {
            AtomicReference<String> receivedBody = stubExecuteEndpoint(200, """
                {
                    "language": "javascript",
                    "version": "18.15.0",
                    "run": {
                        "stdout": "hello\\n",
                        "stderr": "",
                        "code": 0,
                        "output": "hello\\n"
                    }
                }
                """);

            CodeRequest request = new CodeRequest("javascript", "*",
                "console.log('hello')", null, 10000, 10000, 128000000L);

            CodeResult result = pistonClient.execute(request);

            assertEquals("javascript", result.language());
            assertEquals("18.15.0", result.version());
            assertEquals("hello\n", result.stdout());
            assertEquals(0, result.exitCode());

            // Verify the request body was sent correctly
            assertNotNull(receivedBody.get());
            @SuppressWarnings("unchecked")
            Map<String, Object> sentBody = objectMapper.readValue(receivedBody.get(), Map.class);
            assertEquals("javascript", sentBody.get("language"));
            assertEquals("*", sentBody.get("version"));
        }

        @Test
        @DisplayName("Should parse response with compile phase")
        void shouldParseResponseWithCompilePhase() throws Exception {
            stubExecuteEndpoint(200, """
                {
                    "language": "typescript",
                    "version": "5.0.3",
                    "compile": {
                        "stdout": "",
                        "stderr": "",
                        "code": 0,
                        "output": ""
                    },
                    "run": {
                        "stdout": "42\\n",
                        "stderr": "",
                        "code": 0,
                        "output": "42\\n"
                    }
                }
                """);

            CodeRequest request = new CodeRequest("typescript", "*",
                "console.log(42)", null, 10000, 10000, 128000000L);

            CodeResult result = pistonClient.execute(request);

            assertEquals("typescript", result.language());
            assertNotNull(result.compileStdout());
            assertFalse(result.hasCompileError());
            assertEquals("42\n", result.stdout());
        }

        @Test
        @DisplayName("Should parse response with compilation error")
        void shouldParseResponseWithCompilationError() throws Exception {
            stubExecuteEndpoint(200, """
                {
                    "language": "typescript",
                    "version": "5.0.3",
                    "compile": {
                        "stdout": "",
                        "stderr": "error TS2322: Type 'string' is not assignable",
                        "code": 1,
                        "output": "error TS2322: Type 'string' is not assignable"
                    },
                    "run": {
                        "stdout": "",
                        "stderr": "",
                        "code": 0,
                        "output": ""
                    }
                }
                """);

            CodeRequest request = new CodeRequest("typescript", "*",
                "let x: number = 'bad'", null, 10000, 10000, 128000000L);

            CodeResult result = pistonClient.execute(request);

            assertTrue(result.hasCompileError());
            assertTrue(result.compileStderr().contains("TS2322"));
        }

        @Test
        @DisplayName("Should handle non-200 status code")
        void shouldHandleNon200StatusCode() throws Exception {
            stubExecuteEndpoint(500, "Internal Server Error");

            CodeRequest request = new CodeRequest("javascript", "*",
                "code", null, 10000, 10000, 128000000L);

            RuntimeException exception = assertThrows(RuntimeException.class,
                () -> pistonClient.execute(request));
            assertTrue(exception.getMessage().contains("HTTP 500"));
            assertTrue(exception.getMessage().contains("Internal Server Error"));
        }

        @Test
        @DisplayName("Should handle 400 bad request")
        void shouldHandle400BadRequest() throws Exception {
            stubExecuteEndpoint(400, "{\"message\":\"runtime is unknown\"}");

            CodeRequest request = new CodeRequest("unknownlang", "*",
                "code", null, 10000, 10000, 128000000L);

            RuntimeException exception = assertThrows(RuntimeException.class,
                () -> pistonClient.execute(request));
            assertTrue(exception.getMessage().contains("HTTP 400"));
        }

        @Test
        @DisplayName("Should use default version when null")
        void shouldUseDefaultVersionWhenNull() throws Exception {
            AtomicReference<String> receivedBody = stubExecuteEndpoint(200, """
                {
                    "language": "python",
                    "version": "3.10.0",
                    "run": {
                        "stdout": "",
                        "stderr": "",
                        "code": 0,
                        "output": ""
                    }
                }
                """);

            CodeRequest request = new CodeRequest("python", null,
                "pass", null, 10000, 10000, 128000000L);

            pistonClient.execute(request);

            // Verify the request body contains version: "*" (default)
            assertNotNull(receivedBody.get());
            @SuppressWarnings("unchecked")
            Map<String, Object> sentBody = objectMapper.readValue(receivedBody.get(), Map.class);
            assertEquals("*", sentBody.get("version"));
        }

        @Test
        @DisplayName("Should use default timeout when request timeout is 0")
        void shouldUseDefaultTimeoutWhenZero() throws Exception {
            stubExecuteEndpoint(200, """
                {
                    "language": "bash",
                    "version": "5.2.0",
                    "run": {
                        "stdout": "",
                        "stderr": "",
                        "code": 0,
                        "output": ""
                    }
                }
                """);

            CodeRequest request = new CodeRequest("bash", "*",
                "echo hi", null, 0, 0, 0);

            CodeResult result = pistonClient.execute(request);

            assertNotNull(result);
            assertEquals("bash", result.language());
        }

        @Test
        @DisplayName("Should propagate connection exceptions after exhausting retries")
        void shouldPropagateHttpClientExceptions() throws Exception {
            // Point the client at a port nothing listens on to trigger a real connect failure.
            PistonClient badClient = new PistonClient(objectMapper, httpClient,
                "http://localhost:1", DEFAULT_TIMEOUT, DEFAULT_MEMORY);
            // Keep the real-socket path fast: 2 attempts, 1ms back-off.
            badClient.setConnectRetryConfigForTest(2, 1, 1);

            CodeRequest request = new CodeRequest("javascript", "*",
                "code", null, 10000, 10000, 128000000L);

            assertThrows(java.net.ConnectException.class,
                () -> badClient.execute(request));
        }

        @Test
        @DisplayName("Should parse response with signal (timeout kill)")
        void shouldParseResponseWithSignal() throws Exception {
            stubExecuteEndpoint(200, """
                {
                    "language": "javascript",
                    "version": "18.15.0",
                    "run": {
                        "stdout": "",
                        "stderr": "",
                        "code": 137,
                        "signal": "SIGKILL",
                        "output": ""
                    }
                }
                """);

            CodeRequest request = new CodeRequest("javascript", "*",
                "while(true){}", null, 10000, 10000, 128000000L);

            CodeResult result = pistonClient.execute(request);

            assertEquals(137, result.exitCode());
            assertEquals("SIGKILL", result.signal());
        }

        @Test
        @DisplayName("Should include stdin in request when provided")
        void shouldIncludeStdinWhenProvided() throws Exception {
            AtomicReference<String> receivedBody = stubExecuteEndpoint(200, """
                {
                    "language": "python",
                    "version": "3.10.0",
                    "run": {
                        "stdout": "hello\\n",
                        "stderr": "",
                        "code": 0,
                        "output": "hello\\n"
                    }
                }
                """);

            CodeRequest request = new CodeRequest("python", "*",
                "print(input())", "hello", 10000, 10000, 128000000L);

            CodeResult result = pistonClient.execute(request);

            assertEquals("hello\n", result.stdout());

            // Verify stdin was included in the request body
            assertNotNull(receivedBody.get());
            @SuppressWarnings("unchecked")
            Map<String, Object> sentBody = objectMapper.readValue(receivedBody.get(), Map.class);
            assertEquals("hello", sentBody.get("stdin"));
        }
    }

    // ===============================================================
    // Connect-retry tests (transient "Connection refused" tolerance)
    // ===============================================================

    @Nested
    @DisplayName("connect retry")
    class ConnectRetryTests {

        private final PistonResponse OK =
            new PistonResponse("javascript", "20.11.1", null,
                new PistonRun("ok\n", "", 0, null, "ok\n"));

        @Test
        @DisplayName("Should retry on ConnectException and succeed on a later attempt")
        void shouldRetryConnectExceptionThenSucceed() throws Exception {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            PistonClient client = new PistonClient(objectMapper, httpClient, pistonUrl, DEFAULT_TIMEOUT, DEFAULT_MEMORY) {
                @Override
                protected PistonResponse executeOnce(String jsonBody, long httpTimeoutSec) throws Exception {
                    if (calls.incrementAndGet() <= 2) throw new java.net.ConnectException("Connection refused");
                    return OK;
                }
            };
            client.setConnectRetryConfigForTest(4, 1, 2);

            PistonResponse resp = client.executeWithConnectRetry("{}", 30);

            assertEquals(3, calls.get(), "should fail twice then succeed on the 3rd attempt");
            assertEquals("ok\n", resp.run().stdout());
        }

        @Test
        @DisplayName("Should give up after max attempts and rethrow the ConnectException")
        void shouldGiveUpAfterMaxAttempts() {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            PistonClient client = new PistonClient(objectMapper, httpClient, pistonUrl, DEFAULT_TIMEOUT, DEFAULT_MEMORY) {
                @Override
                protected PistonResponse executeOnce(String jsonBody, long httpTimeoutSec) throws Exception {
                    calls.incrementAndGet();
                    throw new java.net.ConnectException("Connection refused");
                }
            };
            client.setConnectRetryConfigForTest(3, 1, 2);

            assertThrows(java.net.ConnectException.class,
                () -> client.executeWithConnectRetry("{}", 30));
            assertEquals(3, calls.get(), "should attempt exactly maxAttempts times");
        }

        @Test
        @DisplayName("Should NOT retry on a non-connect error (e.g. HTTP 500 / parse error)")
        void shouldNotRetryNonConnectError() {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            PistonClient client = new PistonClient(objectMapper, httpClient, pistonUrl, DEFAULT_TIMEOUT, DEFAULT_MEMORY) {
                @Override
                protected PistonResponse executeOnce(String jsonBody, long httpTimeoutSec) {
                    calls.incrementAndGet();
                    throw new RuntimeException("Piston returned HTTP 500: boom");
                }
            };
            client.setConnectRetryConfigForTest(4, 1, 2);

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.executeWithConnectRetry("{}", 30));
            assertTrue(ex.getMessage().contains("HTTP 500"));
            assertEquals(1, calls.get(), "non-connect failures must not be retried");
        }

        @Test
        @DisplayName("Should NOT retry when the first attempt succeeds")
        void shouldNotRetryOnSuccess() throws Exception {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            PistonClient client = new PistonClient(objectMapper, httpClient, pistonUrl, DEFAULT_TIMEOUT, DEFAULT_MEMORY) {
                @Override
                protected PistonResponse executeOnce(String jsonBody, long httpTimeoutSec) {
                    calls.incrementAndGet();
                    return OK;
                }
            };
            client.setConnectRetryConfigForTest(4, 1, 2);

            assertEquals("ok\n", client.executeWithConnectRetry("{}", 30).run().stdout());
            assertEquals(1, calls.get(), "a first-attempt success must not trigger extra calls");
        }

        @Test
        @DisplayName("Back-off should grow exponentially and be capped")
        void backoffShouldGrowExponentiallyAndBeCapped() {
            PistonClient client = new PistonClient(objectMapper, httpClient, pistonUrl, DEFAULT_TIMEOUT, DEFAULT_MEMORY);
            client.setConnectRetryConfigForTest(10, 500, 3000);

            assertEquals(500, client.connectRetryBackoffMs(1));   // base
            assertEquals(1000, client.connectRetryBackoffMs(2));  // base*2
            assertEquals(2000, client.connectRetryBackoffMs(3));  // base*4
            assertEquals(3000, client.connectRetryBackoffMs(4));  // base*8 -> capped at 3000
            assertEquals(3000, client.connectRetryBackoffMs(20)); // still capped, no overflow
        }

        @Test
        @DisplayName("Should preserve the interrupt flag and rethrow ConnectException if interrupted during back-off")
        void shouldReinterruptAndRethrowWhenInterruptedDuringBackoff() {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            PistonClient client = new PistonClient(objectMapper, httpClient, pistonUrl, DEFAULT_TIMEOUT, DEFAULT_MEMORY) {
                @Override
                protected PistonResponse executeOnce(String jsonBody, long httpTimeoutSec) throws Exception {
                    calls.incrementAndGet();
                    Thread.currentThread().interrupt(); // make the upcoming back-off sleep throw immediately
                    throw new java.net.ConnectException("Connection refused");
                }
            };
            // Large back-off so the test would hang for seconds if the interrupt were NOT honored.
            client.setConnectRetryConfigForTest(4, 5000, 5000);

            assertThrows(java.net.ConnectException.class,
                () -> client.executeWithConnectRetry("{}", 30));
            assertTrue(Thread.interrupted(), "interrupt flag must be preserved (this check also clears it)");
            assertEquals(1, calls.get(), "interruption must abort retrying after the first attempt");
        }

        @Test
        @DisplayName("maxAttempts of 1 disables retry (single attempt)")
        void maxAttemptsOneDisablesRetry() {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            PistonClient client = new PistonClient(objectMapper, httpClient, pistonUrl, DEFAULT_TIMEOUT, DEFAULT_MEMORY) {
                @Override
                protected PistonResponse executeOnce(String jsonBody, long httpTimeoutSec) throws Exception {
                    calls.incrementAndGet();
                    throw new java.net.ConnectException("Connection refused");
                }
            };
            client.setConnectRetryConfigForTest(1, 1, 1);

            assertThrows(java.net.ConnectException.class,
                () -> client.executeWithConnectRetry("{}", 30));
            assertEquals(1, calls.get(), "maxAttempts=1 means no retry");
        }
    }

    // ===============================================================
    // Secret isolation (parity with CE EmbeddedCodeExecutorTest.EnvironmentScrubbing)
    // ===============================================================

    /**
     * Why this matters: in EE mode user-authored code runs in a remote Piston sandbox reached over
     * HTTP. The orchestrator JVM that builds the request carries host/service secrets in its
     * environment (DB_PASSWORD, CREDENTIAL_ENCRYPTION_*, STORAGE_S3_SECRET_KEY,
     * CLOUD_LINK_ENCRYPTION_KEY, …). The CE counterpart (EmbeddedCodeExecutor) scrubs those from the
     * child process env before launch; the EE counterpart's guarantee is structural - the execute
     * request body Piston receives carries ONLY language/version/files/stdin/timeouts/memory and has
     * NO {@code env} channel, so user code in the remote sandbox can never be handed a host secret via
     * the request the orchestrator sends.
     *
     * <p>This test pins exactly that no-secret-in-request invariant: it captures the serialized body
     * actually POSTed to {@code /api/v2/execute} and asserts (a) there is no {@code env} key and
     * (b) no known host-secret name appears anywhere in it. The runtime isolation of the Piston
     * container itself (process/network sandboxing) is a property of Piston and is out of scope here -
     * this guards only what the orchestrator puts on the wire.
     */
    @Nested
    @DisplayName("Secret isolation")
    class SecretIsolation {

        @Test
        @DisplayName("execute request body carries no env channel and no host secrets")
        void executeRequestCarriesNoEnvOrHostSecrets() throws Exception {
            AtomicReference<String> receivedBody = stubExecuteEndpoint(200, """
                {
                    "language": "javascript",
                    "version": "18.15.0",
                    "run": {
                        "stdout": "",
                        "stderr": "",
                        "code": 0,
                        "output": ""
                    }
                }
                """);

            // Realistic user code that, if an env channel existed, could try to read host secrets.
            CodeRequest request = new CodeRequest("javascript", "*",
                "console.log(process.env.DB_PASSWORD)", "stdin-data", 10000, 10000, 128000000L);

            pistonClient.execute(request);

            String rawBody = receivedBody.get();
            assertNotNull(rawBody, "the execute request body must have reached Piston");

            @SuppressWarnings("unchecked")
            Map<String, Object> sentBody = objectMapper.readValue(rawBody, Map.class);

            // The body must expose ONLY the documented Piston execute keys - never an env channel.
            assertFalse(sentBody.containsKey("env"),
                "Piston execute body must NOT carry an 'env' key - user code must not be handed host env");
            assertEquals(
                Set.of("language", "version", "files", "stdin", "run_timeout", "compile_timeout", "memory_limit"),
                sentBody.keySet(),
                "execute body keys must be exactly the documented Piston fields (no extra/secret channel)");

            // Defense in depth: no known host-secret NAME may appear anywhere in the serialized body,
            // even though the only free-form field (files[].content) echoes the user's source.
            for (String secretName : List.of(
                    "DB_PASSWORD",
                    "CREDENTIAL_ENCRYPTION",
                    "STORAGE_S3_SECRET_KEY",
                    "CLOUD_LINK_ENCRYPTION_KEY")) {
                // The user's source literally references DB_PASSWORD, so check the structural keys
                // (everything except the user-controlled files[].content) for secret names.
                assertFalse(sentBody.keySet().stream().anyMatch(k -> k.contains(secretName)),
                    "no request key may reference the host secret " + secretName);
            }

            // The orchestrator never injects a secret VALUE: assert none of the structural fields
            // (version/stdin/timeouts/memory) leaks a secret-shaped name, and files[] carries only
            // the user's own source under name="main".
            assertEquals("*", sentBody.get("version"));
            assertEquals("stdin-data", sentBody.get("stdin"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) sentBody.get("files");
            assertEquals(1, files.size(), "exactly one file (the user's code) is sent");
            assertEquals("main", files.get(0).get("name"));
            assertEquals("console.log(process.env.DB_PASSWORD)", files.get(0).get("content"),
                "files[].content is exactly the user's source - no host env spliced in");
        }
    }

    // ===============================================================
    // listRuntimes() tests
    // ===============================================================

    @Nested
    @DisplayName("listRuntimes()")
    class ListRuntimesTests {

        @Test
        @DisplayName("Should return list of available runtimes")
        void shouldReturnListOfRuntimes() throws Exception {
            server.createContext("/api/v2/runtimes", exchange -> {
                String body = """
                    [
                        {"language": "javascript", "version": "18.15.0", "aliases": ["node", "js"]},
                        {"language": "python", "version": "3.10.0", "aliases": ["py"]}
                    ]
                    """;
                byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            });

            List<Map<String, Object>> runtimes = pistonClient.listRuntimes();

            assertEquals(2, runtimes.size());
            assertEquals("javascript", runtimes.get(0).get("language"));
            assertEquals("python", runtimes.get(1).get("language"));
        }

        @Test
        @DisplayName("Should call correct runtimes endpoint")
        void shouldCallCorrectRuntimesEndpoint() throws Exception {
            AtomicReference<String> receivedUri = new AtomicReference<>();
            AtomicReference<String> receivedMethod = new AtomicReference<>();
            server.createContext("/api/v2/runtimes", exchange -> {
                receivedUri.set(exchange.getRequestURI().toString());
                receivedMethod.set(exchange.getRequestMethod());
                byte[] responseBytes = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            });

            pistonClient.listRuntimes();

            assertEquals("/api/v2/runtimes", receivedUri.get());
            assertEquals("GET", receivedMethod.get());
        }
    }
}
