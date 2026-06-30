package com.apimarketplace.auth.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central audit logger.
 *
 * Writes events as a single JSON line to a dedicated SLF4J logger named "AUDIT".
 * Logback should route this logger to a separate rolling file appender (see
 * logback-spring.xml) so audit events never mix with application logs.
 *
 * Performance: serialization is ~1µs, the underlying appender is async →
 * effectively zero latency on the request path.
 *
 * Security: every event is HMAC-signed for tamper detection. PII is
 * pseudonymized via {@link AuditPseudonymizer} before reaching the JSON.
 *
 * IMPORTANT: callers MUST NOT pass raw email/IP/UA in the {@code details} map.
 * Use {@link #builder()} which only accepts pre-pseudonymized identifiers.
 */
@Component
public class AuditLogger {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final ObjectMapper mapper;
    private final AuditPseudonymizer pseudonymizer;
    private final AuditSigner signer;
    private final String serviceName;

    public AuditLogger(AuditPseudonymizer pseudonymizer,
                       AuditSigner signer,
                       @Value("${spring.application.name:auth-service}") String serviceName) {
        this.pseudonymizer = pseudonymizer;
        this.signer = signer;
        this.serviceName = serviceName;
        this.mapper = new ObjectMapper();
        // JavaTimeModule to serialize Instant (AuditEvent.timestamp) as ISO-8601
        // string instead of crashing on "Java 8 date/time type not supported".
        this.mapper.registerModule(new JavaTimeModule());
        // Disable timestamp-as-numeric so Instant serializes to a stable RFC3339
        // string, matching the format promtail's pipeline_stages expects.
        this.mapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        // Deterministic key order for stable HMAC signing.
        this.mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public Builder event(String eventType) {
        return new Builder(eventType);
    }

    /**
     * Convenience: extract IP and UA from a Spring HttpServletRequest while
     * keeping the call site one-liner.
     */
    public Builder eventFromRequest(String eventType, HttpServletRequest req) {
        Builder b = new Builder(eventType);
        if (req != null) {
            b.ip(extractClientIp(req));
            b.userAgent(req.getHeader("User-Agent"));
        }
        return b;
    }

    private static String extractClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    public final class Builder {
        private final String eventType;
        private String severity = AuditEventTypes.SEVERITY_INFO;
        private String userId;
        private String rawIp;
        private String rawUa;
        private String result;
        private String reason;
        private final Map<String, Object> details = new LinkedHashMap<>();

        private Builder(String eventType) {
            this.eventType = eventType;
        }

        public Builder severity(String s) { this.severity = s; return this; }
        public Builder critical()         { this.severity = AuditEventTypes.SEVERITY_CRITICAL; return this; }
        public Builder warn()             { this.severity = AuditEventTypes.SEVERITY_WARN; return this; }

        public Builder user(String userId)        { this.userId = userId; return this; }
        public Builder user(Long userId)          { this.userId = userId == null ? null : String.valueOf(userId); return this; }
        public Builder ip(String rawIp)           { this.rawIp = rawIp; return this; }
        public Builder userAgent(String rawUa)    { this.rawUa = rawUa; return this; }
        public Builder result(String r)           { this.result = r; return this; }
        public Builder success()                  { this.result = AuditEventTypes.RESULT_SUCCESS; return this; }
        public Builder failure(String reason)     { this.result = AuditEventTypes.RESULT_FAILURE; this.reason = reason; return this; }
        public Builder reason(String r)           { this.reason = r; return this; }

        /**
         * Add a sanitized detail. Caller is responsible for ensuring the value
         * does not contain PII (no email, no raw IP, no name). Strings are
         * truncated to 256 chars.
         */
        public Builder detail(String key, Object value) {
            if (value instanceof String s && s.length() > 256) {
                value = s.substring(0, 256);
            }
            this.details.put(key, value);
            return this;
        }

        public void write() {
            try {
                String ipHash = pseudonymizer.hashIp(rawIp);
                String uaHash = pseudonymizer.hashUserAgent(rawUa);

                AuditEvent unsigned = new AuditEvent(
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        eventType,
                        severity,
                        serviceName,
                        userId,
                        ipHash,
                        uaHash,
                        result,
                        reason,
                        // IMPORTANT: keep insertion order (LinkedHashMap), not Map.copyOf
                        // which returns an unordered immutable map. The HMAC is computed
                        // over the canonical JSON of this exact map; if the signed event's
                        // serialized form has different key ordering, signature
                        // verification on read will fail. The outer ObjectMapper has
                        // ORDER_MAP_ENTRIES_BY_KEYS=true so even nested maps serialize
                        // deterministically - but Map.copyOf does NOT preserve order
                        // for iteration, breaking the round-trip.
                        details.isEmpty() ? null : new LinkedHashMap<>(details),
                        null
                );

                String canonical = mapper.writeValueAsString(unsigned);
                String signature = signer.sign(canonical);

                AuditEvent signed = new AuditEvent(
                        unsigned.eventId(),
                        unsigned.timestamp(),
                        unsigned.eventType(),
                        unsigned.severity(),
                        unsigned.service(),
                        unsigned.userId(),
                        unsigned.ipHash(),
                        unsigned.uaHash(),
                        unsigned.result(),
                        unsigned.reason(),
                        unsigned.details(),
                        signature
                );

                AUDIT_LOG.info(mapper.writeValueAsString(signed));
            } catch (JsonProcessingException e) {
                // Audit must never break the request flow. Log to standard logger.
                log.error("Failed to serialize audit event {}: {}", eventType, e.getMessage());
            } catch (Exception e) {
                log.error("Audit logging failed for {}: {}", eventType, e.getMessage());
            }
        }
    }
}
