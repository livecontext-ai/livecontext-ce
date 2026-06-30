package com.apimarketplace.auth.service;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.scope.OrgAccessCacheInvalidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * Full CRUD service for org resource restrictions.
 * Auth-service owns the auth.org_resource_restrictions table.
 * Other services access it via AuthClient HTTP calls.
 */
@Service
public class OrgRestrictionQueryService {

    private static final Logger log = LoggerFactory.getLogger(OrgRestrictionQueryService.class);

    private final JdbcTemplate jdbc;
    private final EventBus eventBus;

    public OrgRestrictionQueryService(JdbcTemplate jdbc, EventBus eventBus) {
        this.jdbc = jdbc;
        this.eventBus = eventBus;
    }

    /**
     * Returns the set of resource IDs that are <b>read-restricted</b> (fully blocked)
     * for a given org member - i.e. rows with {@code permission = 'DENY'}. READ-only
     * rows are intentionally excluded so the member still sees them in list/read views.
     */
    public Set<String> getRestrictedResourceIds(String orgId, String userId, String resourceType) {
        List<String> ids = jdbc.queryForList(
                "SELECT resource_id FROM org_resource_restrictions " +
                "WHERE organization_id = ? AND member_user_id = ? AND resource_type = ? " +
                "AND permission = 'DENY'",
                String.class, orgId, userId, resourceType);
        return new HashSet<>(ids);
    }

    /**
     * Returns the set of resource IDs the member may not <b>write</b> (delete / assign /
     * modify) - ANY restriction row (DENY or READ) blocks writes.
     */
    public Set<String> getWriteRestrictedResourceIds(String orgId, String userId, String resourceType) {
        List<String> ids = jdbc.queryForList(
                "SELECT resource_id FROM org_resource_restrictions " +
                "WHERE organization_id = ? AND member_user_id = ? AND resource_type = ?",
                String.class, orgId, userId, resourceType);
        return new HashSet<>(ids);
    }

    /**
     * Add a single restriction with a permission level (DENY = fully blocked, READ = read-only).
     */
    @Transactional
    public void restrictAccess(String orgId, String memberUserId, String resourceType,
                                String resourceId, String restrictedBy, String permission) {
        String perm = normalizePermission(permission);
        jdbc.update(
                "INSERT INTO org_resource_restrictions (organization_id, member_user_id, resource_type, resource_id, restricted_by, permission, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, NOW()) " +
                "ON CONFLICT (organization_id, member_user_id, resource_type, resource_id) DO UPDATE SET permission = EXCLUDED.permission, restricted_by = EXCLUDED.restricted_by",
                orgId, memberUserId, resourceType, resourceId, restrictedBy, perm);
        publishInvalidationAfterCommit(orgId, memberUserId);
    }

    private static String normalizePermission(String permission) {
        return "READ".equalsIgnoreCase(permission) ? "READ" : "DENY";
    }

    /**
     * Remove a single restriction.
     */
    @Transactional
    public void grantAccess(String orgId, String memberUserId, String resourceType, String resourceId) {
        jdbc.update(
                "DELETE FROM org_resource_restrictions " +
                "WHERE organization_id = ? AND member_user_id = ? AND resource_type = ? AND resource_id = ?",
                orgId, memberUserId, resourceType, resourceId);
        publishInvalidationAfterCommit(orgId, memberUserId);
    }

    /**
     * Bulk set restrictions for a member + resource type, each with a permission level.
     * Replaces all existing restrictions for that (member, type) with the new map
     * ({@code resourceId -> "DENY"|"READ"}).
     */
    @Transactional
    public void setRestrictions(String orgId, String memberUserId, String resourceType,
                                 Map<String, String> permissionsById, String restrictedBy) {
        // Delete all existing for this member + type
        jdbc.update(
                "DELETE FROM org_resource_restrictions " +
                "WHERE organization_id = ? AND member_user_id = ? AND resource_type = ?",
                orgId, memberUserId, resourceType);

        // Insert new restrictions with their permission level
        if (permissionsById != null) {
            for (Map.Entry<String, String> e : permissionsById.entrySet()) {
                jdbc.update(
                        "INSERT INTO org_resource_restrictions (organization_id, member_user_id, resource_type, resource_id, restricted_by, permission, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                        orgId, memberUserId, resourceType, e.getKey(), restrictedBy, normalizePermission(e.getValue()));
            }
        }
        publishInvalidationAfterCommit(orgId, memberUserId);
    }

    /**
     * Get all restrictions for a member (for UI display), including the permission level.
     */
    public List<Map<String, Object>> getMemberRestrictions(String orgId, String memberUserId) {
        return jdbc.queryForList(
                "SELECT id, organization_id, member_user_id, resource_type, resource_id, restricted_by, permission, created_at " +
                "FROM org_resource_restrictions " +
                "WHERE organization_id = ? AND member_user_id = ?",
                orgId, memberUserId);
    }

    private void publishInvalidationAfterCommit(String orgId, String memberUserId) {
        if (orgId == null || orgId.isBlank() || memberUserId == null || memberUserId.isBlank()) {
            return;
        }
        Runnable publish = () -> {
            try {
                eventBus.publish(
                        OrgAccessCacheInvalidation.CHANNEL,
                        OrgAccessCacheInvalidation.messageFor(orgId, memberUserId));
            } catch (Exception | LinkageError e) {
                log.warn("Failed to publish org access cache invalidation for org={} member={}: {}",
                        orgId, memberUserId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
