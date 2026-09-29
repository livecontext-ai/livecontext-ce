package com.apimarketplace.catalog.web;

import com.apimarketplace.common.web.AdminRoleGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Admin gate for catalog-service writes that change GLOBAL catalog state (shared by every
 * tenant): the lexical search index, response skeletons, default categories.
 *
 * <p>Two callers are legitimate, so two credentials are accepted:
 * <ul>
 *   <li>a platform admin coming through the gateway (or the CE monolith filter), which
 *       injects {@code X-User-Roles} from the validated JWT and strips any client-supplied
 *       value, so {@code ADMIN} there cannot be forged by an end user;</li>
 *   <li>the catalog import Job, which calls catalog-service directly in-cluster and presents
 *       {@code X-Internal-Admin-Token} matching {@code catalog.admin-token} (the same shared
 *       secret ToolResponseController and ToolCategoryController already require).</li>
 * </ul>
 * A blank configured token disables the token path (deny by default); it never opens it.
 */
@Component
public class CatalogAdminAccess {

    public static final String ADMIN_TOKEN_HEADER = "X-Internal-Admin-Token";

    private final String configuredToken;

    public CatalogAdminAccess(@Value("${catalog.admin-token:}") String configuredToken) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
    }

    /** True when the caller is a platform admin or presents the shared admin token. */
    public boolean isAdmin(String roles, String presentedToken) {
        return AdminRoleGuard.isAdmin(roles) || tokenMatches(presentedToken);
    }

    /** A 403 response when the caller is not an admin, or null when the call may proceed. */
    public ResponseEntity<Map<String, Object>> denyIfNotAdmin(String roles, String presentedToken) {
        if (isAdmin(roles, presentedToken)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Admin access required"));
    }

    private boolean tokenMatches(String presentedToken) {
        if (configuredToken.isEmpty() || presentedToken == null) {
            return false;
        }
        String presented = presentedToken.trim();
        if (presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
