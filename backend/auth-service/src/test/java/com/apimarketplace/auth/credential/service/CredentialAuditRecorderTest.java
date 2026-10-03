package com.apimarketplace.auth.credential.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.audit.AuditPseudonymizer;
import com.apimarketplace.auth.audit.AuditSigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** LC-058: the credential lifecycle events land on the signed AUDIT stream, with no secret in them. */
@DisplayName("CredentialAuditRecorder")
class CredentialAuditRecorderTest {

    private final ObjectMapper json = new ObjectMapper();
    private CredentialAuditRecorder recorder;
    private ListAppender<ILoggingEvent> appender;
    private Logger auditLog;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        AuditPseudonymizer pseudo = new AuditPseudonymizer(key);
        pseudo.rotateSalt();
        recorder = new CredentialAuditRecorder();
        recorder.setAuditLogger(new AuditLogger(pseudo, new AuditSigner(key), "auth-service"));
        auditLog = (Logger) LoggerFactory.getLogger("AUDIT");
        appender = new ListAppender<>();
        appender.start();
        auditLog.addAppender(appender);
        auditLog.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        auditLog.detachAppender(appender);
    }

    private JsonNode only() throws Exception {
        assertThat(appender.list).hasSize(1);
        return json.readTree(appender.list.get(0).getFormattedMessage());
    }

    @Test
    @DisplayName("connect: event type, user, credential id, integration and the granted scope names")
    void connect() throws Exception {
        recorder.recordOAuthConnected("user-1", 55L, "gmail", List.of("https://www.googleapis.com/auth/gmail.send"));

        JsonNode e = only();
        assertThat(e.path("eventType").asText()).isEqualTo(AuditEventTypes.CREDENTIAL_OAUTH_CONNECTED);
        assertThat(e.path("userId").asText()).isEqualTo("user-1");
        assertThat(e.path("details").path("credential_id").asLong()).isEqualTo(55L);
        assertThat(e.path("details").path("integration").asText()).isEqualTo("gmail");
        assertThat(e.path("details").path("scopes").get(0).asText()).endsWith("gmail.send");
        assertThat(e.path("signature").asText()).isNotBlank();
    }

    @Test
    @DisplayName("scope list is bounded; scope_count keeps the true total")
    void boundedScopes() throws Exception {
        List<String> many = IntStream.range(0, 40).mapToObj(i -> "scope-" + i).toList();

        recorder.recordOAuthConnected("user-1", 1L, "x", many);

        JsonNode d = only().path("details");
        assertThat(d.path("scope_count").asInt()).isEqualTo(40);
        assertThat(d.path("scopes").size()).isEqualTo(CredentialAuditRecorder.MAX_SCOPES_LOGGED);
    }

    @Test
    @DisplayName("delete carries the revocation outcome and the removal reason")
    void deleted() throws Exception {
        recorder.recordDeleted("user-1", 7L, "gmail", "user_delete", "failed");

        JsonNode e = only();
        assertThat(e.path("eventType").asText()).isEqualTo(AuditEventTypes.CREDENTIAL_DELETED);
        assertThat(e.path("details").path("provider_revocation").asText()).isEqualTo("failed");
        assertThat(e.path("details").path("reason").asText()).isEqualTo("user_delete");
    }

    @Test
    @DisplayName("a failed provider revocation is a WARN failure event")
    void failedRevocationIsWarn() throws Exception {
        recorder.recordProviderRevocation("user-1", 7L, "gmail", "failed", false);

        JsonNode e = only();
        assertThat(e.path("severity").asText()).isEqualTo(AuditEventTypes.SEVERITY_WARN);
        assertThat(e.path("result").asText()).isEqualTo(AuditEventTypes.RESULT_FAILURE);
    }

    @Test
    @DisplayName("terminal refresh failure is WARN; the http status is recorded")
    void refreshFailure() throws Exception {
        recorder.recordRefreshFailed("user-1", 7L, "gmail", "terminal_user", 400, true);

        JsonNode e = only();
        assertThat(e.path("eventType").asText()).isEqualTo(AuditEventTypes.CREDENTIAL_REFRESH_FAILED);
        assertThat(e.path("severity").asText()).isEqualTo(AuditEventTypes.SEVERITY_WARN);
        assertThat(e.path("details").path("http_status").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("secret read: identifiers and access path only; the API has no way to pass a value")
    void secretRead() throws Exception {
        recorder.recordSecretRead("user-1", 5L, "gmail", "access_token");

        JsonNode e = only();
        assertThat(e.path("eventType").asText()).isEqualTo(AuditEventTypes.CREDENTIAL_SECRET_READ);
        assertThat(e.path("details").path("access_path").asText()).isEqualTo("access_token");
        assertThat(e.path("details").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("credential_id", "integration", "access_path");
    }

    @Test
    @DisplayName("without an AuditLogger (hand-built services) every method is a silent no-op")
    void noLoggerNoOp() {
        CredentialAuditRecorder bare = new CredentialAuditRecorder();

        assertThatCode(() -> {
            bare.recordOAuthConnected("u", 1L, "x", List.of());
            bare.recordDeleted("u", 1L, "x", "r", "o");
            bare.recordSecretReadByName("u", "n", "p");
        }).doesNotThrowAnyException();
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("batch read: ONE event with the true count and a bounded id list")
    void batchRead() throws Exception {
        List<Long> ids = java.util.stream.LongStream.range(0, 80).boxed().toList();

        recorder.recordSecretReadBatch("user-1", ids, "credential_list");

        JsonNode d = only().path("details");
        assertThat(d.path("count").asInt()).isEqualTo(80);
        assertThat(d.path("credential_ids").size()).isEqualTo(CredentialAuditRecorder.MAX_IDS_LOGGED);
    }
}
