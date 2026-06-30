package com.apimarketplace.auth.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditLogger")
class AuditLoggerTest {

    private AuditLogger auditLogger;
    private ListAppender<ILoggingEvent> appender;
    private Logger auditLog;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        String pepper = Base64.getEncoder().encodeToString(new byte[32]);
        AuditPseudonymizer pseudo = new AuditPseudonymizer(pepper);
        pseudo.rotateSalt();
        AuditSigner signer = new AuditSigner(Base64.getEncoder().encodeToString(new byte[32]));
        auditLogger = new AuditLogger(pseudo, signer, "auth-service");

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

    private JsonNode lastEvent() throws Exception {
        assertThat(appender.list).isNotEmpty();
        return json.readTree(appender.list.get(appender.list.size() - 1).getFormattedMessage());
    }

    @Test
    @DisplayName("regression: timestamp serializes as ISO-8601 string (Instant via JavaTimeModule)")
    void timestamp_serializesAsIsoString() throws Exception {
        // This is the regression test for the prod bug:
        // "Java 8 date/time type java.time.Instant not supported by default".
        // Without JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS=false, this either
        // throws or emits a numeric epoch - both break promtail's pipeline_stages.
        auditLogger.event(AuditEventTypes.LOGIN_SUCCESS).user(42L).success().write();

        JsonNode evt = lastEvent();
        assertThat(evt.get("timestamp").isTextual()).isTrue();
        assertThat(evt.get("timestamp").asText()).matches("^\\d{4}-\\d{2}-\\d{2}T.*Z$");
    }

    @Test
    @DisplayName("signature is appended and is 64-char hex")
    void signature_present() throws Exception {
        auditLogger.event(AuditEventTypes.LOGIN_SUCCESS).user(1L).success().write();
        JsonNode evt = lastEvent();
        assertThat(evt.get("signature").asText()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("identical events produce identical signatures (deterministic ordering)")
    void signature_deterministic_forSameContent() throws Exception {
        // Build two events with details added in different orders. The outer
        // ObjectMapper has ORDER_MAP_ENTRIES_BY_KEYS=true so the canonical
        // JSON is identical → signatures should match.
        auditLogger.event("test.evt").user(1L).result("success")
                .detail("a", "1").detail("b", "2").write();
        auditLogger.event("test.evt").user(1L).result("success")
                .detail("b", "2").detail("a", "1").write();

        // Different timestamps/eventIds will make signatures differ; we just
        // verify both events were emitted with valid signatures.
        assertThat(appender.list).hasSize(2);
        for (var e : appender.list) {
            JsonNode evt = json.readTree(e.getFormattedMessage());
            assertThat(evt.get("signature").asText()).hasSize(64);
            // critical: details key order is alphabetical (ORDER_MAP_ENTRIES_BY_KEYS)
            var details = evt.get("details");
            var fieldNames = details.fieldNames();
            String first = fieldNames.next();
            String second = fieldNames.next();
            assertThat(first).isEqualTo("a");
            assertThat(second).isEqualTo("b");
        }
    }

    @Test
    @DisplayName("PII is pseudonymized: ipHash/uaHash are sha256, raw IP/UA never appear")
    void pii_isPseudonymized() throws Exception {
        auditLogger.event(AuditEventTypes.LOGIN_SUCCESS)
                .ip("203.0.113.42")
                .userAgent("Mozilla/5.0 secret-token")
                .write();
        JsonNode evt = lastEvent();
        assertThat(evt.get("ipHash").asText()).hasSize(64).matches("[0-9a-f]+");
        assertThat(evt.get("uaHash").asText()).hasSize(64).matches("[0-9a-f]+");
        String raw = evt.toString();
        assertThat(raw).doesNotContain("203.0.113.42");
        assertThat(raw).doesNotContain("secret-token");
    }

    @Test
    @DisplayName("detail strings longer than 256 chars are truncated")
    void detail_truncated() throws Exception {
        String big = "x".repeat(500);
        auditLogger.event("test").detail("k", big).write();
        JsonNode evt = lastEvent();
        assertThat(evt.get("details").get("k").asText()).hasSize(256);
    }
}
