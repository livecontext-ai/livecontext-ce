package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.DmAttachmentDto;
import com.apimarketplace.conversation.dto.DmMessageDto;
import com.apimarketplace.conversation.dto.DmThreadDto;
import com.apimarketplace.conversation.entity.DmMessage;
import com.apimarketplace.conversation.entity.DmThread;
import com.apimarketplace.conversation.repository.DmMessageRepository;
import com.apimarketplace.conversation.repository.DmThreadRepository;
import com.apimarketplace.conversation.streaming.DmEventPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Direct-message domain logic. Threads are identity-level (global, not org-scoped);
 * access to a thread/message is purely "is the caller one of the two participants".
 */
@Service
public class DmService {

    private static final int MAX_MESSAGE_LEN = 4000;
    private static final int PREVIEW_LEN = 280;
    private static final int MAX_ATTACHMENTS = 5;
    private static final Set<String> ATTACHMENT_TYPES = Set.of("IMAGE", "PDF", "TEXT", "OTHER");
    /** Plain (de)serialization of the attachments JSONB column - no Spring config needed. */
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<DmAttachmentDto>> ATTACHMENT_LIST =
            new TypeReference<>() {
            };

    private final DmThreadRepository threadRepository;
    private final DmMessageRepository messageRepository;
    private final AttachmentService attachmentService;

    /**
     * Nullable: the reactive WS publisher lives in {@code conversation.streaming}.
     * Cloud picks it up through component scan; CE monolith wires just this Redis
     * publisher explicitly while keeping the rest of the streaming package excluded.
     * It remains nullable for tests and defensive fallback wiring.
     */
    @Nullable
    private final DmEventPublisher eventPublisher;

    /**
     * Self-reference so the new-thread INSERT runs through the Spring proxy with
     * {@code REQUIRES_NEW} ({@link #saveThreadInNewTransaction}). The thread id is a
     * Hibernate-generated UUID, so {@code save()} does not flush until commit - a
     * concurrent first-open of the same pair would otherwise surface the
     * {@code dm_threads_unique_pair} violation at outer-commit (a 500 the caller
     * cannot recover) instead of at the call site where we can re-fetch the winner.
     * {@code @Lazy} breaks the constructor self-reference cycle.
     */
    private final DmService self;

    public DmService(DmThreadRepository threadRepository,
                     DmMessageRepository messageRepository,
                     AttachmentService attachmentService,
                     @Nullable DmEventPublisher eventPublisher,
                     @Lazy DmService self) {
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.attachmentService = attachmentService;
        this.eventPublisher = eventPublisher;
        this.self = self;
    }

