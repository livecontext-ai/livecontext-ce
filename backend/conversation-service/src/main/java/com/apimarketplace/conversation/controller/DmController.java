package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.DmMessageDto;
import com.apimarketplace.conversation.dto.DmThreadDto;
import com.apimarketplace.conversation.dto.OpenThreadRequest;
import com.apimarketplace.conversation.dto.SendDmMessageRequest;
import com.apimarketplace.conversation.service.DmService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct-message REST API. Requires authentication (the gateway / MonolithSecurityFilter
 * injects X-User-ID); threads are identity-level (global), so no org header is consulted.
 */
@RestController
@RequestMapping("/api/dm")
public class DmController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DmService dmService;

    public DmController(DmService dmService) {
        this.dmService = dmService;
    }

    /** The caller's DM inbox (threads they participate in, most-recent first). */
    @GetMapping("/threads")
    public List<DmThreadDto> listThreads(@RequestHeader("X-User-ID") String userId) {
        return dmService.listThreads(userId);
    }

    /** Open (or fetch the existing) 1:1 thread with another user. */
    @PostMapping("/threads")
    public DmThreadDto openThread(@RequestHeader("X-User-ID") String userId,
                                  @RequestBody OpenThreadRequest request) {
        return dmService.openOrGetThread(userId, request.otherUserId());
    }

    /** A single thread (resolves the other participant for the thread view). */
    @GetMapping("/threads/{threadId}")
    public DmThreadDto getThread(@RequestHeader("X-User-ID") String userId,
                                 @PathVariable String threadId) {
        return dmService.getThread(userId, threadId);
    }

    /** A page of a thread's messages (newest first). */
    @GetMapping("/threads/{threadId}/messages")
    public Map<String, Object> listMessages(@RequestHeader("X-User-ID") String userId,
                                            @PathVariable String threadId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "30") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Page<DmMessageDto> messages = dmService.listMessages(userId, threadId, PageRequest.of(safePage, safeSize));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", messages.getContent());
        body.put("totalCount", messages.getTotalElements());
        body.put("page", messages.getNumber());
        body.put("size", messages.getSize());
        return body;
    }

    /** Send a message to a thread. Fans out live to both participants over WebSocket. */
    @PostMapping("/threads/{threadId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public DmMessageDto sendMessage(@RequestHeader("X-User-ID") String userId,
                                    @PathVariable String threadId,
                                    @RequestBody SendDmMessageRequest request) {
        return dmService.sendMessage(userId, threadId, request.content(), request.attachments());
    }

    /**
     * Download a DM attachment. DM-scoped access path (the generic chat attachment
     * endpoint is tenant-scoped, so the RECIPIENT could never read the sender's file
     * through it): participant check + the storageId must be referenced by a message
     * of this thread, then the bytes are served from the sender's store.
     */
    @GetMapping("/threads/{threadId}/attachments/{storageId}")
    public ResponseEntity<byte[]> getAttachment(@RequestHeader("X-User-ID") String userId,
                                                @PathVariable String threadId,
                                                @PathVariable String storageId) {
        var data = dmService.getAttachment(userId, threadId, storageId);
        String mimeType = data.mimeType() != null ? data.mimeType() : "application/octet-stream";
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, mimeType)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(data.data());
    }

    /**
     * "Delete" the conversation from the caller's inbox (soft, one-sided hide - the
     * other participant keeps the thread; new activity resurfaces it).
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/threads/{threadId}")
    public Map<String, Object> deleteThread(@RequestHeader("X-User-ID") String userId,
                                            @PathVariable String threadId) {
        dmService.deleteThreadForUser(userId, threadId);
        return Map.of("deleted", true);
    }

    /** Mark the other party's messages in this thread as read. */
    @PostMapping("/threads/{threadId}/read")
    public Map<String, Object> markRead(@RequestHeader("X-User-ID") String userId,
                                        @PathVariable String threadId) {
        return Map.of("markedRead", dmService.markRead(userId, threadId));
    }

    /**
     * Honour the HTTP status of DmService's ResponseStatusException (400/403/404).
     * Controller-local handlers take precedence over the app-wide
     * {@code @RestControllerAdvice} GlobalExceptionHandler, whose catch-all
     * {@code Exception} mapping would otherwise turn these into a generic 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() != null ? ex.getReason() : "request_failed"));
    }
}
