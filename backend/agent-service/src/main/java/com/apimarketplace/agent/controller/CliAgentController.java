package com.apimarketplace.agent.controller;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.dto.cli.*;
import com.apimarketplace.agent.service.cli.CliAgentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for CLI-based tool execution.
 * Claude Code IS the agent - these endpoints manage sessions and execute tools directly.
 *
 * Flow: POST /session → POST /tool (N times) → POST /session/end
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/cli")
@RequiredArgsConstructor
public class CliAgentController {

    private final CliAgentService cliAgentService;
    private final TenantResolver tenantResolver;
    private final AuthClient authClient;

    @PostMapping("/session")
    public ResponseEntity<CliSessionResponse> startSession(
            @RequestBody(required = false) CliSessionStartRequest request,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        // PR20 - workspace identity snapshotted at session start so the
        // observability row written at session end carries the same scope.
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        // 2026-05-28 fix: the agent-cli MCP stdio bridge (mcp/agent-cli-server.mjs)
        // talks directly to agent-service without going through the gateway, so
        // there is no JWT validator to inject X-Organization-ID. Pre-V261 the
        // null fell through silently; post-V261 every downstream INSERT trips
        // "organizationId required after V261". Falling back to the user's
        // default-personal organization (auth.organization_member where
        // is_default=true AND is_personal=true) matches the gateway's behavior
        // for first-time logins. Mirrors the CeDownloadController pattern from
        // the V261 migration. Returns null only in the degenerate state where
        // the user has no personal membership at all - leave the null in place
        // so the downstream guard surfaces the real "user has no workspace"
        // problem rather than masking it with a synthetic id.
        if (organizationId == null || organizationId.isBlank()) {
            String fallback = authClient.getDefaultOrganizationIdForUser(tenantId);
            if (fallback != null && !fallback.isBlank()) {
                log.debug("CLI session: no X-Organization-ID header - falling back to default-personal org {} for user {}",
                        fallback, tenantId);
                organizationId = fallback;
            }
        }
        // 2026-05-21 prod fix: also resolve orgRole so session.credentials
        // get __orgId__ + __orgRole__ stamped at start. Every subsequent tool
        // call in the session forwards these to downstream services via
        // RemoteToolExecutionService, closing the "Access restricted" +
        // "organizationId required after V261" bugs for ALL MCP tools
        // (agent, interface, application, datasource, ...).
        String organizationRole = tenantResolver.resolveOrgRole(httpRequest);
        // 2026-06-09 fix: resolve the caller's PLATFORM roles (e.g. ADMIN) so
        // admin-gated tools (SkillCrudModule.callerIsAdmin → modifying a GLOBAL
        // skill) work on the bridge/CLI path. The agent-cli MCP stdio bridge
        // (mcp/agent-cli-server.mjs) bypasses the gateway, so there is no JWT
        // validator to inject X-User-Roles - without server-side resolution the
        // session credentials never carried __userRoles__ and every global-skill
        // edit by an admin was rejected with "Only admins can modify global
        // skills". We resolve from the authoritative persisted store
        // (auth.user_roles, the same source JWT role claims are built from) via
        // AuthClient - never from a request header. A header would be spoofable
        // here precisely because the gateway (the only trusted role-injector)
        // is bypassed on this path; the DB lookup is unspoofable. Mirrors the
        // org-fallback above and InternalBridgeAccessController.persistedAdmin.
        String userRoles = authClient.getUserRoles(tenantId);
        log.info("CLI session start: tenant={}, org={}, role={}", tenantId, organizationId, organizationRole);
        return ResponseEntity.ok(cliAgentService.startSession(request, tenantId, organizationId, organizationRole, userRoles));
    }

    @PostMapping("/tool")
    public ResponseEntity<CliToolResponse> executeTool(
            @RequestBody @Valid CliToolRequest request,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        // org context is on session.credentials (stamped at startSession);
        // no need to re-resolve from header on every tool call.
        return ResponseEntity.ok(cliAgentService.executeTool(request, tenantId));
    }

    @PostMapping("/session/end")
    public ResponseEntity<CliSessionEndResponse> endSession(
            @RequestBody Map sessionEndRequest,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String sessionId = sessionEndRequest != null
            ? (String) sessionEndRequest.get("sessionId") : null;
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("CLI session end: tenant={}, session={}", tenantId, sessionId);
        return ResponseEntity.ok(cliAgentService.endSession(sessionId, tenantId));
    }
}
