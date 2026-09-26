package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.common.storage.service.StorageService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Reads back the full text of a trace row whose content was moved to storage.
 *
 * <p>{@code AgentObservabilityService} stores any message or tool-call content over 8,192
 * characters in storage and keeps only its first 500 characters on the row, followed by
 * {@code ...[truncated]}. Nothing ever read the stored text back, so the execution trace
 * showed those 500 characters and a "content truncated" label for every long prompt or
 * reply (about one message in nine in production), while the full text sat in storage,
 * paid for and unreadable. A classify node given a 12,748-character email showed 514.
 *
 * <p>Reads are best-effort: the inline excerpt is returned when the stored text is gone
 * (retention) or unreadable, so a trace never fails to render because of it.
 *
 * <p><b>Called OUTSIDE the query's transaction, on purpose.</b> The trace endpoints read their
 * page in {@code AgentMetricsQueryService}'s read-only transaction and hand it here once that
 * transaction has ended and returned its connection. Reading storage from inside it would
 * either hold a second pooled connection per request for every long row (the pool is 30), or
 * turn one storage error into a rollback-only commit and a 500 on the whole trace.
 */
@Component
public class TraceContentLoader {

    private static final Logger logger = LoggerFactory.getLogger(TraceContentLoader.class);

    /**
     * The longest message content read back into the trace screen: far above any prompt or
     * reply, explicit so a TOOL-role message carrying a raw tool result stays bounded.
     */
    public static final int MAX_MESSAGE_CONTENT_CHARS = 1024 * 1024;

    /** The longest tool-call content read back into the trace screen (file bodies, pages). */
    public static final int MAX_TOOL_CALL_CONTENT_CHARS = 256 * 1024;

    /**
     * The most text read back into ONE trace page, across its rows. The per-row limits alone
     * let a page of 100 long rows reach tens of megabytes; past this budget the remaining rows
     * keep their excerpt, which the trace labels as truncated.
     */
    public static final long MAX_PAGE_READ_BACK_CHARS = 4L * 1024 * 1024;

    private final StorageService storageService;
    private final EntityManager entityManager;

    @Autowired
    public TraceContentLoader(StorageService storageService, EntityManager entityManager) {
        this.storageService = storageService;
        this.entityManager = entityManager;
    }

    /** For callers that only read single rows ({@link #fullContent}) and never replace a row's content. */
    TraceContentLoader(StorageService storageService) {
        this(storageService, null);
    }

    /**
     * Replaces the excerpt of each long message on this page with its full text, within the
     * per-row and per-page limits. Each changed entity is detached first, so the full text is
     * for this response and is never flushed back over the excerpt the row keeps.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void readBackMessages(List<AgentExecutionMessageEntity> page) {
        long budget = MAX_PAGE_READ_BACK_CHARS;
        for (AgentExecutionMessageEntity message : page) {
            if (message.getContentStorageId() == null) continue;
            int length = message.getContentLength() != null ? message.getContentLength() : MAX_MESSAGE_CONTENT_CHARS;
            if (length > budget) continue;
            String full = fullContent(message.getContent(), message.getContentStorageId(),
                message.getTenantId(), message.getContentLength(), MAX_MESSAGE_CONTENT_CHARS);
            if (full == null || full.equals(message.getContent())) continue;
            detach(message);
            message.setContent(full);
            budget -= full.length();
        }
    }

    /** {@link #readBackMessages} for tool calls, with their own, lower, per-row limit. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void readBackToolCalls(List<AgentExecutionToolCallEntity> page) {
        long budget = MAX_PAGE_READ_BACK_CHARS;
        for (AgentExecutionToolCallEntity call : page) {
            if (call.getContentStorageId() == null) continue;
            int length = call.getContentLength() != null ? call.getContentLength() : MAX_TOOL_CALL_CONTENT_CHARS;
            if (length > budget) continue;
            String full = fullContent(call.getContent(), call.getContentStorageId(),
                call.getTenantId(), call.getContentLength(), MAX_TOOL_CALL_CONTENT_CHARS);
            if (full == null || full.equals(call.getContent())) continue;
            detach(call);
            call.setContent(full);
            budget -= full.length();
        }
    }

    private void detach(Object entity) {
        if (entityManager == null) return;
        try {
            if (entityManager.contains(entity)) {
                entityManager.detach(entity);
            }
        } catch (RuntimeException e) {
            // No persistence context bound to this thread: the entity is already detached.
            logger.debug("Trace row not detached: {}", e.getMessage());
        }
    }

    /**
     * The row's full content when it was moved to storage and is at most {@code maxChars}
     * long, otherwise {@code inline} unchanged.
     *
     * @param inline        the content stored on the row (the excerpt when {@code storageId} is set)
     * @param storageId     where the full text was moved, or null when it was never moved
     * @param tenantId      the row's own tenant, which is the tenant the text was saved under
     * @param contentLength the full text's length as recorded on the row, or null
     * @param maxChars      the longest text the caller accepts; a longer one keeps the excerpt
     */
    // NOT_SUPPORTED: should a caller hold a transaction, it is suspended for the storage read,
    // so a storage error cannot mark it rollback-only.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String fullContent(String inline, UUID storageId, String tenantId, Integer contentLength, int maxChars) {
        if (storageId == null || tenantId == null) {
            return inline;
        }
        // Decided on the recorded length before any read, so an oversized payload is never
        // loaded only to be thrown away.
        if (contentLength != null && contentLength > maxChars) {
            return inline;
        }
        try {
            return storageService.getByIdReadOnly(storageId, tenantId)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(text -> text.length() <= maxChars)
                .orElse(inline);
        } catch (RuntimeException e) {
            logger.warn("Could not read the stored trace content {}: {}", storageId, e.getMessage());
            return inline;
        }
    }
}
