package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.StreamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class StreamService {
    
    private static final Logger log = LoggerFactory.getLogger(StreamService.class);
    private final StreamRepository streamRepository;
    private final ConversationRepository conversationRepository;

    public StreamService(StreamRepository streamRepository, ConversationRepository conversationRepository) {
        this.streamRepository = streamRepository;
        this.conversationRepository = conversationRepository;
    }

    /**
     * Create a new active stream for a conversation.
     *
     * <p>Returns {@code null} in TWO cases, both meaning "no row was written, carry on": the
     * conversation no longer exists, or the {@code streamId} is already bound to a different
     * conversation (see below). Callers treat either as a graceful skip.
     *
     * <p>Neither null case is an error: the FK one avoids a {@code streams_conversation_id_fkey}
     * violation for a chat sent to a deleted / never-persisted / out-of-scope conversation, and
     * the chat itself is unaffected either way.
     *
     * <p><b>Idempotent on {@code streamId}.</b> The same stream is registered twice whenever the
     * chat path has already created the row and agent-service/orchestrator then calls
     * {@code /api/internal/streams/register} for it. Without the guard below that second call
     * INSERTed blindly and {@code streams_stream_id_key} rejected it at flush, which produced a
     * Hibernate ERROR per registration (30 of them in a 3h prod window) right after a
     * "Created new stream" line that had already claimed success. Re-registration now returns the
     * existing row.
     *
     * <p>This is a read-then-insert, so it narrows the window rather than closing it: two
     * registrations landing at once (the chat path and {@code /api/internal/streams/register}
     * run in different services) can both read empty and one will still lose on the unique
     * index. Catching that here cannot help - a constraint violation poisons the surrounding
     * transaction, so the re-read would fail too - and both callers already treat the throw as
     * a skip. The observed production case is sequential, which this removes entirely.
     */
    @Transactional
    public Stream createStream(String conversationId, String streamId, String userId) {
        // Idempotency runs FIRST - before both the conversation lock and the stop below.
        //
        // Why not after the stop: the stream being re-registered may itself BE the conversation's
        // active one, so stopping first and then handing it back would terminate a live stream on
        // a duplicate registration. That is the ordering this must keep.
        //
        // Two lesser consequences of running first, both inert today: a row whose conversation has
        // since vanished is returned instead of answered with null (the FK makes that vanishingly
        // rare), and a finished row comes back STOPPED where the caller asked for a new ACTIVE
        // stream (neither caller reads the status).
        Optional<Stream> existing = streamRepository.findByStreamId(streamId);
        if (existing.isPresent()) {
            Stream row = existing.get();
            if (!Objects.equals(row.getConversationId(), conversationId)) {
                // streamId is a UUID and unique platform-wide, so this means the caller paired it
                // with the wrong conversation. Neither inserting (unique index) nor returning a
                // row from another conversation is right, so refuse loudly and let the caller
                // treat it as the graceful skip a null already means.
                log.warn("Stream {} is already registered on conversation {}, refusing to re-register "
                        + "it on {}", streamId, row.getConversationId(), conversationId);
                return null;
            }
            log.debug("Stream {} already registered for conversation {}; reusing the existing row",
                    streamId, conversationId);
            return row;
        }

        // Guard the FK: take the SAME FOR KEY SHARE lock on the conversation row that the streams
        // INSERT's FK takes implicitly. Held to commit, a concurrent conversation delete blocks until
        // this insert commits, so a missing conversation can no longer turn the insert into an
        // unhandled streams_conversation_id_fkey violation (a full stacktrace + an aborted side-tx).
        // Conversation already gone -> skip the row rather than insert a dangling reference.
        if (conversationRepository.lockConversationRowIfExists(conversationId).isEmpty()) {
            log.debug("Conversation {} no longer exists; skipping stream row for stream {}",
                    conversationId, streamId);
            return null;
        }

        // First, stop any existing active streams for this conversation
        stopAllActiveStreamsForConversation(conversationId);
        
        // Create new stream
        Stream stream = new Stream();
        stream.setId(UUID.randomUUID().toString());
        stream.setConversationId(conversationId);
        stream.setStreamId(streamId);
        stream.setUserId(userId);
        stream.setStatus(Stream.StreamStatus.ACTIVE);
        
        // saveAndFlush, not save: the INSERT must succeed BEFORE the success line is logged.
        // With a deferred flush the log announced a stream that the commit then rejected, so the
        // logs carried a "✅ Created" immediately followed by the constraint error for the same id.
        Stream savedStream = streamRepository.saveAndFlush(stream);
        log.info("✅ [STREAM SERVICE] Created new stream {} for conversation {}", streamId, conversationId);

        return savedStream;
    }
    
    /**
     * Get active stream for a conversation
     */
    @Transactional(readOnly = true)
    public Optional<Stream> getActiveStream(String conversationId) {
        return streamRepository.findActiveStreamByConversationId(conversationId);
    }
    
    /**
     * Get stream by streamId
     */
    @Transactional(readOnly = true)
    public Optional<Stream> getStreamByStreamId(String streamId) {
        return streamRepository.findByStreamId(streamId);
    }
    
    /**
     * Stop all active streams for a conversation
     */
    @Transactional
    public boolean stopAllActiveStreamsForConversation(String conversationId) {
        int stoppedCount = streamRepository.stopAllActiveStreamsForConversation(conversationId, LocalDateTime.now());
        
        if (stoppedCount > 0) {
            log.info("🛑 [STREAM SERVICE] Stopped {} active streams for conversation {}", stoppedCount, conversationId);
            return true;
        }
        
        return false;
    }
    
    /**
     * Mark stream as completed
     */
    @Transactional
    public void markStreamAsCompleted(String streamId) {
        Optional<Stream> streamOpt = streamRepository.findByStreamId(streamId);
        if (streamOpt.isPresent()) {
            Stream stream = streamOpt.get();
            stream.markAsCompleted();
            streamRepository.save(stream);
            log.info("✅ [STREAM SERVICE] Marked stream {} as completed", streamId);
        }
    }
    
    /**
     * Mark stream as stopped
     */
    @Transactional
    public void markStreamAsStopped(String streamId) {
        Optional<Stream> streamOpt = streamRepository.findByStreamId(streamId);
        if (streamOpt.isPresent()) {
            Stream stream = streamOpt.get();
            stream.markAsStopped();
            streamRepository.save(stream);
            log.info("🛑 [STREAM SERVICE] Marked stream {} as stopped", streamId);
        }
    }
    
    /**
     * Mark stream as interrupted (producer death: pod drain/shutdown, heartbeat lost)
     */
    @Transactional
    public void markStreamAsInterrupted(String streamId, String reason) {
        Optional<Stream> streamOpt = streamRepository.findByStreamId(streamId);
        if (streamOpt.isPresent()) {
            Stream stream = streamOpt.get();
            stream.markAsInterrupted(reason);
            streamRepository.save(stream);
            log.warn("⚠️ [STREAM SERVICE] Marked stream {} as interrupted: {}", streamId, reason);
        }
    }

    /**
     * Mark stream as error
     */
    @Transactional
    public void markStreamAsError(String streamId, String errorMessage) {
        Optional<Stream> streamOpt = streamRepository.findByStreamId(streamId);
        if (streamOpt.isPresent()) {
            Stream stream = streamOpt.get();
            stream.markAsError(errorMessage);
            streamRepository.save(stream);
            // DEBUG: a state transition. Every caller logs the failure itself.
            log.debug("[STREAM SERVICE] Marked stream {} as error: {}", streamId, errorMessage);
        }
    }
    
}
