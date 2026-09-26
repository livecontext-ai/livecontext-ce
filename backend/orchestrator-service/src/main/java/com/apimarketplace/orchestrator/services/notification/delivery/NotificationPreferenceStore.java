package com.apimarketplace.orchestrator.services.notification.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * A person's delivery choice per topic, in ONE workspace. Someone in two
 * workspaces chooses twice: the same failure alert can matter in the production
 * workspace and be noise in a sandbox.
 *
 * <p>A person-scoped topic ({@link NotificationTopic#isPersonScoped()}) is stored once per
 * person under {@link #PERSON_SCOPE} instead of a workspace id: credit alerts always land in the
 * personal workspace, so a per-workspace choice made anywhere else would be saved and ignored.
 *
 * <p>Only explicit choices are stored. A topic with no row answers its
 * {@link NotificationTopic#defaultDelivery()}, so changing a default later
 * reaches everyone who never touched the setting.
 */
@Component
public class NotificationPreferenceStore {

    /** The organization_id value of a person-wide choice. Never a real workspace id (those are UUIDs). */
    static final String PERSON_SCOPE = "*";

    private final JdbcTemplate jdbc;

    public NotificationPreferenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every topic, with the stored choice or the default. */
    public Map<NotificationTopic, DeliveryMode> resolveAll(String tenantId, String organizationId) {
        Map<NotificationTopic, DeliveryMode> out = new EnumMap<>(NotificationTopic.class);
        for (NotificationTopic topic : NotificationTopic.values()) {
            out.put(topic, topic.defaultDelivery());
        }
        jdbc.query("SELECT topic, delivery, organization_id FROM orchestrator.notification_preferences "
                        + "WHERE tenant_id = ? AND organization_id IN (?, ?)",
                rs -> {
                    String delivery = rs.getString("delivery");
                    boolean personRow = PERSON_SCOPE.equals(rs.getString("organization_id"));
                    NotificationTopic.parse(rs.getString("topic"))
                            // A row only counts in its own scope: a stray workspace row for a
                            // person-scoped topic must not shadow the person's real choice.
                            .filter(topic -> topic.isPersonScoped() == personRow)
                            .ifPresent(topic -> DeliveryMode.parse(delivery).ifPresent(mode -> out.put(topic, mode)));
                },
                tenantId, organizationId, PERSON_SCOPE);
        return out;
    }

    public DeliveryMode resolve(String tenantId, String organizationId, NotificationTopic topic) {
        return resolveAll(tenantId, organizationId).get(topic);
    }

    public void save(String tenantId, String organizationId, NotificationTopic topic, DeliveryMode mode) {
        jdbc.update("INSERT INTO orchestrator.notification_preferences "
                        + "(tenant_id, organization_id, topic, delivery, updated_at) VALUES (?, ?, ?, ?, now()) "
                        + "ON CONFLICT (tenant_id, organization_id, topic) "
                        + "DO UPDATE SET delivery = EXCLUDED.delivery, updated_at = now()",
                tenantId, topic.isPersonScoped() ? PERSON_SCOPE : organizationId, topic.name(), mode.name());
    }
}
