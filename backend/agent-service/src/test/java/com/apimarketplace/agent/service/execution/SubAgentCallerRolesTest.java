package com.apimarketplace.agent.service.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sub-agent path must carry the caller's roles into the loop.
 *
 * <p>It never did, and that only became load-bearing once the loop started enforcing the CLI-bridge
 * policy at provider resolution. With the roles missing the loop reads {@code null}, every guard
 * downstream treats that as a plain {@code USER}, and an ADMIN is refused their own sub-agent on a
 * bridge they are entitled to. So the gate and this threading are one change: shipping the gate
 * alone converts a silent hole into a false refusal.
 */
@DisplayName("SubAgentExecutionHandler - the caller's roles reach the loop")
class SubAgentCallerRolesTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "apimarketplace",
        "agent", "service", "execution", "SubAgentExecutionHandler.java");

    private static String callerRoles(Map<String, Object> credentials) throws Exception {
        Method m = SubAgentExecutionHandler.class
            .getDeclaredMethod("callerRoles", Map.class);
        m.setAccessible(true);
        return (String) m.invoke(null, credentials);
    }

    @Test
    @DisplayName("roles are read from the credential key the gateway actually injects")
    void rolesComeFromTheInjectedCredentialKey() throws Exception {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__userRoles__", "USER,ADMIN");

        assertThat(callerRoles(credentials))
            .as("a different key would read null and refuse an admin their own sub-agent")
            .isEqualTo("USER,ADMIN");
    }

    @Test
    @DisplayName("absent, blank and non-string roles all normalise to null")
    void missingRolesNormaliseToNull() throws Exception {
        assertThat(callerRoles(null)).isNull();
        assertThat(callerRoles(new HashMap<>())).isNull();

        Map<String, Object> blank = new HashMap<>();
        blank.put("__userRoles__", "   ");
        assertThat(callerRoles(blank))
            .as("blank must become null so downstream reads one spelling; a blank string would "
                + "parse as a role list with no roles")
            .isNull();

        Map<String, Object> wrongType = new HashMap<>();
        wrongType.put("__userRoles__", 42);
        assertThat(callerRoles(wrongType)).isNull();
    }

    @Test
    @DisplayName("the context built for the loop actually sets userRoles")
    void theBuiltContextCarriesTheRoles() throws IOException {
        // Asserted on the source because building a real context here would need the handler's
        // whole dependency graph. Shallow on purpose: it proves the wiring exists, while the
        // behaviour of the value once it arrives is covered by AgentLoopServiceBridgeGateTest.
        assertThat(Files.exists(SOURCE))
            .as("expected the handler at %s; if it moved, fix this path rather than dropping "
                + "the assertion", SOURCE.toAbsolutePath())
            .isTrue();

        // The wiring used to be one contiguous ".userRoles(callerRoles(credentials))" call; the
        // execution-link bridge-failure fallback (buildSubAgentContext(...), reused by both the
        // primary attempt and the fallback so ContextWindowWiringTest's per-file 1:1 builder/
        // contextWindow discovery stays true) split it into "callerRoles(credentials)" read once
        // into a local, then threaded through as a parameter and wired with ".userRoles(userRoles)"
        // inside the shared helper. Asserting on both fragments still proves the same two facts a
        // single literal did: the credential is actually read, and that value actually reaches the
        // builder - it would fail if either fragment were dropped.
        assertThat(Files.readString(SOURCE))
            .as("without this line the loop's bridge gate judges every sub-agent as USER")
            .contains("callerRoles(credentials)")
            .contains(".userRoles(userRoles)");
    }
}