    /** Opens (or returns the existing) 1:1 thread between the caller and another user. */
    @Transactional
    public DmThreadDto openOrGetThread(String userId, String otherUserId) {
        if (otherUserId == null || otherUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "otherUserId is required");
        }
        if (otherUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot open a DM thread with yourself");
        }
        String lo = DmThread.lo(userId, otherUserId);
        String hi = DmThread.hi(userId, otherUserId);
        DmThread thread = threadRepository
                .findByParticipantLoAndParticipantHi(lo, hi)
                .orElseGet(() -> openNewThreadHandlingRace(userId, otherUserId, lo, hi));
        // Re-opening a conversation the caller had "deleted" resurfaces it (for them only).
        if (thread.isHiddenFor(userId)) {
            thread.unhideFor(userId);
            threadRepository.save(thread);
        }
        return toThreadDto(thread, userId);
    }

    /**
     * Inserts the new thread in a fresh transaction so the unique-pair violation is
     * observable here; on a lost race, re-fetches and returns the concurrent winner so
     * both callers converge on the same thread instead of one getting a 500.
     */
    private DmThread openNewThreadHandlingRace(String userId, String otherUserId, String lo, String hi) {
        try {
            return self != null
                    ? self.saveThreadInNewTransaction(userId, otherUserId)
                    : threadRepository.save(new DmThread(userId, otherUserId));
        } catch (DataIntegrityViolationException e) {
            return threadRepository.findByParticipantLoAndParticipantHi(lo, hi)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * <b>Internal only.</b> Public for the Spring proxy to apply {@code REQUIRES_NEW}.
     * Runs the INSERT in a suspended inner transaction so a {@code dm_threads_unique_pair}
     * violation flushes synchronously (leaving the outer transaction clean for the
     * race-loser re-fetch in {@link #openNewThreadHandlingRace}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DmThread saveThreadInNewTransaction(String userId, String otherUserId) {
        return threadRepository.save(new DmThread(userId, otherUserId));
    }

    /** The caller's inbox - every VISIBLE thread they participate in, most-recent first. */
    @Transactional(readOnly = true)
    public List<DmThreadDto> listThreads(String userId) {
        return threadRepository.findThreadsForUser(userId).stream()
                .filter(t -> !t.isHiddenFor(userId))
                .map(t -> toThreadDto(t, userId))
                .toList();
    }

    /**
     * "Delete" the conversation from the CALLER's inbox only - a soft, one-sided hide.
     * Messages are kept and the other participant is unaffected; any new activity
     * (re-opening it or a new message either way) resurfaces the thread.
     */
    @Transactional
    public void deleteThreadForUser(String userId, String threadId) {
        DmThread thread = loadThread(threadId);
        requireParticipant(thread, userId);
        thread.hideFor(userId);
        threadRepository.save(thread);
    }

    /** A single thread as seen by the caller. 404 if missing, 403 if not a participant. */
    @Transactional(readOnly = true)
    public DmThreadDto getThread(String userId, String threadId) {
        DmThread thread = loadThread(threadId);
        requireParticipant(thread, userId);
        return toThreadDto(thread, userId);
    }

    /** A page of a thread's messages (newest first). 404 if missing, 403 if not a participant. */
    @Transactional(readOnly = true)
    public Page<DmMessageDto> listMessages(String userId, String threadId, Pageable pageable) {
        requireParticipant(loadThread(threadId), userId);
        return messageRepository.findByThreadIdOrderByCreatedAtDesc(threadId, pageable).map(DmService::toMessageDto);
    }

    /** Sends a message, bumps the thread, and fans the event out to both participants over WS. */
    @Transactional
    public DmMessageDto sendMessage(String userId, String threadId, String rawContent) {
        return sendMessage(userId, threadId, rawContent, null);
    }

    /**
     * Sends a message with optional attachments. Content may be blank when at least one
     * attachment is present. Attachment refs are format-validated only - the files are
     * fetched at download time under the SENDER's storage tenant, so a ref pointing at
     * another tenant's file simply resolves to nothing (no cross-tenant read).
     */
    @Transactional
    public DmMessageDto sendMessage(String userId, String threadId, String rawContent,
                                    @Nullable List<DmAttachmentDto> rawAttachments) {
        DmThread thread = loadThread(threadId);
        requireParticipant(thread, userId);

        List<DmAttachmentDto> attachments = sanitizeAttachments(rawAttachments);
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isEmpty() && attachments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message content is required");
        }
        if (content.length() > MAX_MESSAGE_LEN) {
            content = content.substring(0, MAX_MESSAGE_LEN);
        }

        DmMessage message = new DmMessage(threadId, userId, content);
        if (!attachments.isEmpty()) {
            try {
                message.setAttachments(JSON.writeValueAsString(attachments));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attachments");
            }
        }
        DmMessage saved = messageRepository.save(message);
        // New activity resurfaces the conversation for BOTH sides (one may have hidden it).
        thread.unhideAll();
        thread.setLastMessageAt(saved.getCreatedAt());
        String preview = !content.isEmpty()
                ? content
                : "📎 " + attachments.get(0).fileName();
        thread.setLastMessagePreview(preview.length() > PREVIEW_LEN ? preview.substring(0, PREVIEW_LEN) : preview);
        threadRepository.save(thread);

        DmMessageDto dto = toMessageDto(saved);
        if (eventPublisher != null) {
            eventPublisher.publishMessage(threadId, dto);
            eventPublisher.publishInbox(thread.otherParticipant(userId), threadId, dto);
        }
        return dto;
    }

    /**
     * Loads a DM attachment for a thread participant. Access path: the caller must be a
     * participant of the thread AND the storageId must be referenced by a message of
     * that thread; the bytes are then read from the chat attachment store under the
     * SENDER's tenant. Anything else (unknown id, not referenced, foreign tenant) returns 404.
     *
     * <p>NOT {@code readOnly}: the storage read path updates {@code storage.accessed_at}
     * (StorageService.getEntityById then updateAccessTime) inside the SAME transaction
     * (REQUIRED propagation). With {@code readOnly = true} Postgres rejected that UPDATE
     * ("cannot execute UPDATE in a read-only transaction") and every DM attachment
     * download 500'd - images never rendered in DM threads.
     */
    @Transactional
    public AttachmentService.AttachmentData getAttachment(String userId, String threadId, String storageId) {
        requireParticipant(loadThread(threadId), userId);
        UUID id;
        try {
            id = UUID.fromString(storageId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid storage ID format");
        }
        String senderUserId = messageRepository.findAttachmentSenderInThread(threadId, storageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        return attachmentService.getAttachmentWithMetadata(id, senderUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
    }

    /**
     * Format-validates and normalises the attachment refs: at most {@link #MAX_ATTACHMENTS},
     * each with a UUID storageId and a non-blank fileName; type falls back to OTHER,
     * fileName/mimeType are clamped. Invalid entries reject the whole send (400) so the
     * client never believes a file was delivered when it wasn't.
     */
    private static List<DmAttachmentDto> sanitizeAttachments(@Nullable List<DmAttachmentDto> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_ATTACHMENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At most " + MAX_ATTACHMENTS + " attachments per message");
        }
        List<DmAttachmentDto> out = new ArrayList<>(raw.size());
        for (DmAttachmentDto a : raw) {
            if (a == null || a.storageId() == null || a.fileName() == null || a.fileName().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attachment reference");
            }
            try {
                UUID.fromString(a.storageId());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attachment storageId");
            }
            String type = a.type() != null && ATTACHMENT_TYPES.contains(a.type()) ? a.type() : "OTHER";
            String fileName = clamp(a.fileName().trim(), 255);
            String mimeType = a.mimeType() == null ? "application/octet-stream" : clamp(a.mimeType().trim(), 100);
            out.add(new DmAttachmentDto(a.storageId(), type, fileName, mimeType));
        }
        return List.copyOf(out);
    }

    private static String clamp(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Marks the other party's messages in this thread as read. Returns the rows updated. */
    @Transactional
    public int markRead(String userId, String threadId) {
        requireParticipant(loadThread(threadId), userId);
        int updated = messageRepository.markThreadReadFor(threadId, userId, Instant.now());
        if (updated > 0 && eventPublisher != null) {
            eventPublisher.publishRead(threadId, userId);
        }
        return updated;
    }

    /** Identity-level access check used by the gateway ChannelAuthorizer for dm:{threadId}. */
    @Transactional(readOnly = true)
    public boolean isParticipant(String threadId, String userId) {
        return threadRepository.findById(threadId).map(t -> t.hasParticipant(userId)).orElse(false);
    }

    private DmThread loadThread(String threadId) {
        return threadRepository.findById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DM thread not found"));
    }

    private void requireParticipant(DmThread thread, String userId) {
        if (!thread.hasParticipant(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant of this thread");
        }
    }

    private DmThreadDto toThreadDto(DmThread t, String userId) {
        return new DmThreadDto(
                t.getId(),
                t.otherParticipant(userId),
                t.getLastMessageAt(),
                t.getLastMessagePreview(),
                messageRepository.countUnreadForUser(t.getId(), userId),
                t.getCreatedAt());
    }

    private static DmMessageDto toMessageDto(DmMessage m) {
        return new DmMessageDto(m.getId(), m.getThreadId(), m.getSenderUserId(), m.getContent(),
                parseAttachments(m.getAttachments()), m.getReadAt(), m.getCreatedAt());
    }

    /** Defensive parse of the JSONB column - a malformed row degrades to "no attachments". */
    private static List<DmAttachmentDto> parseAttachments(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, ATTACHMENT_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
