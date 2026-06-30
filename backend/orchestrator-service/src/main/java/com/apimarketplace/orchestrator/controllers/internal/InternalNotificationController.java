package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.notification.client.dto.NotificationEmitResponse;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Internal endpoint for cross-service notification emission. Producers
 * ({@code trigger-service}, {@code auth-service}, {@code agent-service},
 * {@code catalog-service}) post via {@code notification-client.NotificationClient}.
 *
 * <p>The handler mirrors {@code NotificationEmitter}'s in-process path:
 * native {@code INSERT … ON CONFLICT (tenant_id, category, source_id) DO NOTHING
 * RETURNING id}, WS publish gated on insert, failure isolation via
 * {@code DataAccessException | PersistenceException} swallow + counter.
 *
 * <p><b>Validation</b> (400 on miss): {@code tenantId}, {@code category},
 * {@code severity ∈ {info,warning,error}}, {@code subjectType} on V176
 * allow-list, {@code subjectId}, {@code sourceId}, {@code payload} containing
 * {@code status}, {@code occurredAt}.
 *
 * <p>Trust boundary: deployed behind the gateway / private network. No
 * authn here; producers in cluster have direct line-of-sight (matches
 * {@code InternalAgentController} et al).
 */
@RestController
@RequestMapping("/api/internal/notifications")
public class InternalNotificationController {

    private static final Logger logger = LoggerFactory.getLogger(InternalNotificationController.class);

    /** V176 allow-list. */
    private static final Set<String> SUBJECT_TYPES = Set.of(
            "WORKFLOW", "APPLICATION", "AGENT_TASK", "CREDENTIAL", "TRIGGER", "ORG_INVITATION");

    private static final Set<String> SEVERITIES = Set.of("info", "warning", "error");

    private final WorkflowRedisPublisher redisPublisher;
    private final MeterRegistry meterRegistry;

    @PersistenceContext
    private EntityManager entityManager;

    public InternalNotificationController(WorkflowRedisPublisher redisPublisher,
                                          MeterRegistry meterRegistry) {
        this.redisPublisher = redisPublisher;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/emit")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResponseEntity<?> emit(@RequestBody NotificationEmitRequest req) {
        // ---- validation ----
        String validationError = validate(req);
        if (validationError != null) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", "Validation").increment();
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }

        Instant occurredAt = req.getOccurredAt() != null ? req.getOccurredAt() : Instant.now();

        // toJson - minimal, payload values are bounded primitives + nested Map.
        // We delegate to Jackson via the EntityManager's jsonb cast to avoid
        // re-implementing toJson here; the native query CASTs the bind to jsonb.
        // For simplicity we stringify via a tiny escaper matching NotificationEmitter.toJson.
        String payloadJson;
        try {
            payloadJson = JsonUtil.toJson(req.getPayload());
        } catch (RuntimeException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", "JsonSerialization").increment();
            return ResponseEntity.badRequest().body(Map.of("error", "payload serialization failed"));
        }

        try {
            // Post-V261 - `organization_id` is NOT NULL on the
            // notifications row. The emitter resolves it in this order:
            //   1. explicit `req.getOrganizationId()` (e.g. invitee's default
            //      org for ORG_INVITATION_PENDING - set by the producer who
            //      knows the recipient's scope, not the caller's);
            //   2. otherwise the caller's active request org from the
            //      X-Organization-ID header propagated via OrgScopedEntityListener.
            String orgId = req.getOrganizationId();
            if (orgId == null || orgId.isBlank()) {
                orgId = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
            }
            if (orgId == null || orgId.isBlank()) {
                meterRegistry.counter("notification.emitter.errors",
                        "type", "MissingOrganizationId").increment();
                return ResponseEntity.badRequest().body(Map.of("error", "organizationId required after V261"));
            }

            @SuppressWarnings("unchecked")
            List<Object> inserted = entityManager.createNativeQuery(
                    "INSERT INTO orchestrator.notifications " +
                            "(tenant_id, organization_id, category, severity, subject_type, subject_id, " +
                            " source_id, run_id, run_id_public, plan_version, payload, occurred_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) " +
                            "ON CONFLICT (tenant_id, category, source_id) DO NOTHING RETURNING id"
            )
                    .setParameter(1, req.getTenantId())
                    .setParameter(2, orgId)
                    .setParameter(3, req.getCategory())
                    .setParameter(4, req.getSeverity())
                    .setParameter(5, req.getSubjectType())
                    .setParameter(6, req.getSubjectId())
                    .setParameter(7, req.getSourceId())
                    .setParameter(8, req.getRunId())
                    .setParameter(9, req.getRunIdPublic())
                    .setParameter(10, req.getPlanVersion())
                    .setParameter(11, payloadJson)
                    .setParameter(12, occurredAt)
                    .getResultList();

            if (inserted.isEmpty()) {
                // ON CONFLICT DO NOTHING fired - idempotent retry / multi-replica race loser.
                return ResponseEntity.ok(new NotificationEmitResponse(false, null));
            }

            Long id = ((Number) inserted.get(0)).longValue();

            try {
                redisPublisher.publishNotification(req.getTenantId(), "notification.created",
                        Map.of("category", req.getCategory(), "severity", req.getSeverity()));
            } catch (RedisConnectionFailureException ex) {
                meterRegistry.counter("notification.emitter.errors",
                        "type", "RedisPublish").increment();
            }

            return ResponseEntity.ok(new NotificationEmitResponse(true, id));
        } catch (DataAccessException | PersistenceException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[notification-emit] swallowed for category={} tenant={}: {}",
                    req.getCategory(), req.getTenantId(), ex.getMessage());
            // 500 lets the producer count failures, but its swallow-and-log
            // contract still applies. Producer's primary work is unaffected.
            return ResponseEntity.internalServerError().body(Map.of("error", "emit failed"));
        }
    }

    private static String validate(NotificationEmitRequest req) {
        if (req == null) return "request body required";
        if (isBlank(req.getTenantId())) return "tenantId required";
        if (isBlank(req.getCategory())) return "category required";
        if (req.getSeverity() == null || !SEVERITIES.contains(req.getSeverity())) {
            return "severity must be one of " + SEVERITIES;
        }
        if (req.getSubjectType() == null || !SUBJECT_TYPES.contains(req.getSubjectType())) {
            return "subjectType must be one of " + SUBJECT_TYPES;
        }
        if (req.getSubjectId() == null) return "subjectId required";
        if (isBlank(req.getSourceId())) return "sourceId required";
        if (req.getPayload() == null || !req.getPayload().containsKey("status")) {
            return "payload.status required (V174 contract)";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Tiny JSON serializer matching NotificationEmitter.toJson semantics. */
    static final class JsonUtil {

        static String toJson(Map<String, Object> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":");
                appendValue(sb, e.getValue());
            }
            sb.append('}');
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void appendValue(StringBuilder sb, Object v) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Map<?, ?> m) {
                sb.append(toJson((Map<String, Object>) m));
            } else if (v instanceof List<?> list) {
                sb.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(',');
                    first = false;
                    appendValue(sb, item);
                }
                sb.append(']');
            } else {
                sb.append('"').append(escape(v.toString())).append('"');
            }
        }

        private static String escape(String s) {
            StringBuilder out = new StringBuilder(s.length() + 2);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                }
            }
            return out.toString();
        }
    }
}
