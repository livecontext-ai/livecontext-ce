package com.apimarketplace.orchestrator.controllers.channel;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelException;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelForbiddenException;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ConnectRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST surface for a workspace's linked chat channels.
 *
 * <ul>
 *   <li>{@code GET    /api/chat-channels}            → the connected destinations</li>
 *   <li>{@code POST   /api/chat-channels/discover}   → chats the bot has heard from</li>
 *   <li>{@code POST   /api/chat-channels}            → connect one destination</li>
 *   <li>{@code POST   /api/chat-channels/{id}/default} → make it the workspace default</li>
 *   <li>{@code DELETE /api/chat-channels/{id}}       → forget it</li>
 * </ul>
 *
 * <p>Tenant from the gateway-injected {@code X-User-ID}, workspace from the
 * request-bound organization. Every caller-fixable refusal comes back as 400
 * with the service's own sentence, because these are read by a person mid-setup
 * (or relayed verbatim by the agent guiding them) and a generic 500 would end
 * the conversation.
 */
@RestController
@RequestMapping("/api/chat-channels")
public class ChatChannelController {

    private final ChatChannelService service;

    public ChatChannelController(ChatChannelService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestHeader("X-User-ID") String tenantId) {
        return ResponseEntity.ok(Map.of("channels", service.list(orgId())));
    }

    @PostMapping("/discover")
    public ResponseEntity<ChatChannelService.DiscoveryResult> discover(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestBody Map<String, Object> body) {
        // Discovery is the first step of connecting and calls the service with the account's
        // credential; a member who may not connect gets the refusal here, not after picking a chat.
        // Same set as the channel tool's WRITE_ACTIONS.
        requireWriteRole();
        return ResponseEntity.ok(service.discover(tenantId, channelOf(body), credentialIdOf(body)));
    }

    @PostMapping
    public ResponseEntity<ChatChannelService.ConnectResult> connect(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestBody Map<String, Object> body) {
        requireWriteRole();
        ConnectRequest request = new ConnectRequest(
                channelOf(body),
                credentialIdOf(body),
                str(body.get("chatId")),
                str(body.get("chatTitle")),
                str(body.get("chatType")),
                Boolean.TRUE.equals(body.get("makeDefault")),
                stringList(body.get("allowedUserIds")),
                str(body.get("accountSetting")));
        return ResponseEntity.ok(service.connect(tenantId, orgId(), request,
                ChatChannelService.ChangeSource.MANUAL));
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<ChatChannelService.ChatChannelSummary> setDefault(
            @RequestHeader("X-User-ID") String tenantId,
            @PathVariable UUID id) {
        requireWriteRole();
        return ResponseEntity.ok(service.setDefault(tenantId, orgId(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(
            @RequestHeader("X-User-ID") String tenantId,
            @PathVariable UUID id) {
        requireWriteRole();
        service.disconnect(tenantId, orgId(), id, ChatChannelService.ChangeSource.MANUAL);
        return ResponseEntity.noContent().build();
    }

    /**
     * Caller-fixable refusals come back as 400 with the service's own sentence, except a
     * role refusal, which is a 403: a read-only member has nothing to fix in their request.
     *
     * <p>The status comes from the refusal's TYPE, never from its wording. It used to be picked
     * by testing the message for "read-only", a sentence written in another method: rewording it
     * turned a security refusal into a client error without a word, and any unrelated refusal
     * mentioning a read-only chat was answered 403. A message is prose for a human; it is not a
     * protocol.
     */
    @ExceptionHandler(ChatChannelException.class)
    public ResponseEntity<Map<String, String>> handleRefusal(ChatChannelException ex) {
        HttpStatus status = ex instanceof ChatChannelForbiddenException
                ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }

    private static String orgId() {
        return TenantResolver.requireOrgId(TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Only a member who may write to the workspace may change where it is reached.
     *
     * <p>Not a formality: the default destination decides WHO is asked to authorize an
     * agent's sensitive actions, and a press on that message runs them under the agent
     * owner's tenant. A read-only member able to point it at their own chat would be able
     * to approve on the workspace's behalf. Reading the list stays open, like any other
     * workspace resource.
     */
    private static void requireWriteRole() {
        if (OrgAccessGuard.isRoleWriteBlocked(TenantResolver.currentRequestOrganizationId(),
                TenantResolver.currentRequestOrganizationRole())) {
            throw new ChatChannelForbiddenException("Your role in this workspace is read-only, "
                    + "so you cannot change where it is reached.");
        }
    }

    /** Telegram was the first channel, so an omitted one still means it rather than a 400. */
    private static String channelOf(Map<String, Object> body) {
        String channel = str(body.get("channel"));
        return channel != null && !channel.isBlank() ? channel : "telegram";
    }

    /**
     * Absent means "use the workspace default"; present means it has to be readable.
     *
     * <p>A value that is neither a number nor a numeric string used to fall through to null,
     * which is the SAME answer as absent, so {@code {"credentialId": true}} quietly connected
     * through whichever credential the workspace defaults to. The caller named a credential and
     * got another one, with a 200.
     */
    private static Long credentialIdOf(Map<String, Object> body) {
        Object raw = body.get("credentialId");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                throw new ChatChannelException("credentialId must be a number, got '" + text + "'.");
            }
        }
        throw new ChatChannelException("credentialId must be a number, got '" + raw + "'.");
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * Absent stays ABSENT, so the service can tell it from an empty list.
     *
     * <p>Returning an empty list for a missing key is what made the allow-list unclearable: the
     * writer could no longer distinguish "the caller said nothing, leave it alone" from "the
     * caller asked for no restriction", so it ignored empty and the restriction could be
     * replaced but never removed. A JSON array that IS empty still arrives as an empty list,
     * which is the clear.
     */
    private static java.util.List<String> stringList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof java.util.List<?> list)) {
            return java.util.List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }
}
