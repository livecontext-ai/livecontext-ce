package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.StreamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
     * <p>Returns {@code null} (instead of FK-violating) when the conversation no longer exists - e.g.
     * a chat sent to a deleted / never-persisted / out-of-scope conversation. The caller treats a
     * null stream as a graceful skip; the chat itself is unaffected.
     */
    @Transactional
    public Stream createStream(String conversationId, String streamId, String userId) {
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
        
        Stream savedStream = streamRepository.save(stream);
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
            log.error("❌ [STREAM SERVICE] Marked stream {} as error: {}", streamId, errorMessage);
        }
    }
    
}
